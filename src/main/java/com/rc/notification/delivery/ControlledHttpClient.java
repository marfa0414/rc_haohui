package com.rc.notification.delivery;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import com.rc.notification.security.IpLiteral;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import javax.net.ssl.SSLContext;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.config.TlsConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.HostnameVerificationPolicy;
import org.apache.hc.client5.http.ssl.HttpsSupport;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;
import org.apache.hc.core5.ssl.SSLContexts;

/**
 * One physical request, a bounded executor and a total deadline including actual-address validation.
 * Business acknowledgement reads headers only; token exchanges may use bounded body decoding.
 */
public final class ControlledHttpClient implements AutoCloseable {
    public static final int MAX_RESPONSE_HEADER_BYTES = 65536;
    private static final Duration MAX_TOTAL_TIMEOUT = Duration.ofMinutes(1);
    private final ThreadLocal<CallState> callState = new ThreadLocal<>();
    private final Semaphore admission;
    private final ThreadPoolExecutor executor;
    private final CloseableHttpClient client;
    private final PoolingHttpClientConnectionManager connections;
    private final int capacity;
    private volatile Map<String, Timer> durations = Map.of();
    private final BiPredicate<URI, InetAddress> allowedTargetAddress;
    private final AtomicBoolean closed = new AtomicBoolean();

    public record Response(int statusCode, Map<String, List<String>> headers) {
        @Override public String toString() { return "Response[statusCode=" + statusCode + ",headers=REDACTED]"; }
    }
    @FunctionalInterface
    public interface BodyDecoder<T> { T decode(Response response, byte[] body) throws IOException; }
    @FunctionalInterface
    private interface ResponseHandler<T> { T handle(Response headers, ClassicHttpResponse response, CallState state) throws IOException; }
    public static final class ResponseTooLargeException extends IOException {
        public ResponseTooLargeException() { super("Response exceeded the configured byte limit"); }
    }
    public static final class ResponseHeadersTooLargeException extends IOException {
        public ResponseHeadersTooLargeException() { super("Response headers exceeded the configured byte limit"); }
    }

    public ControlledHttpClient(DnsResolver resolver, int maxConcurrent) {
        this(resolver, maxConcurrent, (target, address) -> true);
    }

    public ControlledHttpClient(DnsResolver resolver, int maxConcurrent,
                                BiPredicate<URI, InetAddress> allowedTargetAddress) {
        this(resolver, maxConcurrent, allowedTargetAddress, SSLContexts.createDefault());
    }

    /** A supplied trust context supports private CAs; hostname verification is never configurable. */
    public ControlledHttpClient(DnsResolver resolver, int maxConcurrent,
                                BiPredicate<URI, InetAddress> allowedTargetAddress, SSLContext sslContext) {
        Objects.requireNonNull(resolver, "resolver");
        this.allowedTargetAddress = Objects.requireNonNull(allowedTargetAddress, "allowedTargetAddress");
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("maxConcurrent must be positive");
        }
        capacity = maxConcurrent;
        admission = new Semaphore(maxConcurrent);
        AtomicInteger sequence = new AtomicInteger();
        executor = new ThreadPoolExecutor(maxConcurrent, maxConcurrent, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(maxConcurrent), runnable -> {
                    Thread thread = new Thread(runnable, "outbound-http-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());

        connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setTlsSocketStrategy(ClientTlsStrategyBuilder.create().setSslContext(Objects.requireNonNull(sslContext))
                        .setHostVerificationPolicy(HostnameVerificationPolicy.BOTH)
                        .setHostnameVerifier(HttpsSupport.getDefaultHostnameVerifier()).buildClassic())
                .setConnectionFactory(ManagedHttpClientConnectionFactory.builder()
                        // HttpCore 5.3 rejects once size >= maxHeaderCount: 101 accepts at most 100.
                        .http1Config(Http1Config.custom().setMaxLineLength(16384).setMaxHeaderCount(101).build())
                        .requestWriterFactory(Utf8RequestWriter::new).build())
                .setDnsResolver(new DeadlineDnsResolver(resolver))
                .setMaxConnTotal(maxConcurrent)
                .setMaxConnPerRoute(maxConcurrent)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(2))
                        .setSocketTimeout(Timeout.ofSeconds(10))
                        .build())
                .setDefaultTlsConfig(TlsConfig.custom()
                        .setHandshakeTimeout(Timeout.ofSeconds(2))
                        .build())
                .build();
        client = HttpClients.custom()
                .setConnectionManager(connections)
                // A fully consumed token entity may release its socket before finally can cancel it.
                // Disable reuse explicitly so all response paths retain the bounded-close contract.
                .setConnectionReuseStrategy((request, response, context) -> false)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofSeconds(2))
                        .setResponseTimeout(Timeout.ofSeconds(10))
                        .setHardCancellationEnabled(true)
                        .setRedirectsEnabled(false)
                        .setAuthenticationEnabled(false)
                        .build())
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .disableAuthCaching()
                .disableCookieManagement()
                .disableContentCompression()
                .build();
    }

    /** Only the two deployment-defined client roles are accepted; destinations never become labels. */
    public synchronized void bindMetrics(MeterRegistry registry, String purpose) {
        Objects.requireNonNull(registry);
        if (!List.of("business", "token").contains(purpose) || !durations.isEmpty()) {
            throw new IllegalArgumentException("Invalid or duplicate HTTP metric binding");
        }
        Gauge.builder("notification.http.connections.leased", this, value -> value.connections.getTotalStats().getLeased()).tag("client", purpose).register(registry);
        Gauge.builder("notification.http.connections.available", this, value -> value.connections.getTotalStats().getAvailable()).tag("client", purpose).register(registry);
        Gauge.builder("notification.http.connections.pending", this, value -> value.connections.getTotalStats().getPending()).tag("client", purpose).register(registry);
        Gauge.builder("notification.http.connections.max", this, value -> value.connections.getTotalStats().getMax()).tag("client", purpose).register(registry);
        Gauge.builder("notification.http.execution.active", this, value -> value.executor.getActiveCount()).tag("client", purpose).register(registry);
        Gauge.builder("notification.http.execution.queued", this, value -> value.executor.getQueue().size()).tag("client", purpose).register(registry);
        Gauge.builder("notification.http.execution.permits_used", this, value -> value.capacity - value.admission.availablePermits()).tag("client", purpose).register(registry);
        Gauge.builder("notification.http.execution.capacity", this, value -> value.capacity).tag("client", purpose).register(registry);
        var timers = new LinkedHashMap<String, Timer>();
        for (String result : List.of("COMPLETED", "TIMEOUT", "CANCELLED", "IO_ERROR", "CAPACITY", "TARGET_REJECTED", "ERROR")) {
            timers.put(result, Timer.builder("notification.http.duration").tag("client", purpose).tag("result", result).register(registry));
        }
        durations = Map.copyOf(timers);
    }

    public Response execute(URI uri, String method, Map<String, String> headers, byte[] body,
                            Duration totalTimeout)
            throws IOException, TimeoutException, InterruptedException {
        return execute(uri, method, headers, body, totalTimeout, ignored -> { });
    }

    /** The callback runs only when the bounded I/O task enters the real HTTP transport. */
    public Response execute(URI uri, String method, Map<String, String> headers, byte[] body,
                            Duration totalTimeout, Consumer<Instant> onTransportStart)
            throws IOException, TimeoutException, InterruptedException {
        return executeHandled(uri, method, headers, body, totalTimeout, onTransportStart, (head, response, state) -> head);
    }

    /** Reading and decoding run inside the same bounded task and total deadline as DNS and I/O. */
    public <T> T executeWithBody(URI uri, String method, Map<String, String> headers, byte[] body,
                                 Duration totalTimeout, int maximumResponseBytes, IntPredicate needsBody,
                                 BodyDecoder<T> decoder) throws IOException, TimeoutException, InterruptedException {
        if (maximumResponseBytes < 1 || maximumResponseBytes > 65536) throw new IllegalArgumentException("Invalid response byte limit");
        Objects.requireNonNull(needsBody, "needsBody");
        Objects.requireNonNull(decoder, "decoder");
        return executeHandled(uri, method, headers, body, totalTimeout, ignored -> { }, (head, response, state) -> {
            byte[] bytes = needsBody.test(head.statusCode()) ? readBody(response, maximumResponseBytes, state) : new byte[0];
            state.checkActive();
            T decoded = decoder.decode(head, bytes);
            state.checkActive();
            return decoded;
        });
    }

    private <T> T executeHandled(URI uri, String method, Map<String, String> headers, byte[] body,
                                 Duration totalTimeout, Consumer<Instant> onTransportStart, ResponseHandler<T> handler)
            throws IOException, TimeoutException, InterruptedException {
        long started = System.nanoTime();
        String result = "COMPLETED";
        try {
            return executeUnmeasured(uri, method, headers, body, totalTimeout, onTransportStart, handler);
        } catch (TargetAddressRejectedException rejected) { result = "TARGET_REJECTED"; throw rejected; }
        catch (TimeoutException timeout) { result = "TIMEOUT"; throw timeout; }
        catch (InterruptedException cancelled) { result = "CANCELLED"; throw cancelled; }
        catch (RejectedExecutionException capacity) { result = "CAPACITY"; throw capacity; }
        catch (IOException failure) { result = "IO_ERROR"; throw failure; }
        catch (RuntimeException failure) { result = "ERROR"; throw failure; }
        finally {
            Timer timer = durations.get(result);
            if (timer != null) timer.record(Math.max(0, System.nanoTime() - started), TimeUnit.NANOSECONDS);
        }
    }

    private <T> T executeUnmeasured(URI uri, String method, Map<String, String> headers, byte[] body,
                                 Duration totalTimeout, Consumer<Instant> onTransportStart, ResponseHandler<T> handler)
            throws IOException, TimeoutException, InterruptedException {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(totalTimeout, "totalTimeout");
        Objects.requireNonNull(onTransportStart, "onTransportStart");
        if (totalTimeout.isNegative() || totalTimeout.isZero()
                || totalTimeout.compareTo(MAX_TOTAL_TIMEOUT) > 0) {
            throw new IllegalArgumentException("totalTimeout must be positive and at most one minute");
        }
        if (closed.get()) {
            throw new IOException("HTTP client is closed");
        }
        try {
            InetAddress literal = IpLiteral.parse(uri.getHost());
            if (literal != null && !allowedTargetAddress.test(uri, literal)) throw new TargetAddressRejectedException();
        } catch (IllegalArgumentException malformed) { throw new TargetAddressRejectedException(); }
        var request = new HttpUriRequestBase(method, uri);
        headers.forEach(request::setHeader);
        if (body != null) {
            request.setEntity(new ByteArrayEntity(body.clone(), null));
        }
        if (!admission.tryAcquire()) {
            throw new RejectedExecutionException("HTTP execution capacity is exhausted");
        }
        CallState state = new CallState(uri, totalTimeout.toNanos(), admission, onTransportStart);
        FutureTask<T> future = new FutureTask<>(() -> {
            state.executionStarted.set(true);
            try {
                return send(request, state, handler);
            } finally {
                state.releasePermit();
            }
        }) {
            @Override
            protected void done() {
                // A cancellation before the callable starts has no callable finally block.
                if (!state.executionStarted.get()) {
                    state.releasePermit();
                }
            }
        };
        try {
            executor.execute(future);
        } catch (RuntimeException rejected) {
            state.releasePermit();
            throw rejected;
        }
        try {
            long remaining = state.remainingNanos();
            if (remaining <= 0) {
                throw new TimeoutException("HTTP request exceeded its total time budget");
            }
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            cancel(request, state, future);
            throw new TimeoutException("HTTP request exceeded its total time budget");
        } catch (InterruptedException interrupted) {
            cancel(request, state, future);
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (CancellationException cancelled) {
            throw new IOException("HTTP request was cancelled", cancelled);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (state.remainingNanos() <= 0) {
                throw new TimeoutException("HTTP request exceeded its total time budget");
            }
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException("HTTP transport failed", cause);
        }
    }

    private <T> T send(HttpUriRequestBase request, CallState state, ResponseHandler<T> handler) throws IOException {
        callState.set(state);
        ClassicHttpResponse response = null;
        try {
            state.checkActive();
            state.onTransportStart.accept(Instant.now());
            response = client.executeOpen(null, request, null);
            state.checkActive();
            Map<String, List<String>> mutable = new LinkedHashMap<>();
            int headerBytes = 0;
            if (response.getHeaders().length > 100) throw new ResponseHeadersTooLargeException();
            for (Header header : response.getHeaders()) {
                // HttpCore first bounds parsing by 16 KiB/line and 100 fields. This is
                // the additional aggregate limit on decoded field bytes, not the parser's peak memory.
                headerBytes += header.getName().length() + header.getValue().length() + 4;
                if (headerBytes > MAX_RESPONSE_HEADER_BYTES) throw new ResponseHeadersTooLargeException();
                mutable.computeIfAbsent(header.getName().toLowerCase(Locale.ROOT), key -> new ArrayList<>())
                        .add(header.getValue());
            }
            Map<String, List<String>> immutable = new LinkedHashMap<>();
            mutable.forEach((key, values) -> immutable.put(key, List.copyOf(values)));
            return handler.handle(new Response(response.getCode(), Map.copyOf(immutable)), response, state);
        } finally {
            // Abort before close: closing an entity stream alone may drain an unbounded response body.
            // This foundation trades keep-alive reuse for an unambiguous bounded close path.
            request.cancel();
            if (response != null) {
                try {
                    response.close();
                } catch (IOException ignored) {
                    // An acknowledged HTTP response is not changed by deliberate body cancellation.
                }
            }
            callState.remove();
        }
    }

    private static byte[] readBody(ClassicHttpResponse response, int maximumBytes, CallState state) throws IOException {
        if (response.getEntity() == null) return new byte[0];
        if (response.getEntity().getContentLength() > maximumBytes) throw new ResponseTooLargeException();
        // The request is aborted in send's finally before the entity is closed; never drain an excess body.
        InputStream input = response.getEntity().getContent();
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximumBytes, 8192));
        byte[] chunk = new byte[4096];
        while (true) {
            state.checkActive();
            int count = input.read(chunk, 0, Math.min(chunk.length, maximumBytes - output.size() + 1));
            if (count < 0) break;
            if (count > maximumBytes - output.size()) throw new ResponseTooLargeException();
            output.write(chunk, 0, count);
        }
        state.checkActive();
        return output.toByteArray();
    }

    private static void cancel(HttpUriRequestBase request, CallState state, Future<?> future) {
        state.cancelled.set(true);
        request.cancel();
        future.cancel(true);
    }

    @Override
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow().forEach(queued -> {
                if (queued instanceof Future<?> future) {
                    future.cancel(true);
                }
            });
            client.close();
        }
    }

    private static final class CallState {
        private final URI target;
        private final Consumer<Instant> onTransportStart;
        private final long startedNanos = System.nanoTime();
        private final long budgetNanos;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean executionStarted = new AtomicBoolean();
        private final AtomicBoolean permitReleased = new AtomicBoolean();
        private final Semaphore admission;

        private CallState(URI target, long budgetNanos, Semaphore admission, Consumer<Instant> onTransportStart) {
            this.target = target;
            this.onTransportStart = onTransportStart;
            this.budgetNanos = budgetNanos;
            this.admission = admission;
        }

        void releasePermit() {
            if (permitReleased.compareAndSet(false, true)) {
                admission.release();
            }
        }

        long remainingNanos() {
            return budgetNanos - (System.nanoTime() - startedNanos);
        }

        void checkActive() throws IOException {
            if (cancelled.get() || Thread.currentThread().isInterrupted() || remainingNanos() <= 0) {
                throw new IOException("HTTP request is cancelled or expired");
            }
        }
    }

    private final class DeadlineDnsResolver implements DnsResolver {
        private final DnsResolver delegate;

        private DeadlineDnsResolver(DnsResolver delegate) {
            this.delegate = delegate;
        }

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            checkDeadline();
            CallState state = callState.get();
            InetAddress[] addresses = new ValidatedDnsResolver(delegate,
                    address -> allowedTargetAddress.test(state.target, address)).resolve(host);
            // Blocking system DNS may ignore interruption. Never connect after it finally returns.
            checkDeadline();
            return addresses;
        }

        @Override
        public String resolveCanonicalHostname(String host) throws UnknownHostException {
            checkDeadline();
            return host;
        }

        private void checkDeadline() throws UnknownHostException {
            CallState state = callState.get();
            if (state == null) {
                throw new UnknownHostException("DNS resolution outside a bounded request is forbidden");
            }
            try {
                state.checkActive();
            } catch (IOException expired) {
                throw new UnknownHostException("DNS resolution exceeded request lifetime");
            }
        }
    }
}
