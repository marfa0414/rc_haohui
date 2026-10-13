package com.rc.notification.delivery;

import java.io.IOException;
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
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.config.TlsConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;

/**
 * One physical request, a bounded executor and a total deadline including actual-address validation.
 * Response bodies are deliberately not consumed: acknowledgement needs the final status/headers only.
 */
public final class ControlledHttpClient implements AutoCloseable {
    private static final Duration MAX_TOTAL_TIMEOUT = Duration.ofMinutes(1);
    private final ThreadLocal<CallState> callState = new ThreadLocal<>();
    private final Semaphore admission;
    private final ThreadPoolExecutor executor;
    private final CloseableHttpClient client;
    private final BiPredicate<URI, InetAddress> allowedTargetAddress;
    private final AtomicBoolean closed = new AtomicBoolean();

    public record Response(int statusCode, Map<String, List<String>> headers) { }

    public ControlledHttpClient(DnsResolver resolver, int maxConcurrent) {
        this(resolver, maxConcurrent, (target, address) -> true);
    }

    public ControlledHttpClient(DnsResolver resolver, int maxConcurrent,
                                BiPredicate<URI, InetAddress> allowedTargetAddress) {
        Objects.requireNonNull(resolver, "resolver");
        this.allowedTargetAddress = Objects.requireNonNull(allowedTargetAddress, "allowedTargetAddress");
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("maxConcurrent must be positive");
        }
        admission = new Semaphore(maxConcurrent);
        AtomicInteger sequence = new AtomicInteger();
        executor = new ThreadPoolExecutor(maxConcurrent, maxConcurrent, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(maxConcurrent), runnable -> {
                    Thread thread = new Thread(runnable, "outbound-http-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());

        var connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setConnectionFactory(ManagedHttpClientConnectionFactory.builder()
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

    public Response execute(URI uri, String method, Map<String, String> headers, byte[] body,
                            Duration totalTimeout)
            throws IOException, TimeoutException, InterruptedException {
        return execute(uri, method, headers, body, totalTimeout, ignored -> { });
    }

    /** The callback runs only when the bounded I/O task enters the real HTTP transport. */
    public Response execute(URI uri, String method, Map<String, String> headers, byte[] body,
                            Duration totalTimeout, Consumer<Instant> onTransportStart)
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
        var request = new HttpUriRequestBase(method, uri);
        headers.forEach(request::setHeader);
        if (body != null) {
            request.setEntity(new ByteArrayEntity(body.clone(), null));
        }
        if (!admission.tryAcquire()) {
            throw new RejectedExecutionException("HTTP execution capacity is exhausted");
        }
        CallState state = new CallState(uri, totalTimeout.toNanos(), admission, onTransportStart);
        FutureTask<Response> future = new FutureTask<>(() -> {
            state.executionStarted.set(true);
            try {
                return send(request, state);
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

    private Response send(HttpUriRequestBase request, CallState state) throws IOException {
        callState.set(state);
        ClassicHttpResponse response = null;
        try {
            state.checkActive();
            state.onTransportStart.accept(Instant.now());
            response = client.executeOpen(null, request, null);
            state.checkActive();
            Map<String, List<String>> mutable = new LinkedHashMap<>();
            for (Header header : response.getHeaders()) {
                mutable.computeIfAbsent(header.getName().toLowerCase(Locale.ROOT), key -> new ArrayList<>())
                        .add(header.getValue());
            }
            Map<String, List<String>> immutable = new LinkedHashMap<>();
            mutable.forEach((key, values) -> immutable.put(key, List.copyOf(values)));
            return new Response(response.getCode(), Map.copyOf(immutable));
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

    private static void cancel(HttpUriRequestBase request, CallState state, Future<Response> future) {
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
