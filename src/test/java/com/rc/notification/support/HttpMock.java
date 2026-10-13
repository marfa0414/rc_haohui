package com.rc.notification.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Real loopback HTTP boundary. It is deliberately only available on the test classpath. */
public final class HttpMock implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final List<ReceivedRequest> journal = new CopyOnWriteArrayList<>();

    public HttpMock() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.setExecutor(executor);
        server.start();
    }

    public void route(String path, HttpHandler handler) {
        server.createContext(path, exchange -> {
            byte[] bytes = exchange.getRequestBody().readAllBytes();
            journal.add(new ReceivedRequest(exchange.getRequestMethod(), exchange.getRequestURI(),
                    Map.copyOf(exchange.getRequestHeaders()), bytes, Instant.now()));
            handler.handle(exchange);
        });
    }

    public URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    public URI virtualUri(String hostname, String path) {
        return URI.create("http://" + hostname + ":" + server.getAddress().getPort() + path);
    }

    public List<ReceivedRequest> journal() {
        return List.copyOf(journal);
    }

    public long count(String path) {
        return journal.stream().filter(request -> request.uri().getPath().equals(path)).count();
    }

    /** IMF-fixdate requires a two-digit day; the JDK RFC-1123 formatter emits a single digit on days 1–9. */
    public static String httpDate(Instant instant) {
        return java.time.format.DateTimeFormatter.ofPattern("EEE, dd MMM uuuu HH:mm:ss 'GMT'", java.util.Locale.US)
                .withZone(java.time.ZoneOffset.UTC).format(instant);
    }

    public static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    public record ReceivedRequest(String method, URI uri, Map<String, List<String>> headers, byte[] body, Instant receivedAt) {
        public String firstHeader(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .flatMap(entry -> entry.getValue().stream())
                    .findFirst().orElse(null);
        }
    }
}
