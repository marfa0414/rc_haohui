package com.rc.notification.support;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Real temporary CA and PKIX trust store. No permissive trust manager or hostname verifier exists. */
public final class TlsHttpMock implements AutoCloseable {
    public static final String HOST = "tls.supplier.test";
    private static final char[] PASSWORD = "cp08-test-keystore-only".toCharArray();
    public enum CertificateKind { VALID, EXPIRED, WRONG_HOST, UNTRUSTED }
    public record Received(String host, List<String> sni, String path, byte[] body) { }

    public static final class Certificates implements AutoCloseable {
        private final Path directory;
        private final KeyStore trust;
        public Certificates(Path directory) throws Exception {
            this.directory = directory;
            Files.createDirectories(directory);
            keytool("-genkeypair", "-alias", "ca", "-keystore", file("ca.p12"), "-dname", "CN=CP08 Temporary Test CA",
                    "-ext", "BC:critical=ca:true", "-ext", "ku=keyCertSign,cRLSign", "-startdate", "-30d", "-validity", "3650");
            keytool("-exportcert", "-alias", "ca", "-keystore", file("ca.p12"), "-file", file("ca.der"));
            for (CertificateKind kind : CertificateKind.values()) {
                String name = kind.name().toLowerCase(java.util.Locale.ROOT);
                String hostname = kind == CertificateKind.WRONG_HOST ? "other.supplier.test" : HOST;
                keytool("-genkeypair", "-alias", "server", "-keystore", file(name + ".p12"), "-dname", "CN=" + hostname,
                        "-ext", "san=dns:" + hostname, "-validity", "3650", "-startdate", "-1d");
                if (kind == CertificateKind.UNTRUSTED) continue;
                keytool("-certreq", "-alias", "server", "-keystore", file(name + ".p12"), "-file", file(name + ".csr"));
                keytool("-gencert", "-alias", "ca", "-keystore", file("ca.p12"), "-infile", file(name + ".csr"),
                        "-outfile", file(name + ".der"), "-ext", "san=dns:" + hostname, "-ext", "ku=digitalSignature,keyEncipherment",
                        "-ext", "eku=serverAuth", "-startdate", kind == CertificateKind.EXPIRED ? "-10d" : "-1d",
                        "-validity", kind == CertificateKind.EXPIRED ? "1" : "3650");
                keytool("-importcert", "-noprompt", "-alias", "ca", "-keystore", file(name + ".p12"), "-file", file("ca.der"));
                keytool("-importcert", "-alias", "server", "-keystore", file(name + ".p12"), "-file", file(name + ".der"));
            }
            trust = KeyStore.getInstance("PKCS12"); trust.load(null, PASSWORD);
            var factory = java.security.cert.CertificateFactory.getInstance("X.509");
            try (var in = Files.newInputStream(directory.resolve("ca.der"))) { trust.setCertificateEntry("ca", factory.generateCertificate(in)); }
        }
        public SSLContext clientContext() throws Exception {
            var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); managers.init(trust);
            var context = SSLContext.getInstance("TLS"); context.init(null, managers.getTrustManagers(), null); return context;
        }
        public X509Certificate leaf(CertificateKind kind) throws Exception { return (X509Certificate) store(kind).getCertificate("server"); }
        private SSLContext serverContext(CertificateKind kind) throws Exception {
            var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); managers.init(store(kind), PASSWORD);
            var context = SSLContext.getInstance("TLS"); context.init(managers.getKeyManagers(), null, null); return context;
        }
        private KeyStore store(CertificateKind kind) throws Exception {
            var store = KeyStore.getInstance("PKCS12");
            try (var in = Files.newInputStream(directory.resolve(kind.name().toLowerCase(java.util.Locale.ROOT) + ".p12"))) { store.load(in, PASSWORD); }
            return store;
        }
        private String file(String name) { return directory.resolve(name).toString(); }
        private void keytool(String... arguments) throws Exception {
            var command = new ArrayList<String>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
            command.addAll(List.of(arguments)); command.addAll(List.of("-storetype", "PKCS12", "-storepass", new String(PASSWORD)));
            if (List.of(arguments).contains("-genkeypair")) command.addAll(List.of("-keyalg", "RSA", "-keysize", "2048", "-keypass", new String(PASSWORD)));
            Path output = directory.resolve("keytool-output.txt");
            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("Test certificate generation exceeded its deadline"); }
            if (process.exitValue() != 0) throw new IOException("Test certificate generation failed: " + Files.readString(output));
        }
        @Override public void close() throws IOException {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private final HttpsServer server;
    private final java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(2);
    private final List<Received> journal = new CopyOnWriteArrayList<>();
    public TlsHttpMock(Certificates certificates, CertificateKind kind) throws Exception {
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        server.setHttpsConfigurator(new HttpsConfigurator(certificates.serverContext(kind)));
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            var https = (com.sun.net.httpserver.HttpsExchange) exchange;
            List<String> sni = https.getSSLSession() instanceof ExtendedSSLSession extended
                    ? extended.getRequestedServerNames().stream().filter(SNIHostName.class::isInstance)
                        .map(SNIHostName.class::cast).map(SNIHostName::getAsciiName).toList() : List.of();
            journal.add(new Received(exchange.getRequestHeaders().getFirst("Host"), sni,
                    exchange.getRequestURI().getPath(), exchange.getRequestBody().readNBytes(65537)));
            String body = exchange.getRequestURI().getPath().equals("/token")
                    ? "{\"access_token\":\"cp08-fake-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}" : "ok";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
            finally { exchange.close(); }
        });
        server.start();
    }
    public URI uri(String path) { return URI.create("https://" + HOST + ":" + server.getAddress().getPort() + path); }
    public List<Received> journal() { return List.copyOf(journal); }
    @Override public void close() throws InterruptedException {
        server.stop(0); executor.shutdownNow();
        if (!executor.awaitTermination(Duration.ofSeconds(3).toMillis(), TimeUnit.MILLISECONDS)) throw new IllegalStateException("TLS mock did not stop");
    }
}
