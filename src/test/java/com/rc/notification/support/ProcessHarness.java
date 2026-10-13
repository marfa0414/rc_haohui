package com.rc.notification.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rc.notification.worker.WorkerLifecycleListener.Phase;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/** Owns exactly one independently launched application JVM. Database and HTTP mocks stay caller-owned. */
public final class ProcessHarness implements AutoCloseable {
    public record PhaseEvent(long sequence, long pid, Phase phase, UUID taskId, int runNumber, int attemptNo,
                             UUID leaseToken, Instant observedAt, boolean blocked) { }
    private final Path directory;
    private final Path logPath;
    private final Process process;
    private final List<JsonNode> lifecycle = new ArrayList<>();
    private final Object commandLock = new Object();
    private URI baseUri;
    private boolean closed;
    private Phase armedPhase;
    private UUID armedTask;
    private long armedAfterSequence;

    private ProcessHarness(Path directory, Path logPath, Process process) {
        this.directory = directory; this.logPath = logPath; this.process = process;
        record("STARTED");
    }

    public static ProcessHarness start(RealPostgres database, String mode, Map<String, Object> overrides,
                                       Map<String, String> environmentOverrides) throws Exception {
        if (!List.of("api", "worker", "all").contains(mode)) throw new IllegalArgumentException("Invalid child role");
        Path root = Path.of(".cache", "processes").toAbsolutePath();
        ProcessProtocol.privateDirectory(root);
        Path directory = Files.createTempDirectory(root, "cp09-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path logPath = root.resolve(directory.getFileName() + ".log");
        Files.createFile(logPath, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Process process = null;
        ProcessHarness harness = null;
        try {
            Map<String, Object> settings = defaults(database, mode);
            settings.putAll(overrides);
            // This is a test-only controller and must never listen on a routable interface.
            settings.put("server.address", "127.0.0.1");
            settings.put("server.port", "0");
            Properties file = new Properties();
            settings.forEach((key, value) -> file.setProperty(key, value.toString()));
            Path configuration = directory.resolve("application.properties");
            Files.createFile(configuration, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try (var stream = Files.newOutputStream(configuration)) { file.store(stream, "Private test-only configuration; not evidence"); }
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            List<String> arguments = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xms32m", "-Xmx256m"));
            if (Long.parseLong(settings.getOrDefault(JvmClockSkew.PROPERTY, "0").toString()) != 0) {
                Path agent = Path.of(net.bytebuddy.agent.ByteBuddyAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                if (!Files.isRegularFile(agent)) throw new IllegalStateException("Required existing test agent jar is unavailable");
                arguments.add("-javaagent:" + agent);
            }
            arguments.addAll(List.of("-cp", classpath, ProcessApplication.class.getName(), directory.toString()));
            ProcessBuilder builder = new ProcessBuilder(arguments);
            builder.environment().putAll(environmentOverrides);
            builder.redirectErrorStream(true).redirectOutput(logPath.toFile());
            process = builder.start();
            harness = new ProcessHarness(directory, logPath, process);
            harness.awaitReady(Duration.ofSeconds(30));
            return harness;
        } catch (Throwable failure) {
            if (harness != null) harness.close();
            else {
                if (process != null) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
                cleanup(directory);
            }
            throw failure;
        }
    }
    public static ProcessHarness start(RealPostgres database, String mode, Map<String, Object> overrides) throws Exception {
        return start(database, mode, overrides, Map.of());
    }

    private static Map<String, Object> defaults(RealPostgres database, String mode) {
        Map<String, Object> settings = new HashMap<>(database.springProperties(mode));
        settings.put("app.environment", "test");
        settings.put("app.worker.scheduling-enabled", "false");
        settings.put("app.worker.shutdown-grace-period", "2s");
        settings.put("app.outbound.allowed-hosts", "supplier.invalid,supplier-two.invalid,oauth.invalid");
        caller(settings, 0, "caller-a", NotificationApiFixture.CALLER_A, "BUSINESS", "tenant-a", "user.registered,order.paid");
        caller(settings, 1, "caller-b", NotificationApiFixture.CALLER_B, "BUSINESS", "tenant-b", "user.registered,order.paid");
        caller(settings, 2, "admin", NotificationApiFixture.ADMIN, "ADMIN", "*", "*");
        caller(settings, 3, "limited", NotificationApiFixture.LIMITED, "BUSINESS", "tenant-a", "order.paid");
        caller(settings, 4, "config-a", NotificationApiFixture.CONFIG_A, "CONFIG_ADMIN", "tenant-a", "*");
        caller(settings, 5, "config-b", NotificationApiFixture.CONFIG_B, "CONFIG_ADMIN", "tenant-b", "*");
        caller(settings, 6, "operator-a", NotificationApiFixture.OPERATOR_A, "OPERATOR", "tenant-a", "*");
        caller(settings, 7, "operator-b", NotificationApiFixture.OPERATOR_B, "OPERATOR", "tenant-b", "*");
        settings.put("app.secrets.tenant-references[tenant-a]", "env:CP02_VENDOR_SECRET,env:CP03_NEVER_RESOLVED_SECRET,"
                + "env:CP04_TEST_SECRET,env:CP04_INTENTIONALLY_MISSING_SECRET,env:CP06_CLIENT_SECRET,env:CP06_ROTATED_SECRET,"
                + "env:EXAMPLE_CRM_API_KEY,env:EXAMPLE_ADS_API_KEY,env:EXAMPLE_CRM_CLIENT_SECRET");
        settings.put("app.secrets.tenant-references[tenant-b]", "env:CP02_VENDOR_SECRET,env:CP03_NEVER_RESOLVED_SECRET");
        return settings;
    }
    private static void caller(Map<String, Object> settings, int index, String id, String token, String roles, String tenants, String operations) {
        String prefix = "app.security.callers[" + index + "].";
        settings.put(prefix + "id", id); settings.put(prefix + "token", token); settings.put(prefix + "roles", roles);
        settings.put(prefix + "tenants", tenants); settings.put(prefix + "operations", operations);
    }

    private void awaitReady(Duration timeout) throws Exception {
        long end = System.nanoTime() + timeout.toNanos();
        Path ready = directory.resolve("ready.json");
        while (System.nanoTime() < end) {
            ensureAlive();
            if (Files.exists(ready)) {
                JsonNode state = ProcessProtocol.read(ready);
                if (state.path("pid").asLong() != process.pid()) throw new IllegalStateException("Child identity mismatch");
                baseUri = URI.create("http://127.0.0.1:" + state.path("port").asInt());
                record("READY"); return;
            }
            if (Files.exists(directory.resolve("failed.json"))) throw new IllegalStateException("Child failed to initialize; diagnostics at " + logPath);
            pause();
        }
        throw new IllegalStateException("Child did not initialize in time; diagnostics at " + logPath);
    }
    public long pid() { return process.pid(); }
    public boolean isAlive() { return process.isAlive(); }
    public URI baseUri() { return baseUri; }
    public Path logPath() { return logPath; }
    public int exitCode() { return process.exitValue(); }
    public JsonNode state() throws Exception { return command("STATE").path("state"); }
    /** Explicit, healthy-database probe; ordinary STATE/awaitIdle never acquire a database connection. */
    public JsonNode databaseObservation() throws Exception { return command("DATABASE_OBSERVATION").path("database"); }
    public int tick() throws Exception { return command("TICK").path("claimed").asInt(); }
    public void arm(Phase phase, UUID taskId) throws Exception {
        ObjectNode request = ProcessProtocol.JSON.createObjectNode().put("operation", "ARM").put("phase", phase.name());
        if (taskId != null) request.put("taskId", taskId.toString());
        JsonNode result = command(request);
        armedPhase = phase; armedTask = taskId; armedAfterSequence = result.path("afterSequence").asLong();
    }
    public void release() throws Exception { command("RELEASE"); }
    public void startPolling(Duration interval) throws Exception {
        command(ProcessProtocol.JSON.createObjectNode().put("operation", "START_POLLING").put("intervalMillis", interval.toMillis()));
    }
    public void stopPolling() throws Exception { command("STOP_POLLING"); }
    public void awaitIdle(Duration timeout) throws Exception {
        long end = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < end) {
            if (state().path("idle").asBoolean()) return;
            pause();
        }
        throw new IllegalStateException("Child worker did not become idle; diagnostics at " + logPath);
    }
    public PhaseEvent awaitPhase(Phase phase, UUID taskId, Duration timeout) throws Exception {
        boolean armed = phase == armedPhase && (armedTask == null || armedTask.equals(taskId));
        return awaitPhase(phase, taskId, armed ? armedAfterSequence : 0, armed, timeout);
    }
    public PhaseEvent awaitPhase(Phase phase, UUID taskId, long afterSequence, Duration timeout) throws Exception {
        return awaitPhase(phase, taskId, afterSequence, false, timeout);
    }
    private PhaseEvent awaitPhase(Phase phase, UUID taskId, long afterSequence, boolean requireBlocked, Duration timeout) throws Exception {
        long end = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < end) {
            for (PhaseEvent event : phaseEvents()) if (event.sequence() > afterSequence && (!requireBlocked || event.blocked())
                    && event.phase() == phase && (taskId == null || taskId.equals(event.taskId()))) return event;
            ensureAlive(); pause();
        }
        throw new IllegalStateException("Child phase was not observed in time; diagnostics at " + logPath);
    }
    public List<PhaseEvent> phaseEvents() throws IOException {
        List<PhaseEvent> result = new ArrayList<>();
        Path phases = directory.resolve("phases");
        if (!Files.isDirectory(phases)) return List.of();
        try (var files = Files.newDirectoryStream(phases, "*.json")) {
            for (Path file : files) {
                JsonNode event = ProcessProtocol.read(file);
                result.add(new PhaseEvent(event.path("sequence").asLong(), event.path("pid").asLong(), Phase.valueOf(event.path("phase").asText()),
                        UUID.fromString(event.path("taskId").asText()), event.path("runNumber").asInt(), event.path("attemptNo").asInt(),
                        UUID.fromString(event.path("leaseToken").asText()), Instant.parse(event.path("observedAt").asText()), event.path("blocked").asBoolean()));
            }
        }
        result.sort(Comparator.comparingLong(PhaseEvent::sequence));
        return List.copyOf(result);
    }
    private JsonNode command(String operation) throws Exception { return command(ProcessProtocol.JSON.createObjectNode().put("operation", operation)); }
    private JsonNode command(ObjectNode request) throws Exception {
        synchronized (commandLock) { return exchangeCommand(request); }
    }
    private JsonNode exchangeCommand(ObjectNode request) throws Exception {
        ensureAlive();
        String id = UUID.randomUUID() + ".json";
        Path response = directory.resolve("response-" + id);
        ProcessProtocol.write(directory.resolve("command-" + id), request);
        long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < end) {
            ensureAlive();
            if (Files.exists(response)) {
                JsonNode result = ProcessProtocol.read(response); Files.delete(response);
                if (!result.path("ok").asBoolean()) throw new IllegalStateException("Child control operation failed: "
                        + result.path("errorCode").asText() + "; diagnostics at " + logPath);
                return result;
            }
            pause();
        }
        throw new IllegalStateException("Child control operation timed out; diagnostics at " + logPath);
    }

    /** A real OS graceful termination; no command writes a final task result on behalf of the child. */
    public void terminateGracefully(Duration timeout) throws Exception {
        if (!process.isAlive()) return;
        record("SIGTERM_SENT");
        process.destroy();
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) throw new IllegalStateException("Child did not exit after graceful termination");
        record("EXITED");
    }
    public void forceKill() throws Exception {
        if (!process.isAlive()) return;
        record("FORCE_KILL_SENT");
        // Only descendants of the Process we created can be targeted; never accept an arbitrary PID.
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IllegalStateException("Owned child survived forcible termination");
        for (ProcessHandle descendant : descendants) if (descendant.isAlive()) descendant.onExit().get(5, TimeUnit.SECONDS);
        record("EXITED");
    }
    public synchronized List<JsonNode> lifecycle() { return List.copyOf(lifecycle); }
    private synchronized void record(String action) {
        ObjectNode event = ProcessProtocol.JSON.createObjectNode().put("pid", process.pid()).put("action", action).put("observedAt", Instant.now().toString());
        if (!process.isAlive()) event.put("exitCode", process.exitValue());
        lifecycle.add(event);
    }
    private void ensureAlive() {
        if (!process.isAlive()) throw new IllegalStateException("Child exited with " + process.exitValue() + "; diagnostics at " + logPath);
    }
    private static void pause() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Process observation interrupted");
        LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
    }
    @Override public void close() throws Exception {
        if (closed) return;
        closed = true;
        try {
            if (process.isAlive()) {
                try { terminateGracefully(Duration.ofSeconds(8)); }
                catch (Exception incomplete) { forceKill(); }
            }
        } finally { cleanup(directory); }
    }
    private static void cleanup(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
