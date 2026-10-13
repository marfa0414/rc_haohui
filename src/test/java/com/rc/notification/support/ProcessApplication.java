package com.rc.notification.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rc.notification.NotificationApplication;
import com.rc.notification.worker.Attempt;
import com.rc.notification.worker.Lease;
import com.rc.notification.worker.WorkerLifecycleListener;
import com.rc.notification.worker.WorkerRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.MapPropertySource;

/** Separate JVM entry point used only from test classpath, with no production control endpoint. */
public final class ProcessApplication {
    private static Control control;
    private ProcessApplication() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected a private process directory");
        Path directory = Path.of(args[0]);
        Properties loaded = new Properties();
        try (var stream = Files.newInputStream(directory.resolve("application.properties"))) { loaded.load(stream); }
        Map<String, Object> properties = new HashMap<>();
        loaded.forEach((key, value) -> properties.put(key.toString(), value));
        // The running context owns its property values; remove the on-disk credential copy immediately.
        Files.delete(directory.resolve("application.properties"));
        JvmClockSkew.install(Long.parseLong(properties.getOrDefault(JvmClockSkew.PROPERTY, "0").toString()));
        control = new Control(directory);
        try (ConfigurableApplicationContext application = new SpringApplicationBuilder(NotificationApplication.class)
                .web(WebApplicationType.SERVLET).sources(ProcessConfiguration.class)
                .initializers(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("independent-process-test", properties))).run()) {
            control.application = application;
            ProcessProtocol.write(directory.resolve("ready.json"), control.state().put("port",
                    ((ServletWebServerApplicationContext) application).getWebServer().getPort()));
            while (application.isActive() && !Thread.currentThread().isInterrupted()) {
                try (var commands = Files.newDirectoryStream(directory, "command-*.json")) {
                    for (Path command : commands) control.handle(command);
                }
                Thread.sleep(10);
            }
        } catch (Throwable failed) {
            ProcessProtocol.write(directory.resolve("failed.json"), ProcessProtocol.JSON.createObjectNode()
                    .put("errorCode", "CHILD_APPLICATION_FAILED").put("exceptionType", failed.getClass().getSimpleName()));
            System.exit(2);
        } finally { control.stopPolling(); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class ProcessConfiguration {
        @Bean WorkerLifecycleListener independentProcessListener() { return control; }
    }

    private static final class Gate {
        private final WorkerLifecycleListener.Phase phase;
        private final UUID taskId;
        private final AtomicBoolean reached = new AtomicBoolean();
        private final Semaphore released = new Semaphore(0);
        Gate(WorkerLifecycleListener.Phase phase, UUID taskId) { this.phase = phase; this.taskId = taskId; }
    }

    private static final class Control implements WorkerLifecycleListener {
        private final Path directory;
        private final AtomicReference<Gate> gate = new AtomicReference<>();
        private final AtomicLong sequence = new AtomicLong();
        private ConfigurableApplicationContext application;
        private ScheduledExecutorService poller;
        Control(Path directory) throws Exception {
            this.directory = directory;
            ProcessProtocol.privateDirectory(directory.resolve("phases"));
        }
        @Override public void onPhase(Phase phase, Lease lease, Attempt attempt) throws InterruptedException {
            Gate current = gate.get();
            boolean blocked = current != null && current.phase == phase && (current.taskId == null || current.taskId.equals(lease.taskId()))
                    && current.reached.compareAndSet(false, true);
            synchronized (sequence) {
                long number = sequence.incrementAndGet();
                if (number > 10000) throw new IllegalStateException("Process phase evidence capacity exceeded");
                ObjectNode event = ProcessProtocol.JSON.createObjectNode().put("sequence", number).put("pid", ProcessHandle.current().pid())
                        .put("phase", phase.name()).put("taskId", lease.taskId().toString()).put("runNumber", lease.runNumber())
                        .put("attemptNo", attempt == null ? 0 : attempt.attemptNo()).put("leaseToken", lease.leaseToken().toString())
                        .put("observedAt", Instant.now().toString()).put("blocked", blocked);
                try { ProcessProtocol.write(directory.resolve("phases").resolve(String.format("%020d.json", number)), event); }
                catch (java.io.IOException failure) { throw new IllegalStateException("Cannot persist process phase evidence"); }
            }
            if (blocked && !current.released.tryAcquire(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Process test barrier exceeded its sixty-second bound");
            }
        }
        WorkerRuntime worker() { return application.getBeanProvider(WorkerRuntime.class).getIfAvailable(); }
        ObjectNode state() {
            WorkerRuntime worker = worker();
            ObjectNode result = ProcessProtocol.JSON.createObjectNode().put("pid", ProcessHandle.current().pid())
                    .put("observedAt", Instant.now().toString()).put("javaTimezone", java.util.TimeZone.getDefault().getID())
                    .put("workerEnabled", worker != null).put("apiEnabled", !application.getEnvironment().getProperty("app.mode", "all").equals("worker"))
                    .put("running", worker != null && worker.isRunning()).put("active", worker == null ? 0 : worker.activeCount())
                    .put("queued", worker == null ? 0 : worker.queuedCount()).put("providerEntries", worker == null ? 0 : worker.providerEntryCount())
                    .put("idle", worker == null || worker.isIdle()).put("phaseCount", sequence.get());
            result.setAll(JvmClockSkew.observation());
            return result;
        }
        void handle(Path command) throws Exception {
            String id = command.getFileName().toString().substring("command-".length());
            ObjectNode response;
            try {
                JsonNode request = ProcessProtocol.read(command);
                String operation = request.path("operation").asText();
                response = ProcessProtocol.JSON.createObjectNode().put("ok", true);
                switch (operation) {
                    case "STATE" -> response.set("state", state());
                    case "DATABASE_OBSERVATION" -> {
                        var row = application.getBean(org.springframework.jdbc.core.JdbcTemplate.class)
                                .queryForMap("SELECT clock_timestamp() AS observed_at, current_setting('TimeZone') AS timezone");
                        response.putObject("database").put("now", ((java.sql.Timestamp) row.get("observed_at")).toInstant().toString())
                                .put("timezone", row.get("timezone").toString());
                    }
                    case "TICK" -> {
                        WorkerRuntime worker = worker();
                        if (worker == null) throw new IllegalStateException("Worker role is disabled");
                        response.put("claimed", worker.tick());
                    }
                    case "ARM" -> {
                        Phase phase = Phase.valueOf(request.path("phase").asText());
                        UUID taskId = request.hasNonNull("taskId") ? UUID.fromString(request.path("taskId").asText()) : null;
                        response.put("afterSequence", sequence.get());
                        if (!gate.compareAndSet(null, new Gate(phase, taskId))) throw new IllegalStateException("A barrier is already armed");
                    }
                    case "RELEASE" -> {
                        Gate previous = gate.getAndSet(null);
                        if (previous != null) previous.released.release();
                    }
                    case "START_POLLING" -> startPolling(Duration.ofMillis(request.path("intervalMillis").asLong()));
                    case "STOP_POLLING" -> stopPolling();
                    default -> throw new IllegalArgumentException("Unknown process operation");
                }
            } catch (Throwable failure) {
                response = ProcessProtocol.JSON.createObjectNode().put("ok", false).put("errorCode", "PROCESS_COMMAND_FAILED")
                        .put("exceptionType", failure.getClass().getSimpleName());
            }
            ProcessProtocol.write(directory.resolve("response-" + id), response);
            Files.delete(command);
        }
        synchronized void startPolling(Duration interval) {
            if (interval.toMillis() < 10 || interval.compareTo(Duration.ofSeconds(10)) > 0 || worker() == null || poller != null) {
                throw new IllegalArgumentException("Invalid process poller configuration");
            }
            poller = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "process-test-poller"); thread.setDaemon(true); return thread;
            });
            poller.scheduleWithFixedDelay(() -> { try { worker().tick(); } catch (RuntimeException unavailable) { } },
                    0, interval.toMillis(), TimeUnit.MILLISECONDS);
        }
        synchronized void stopPolling() {
            if (poller != null) { poller.shutdownNow(); poller = null; }
        }
    }
}
