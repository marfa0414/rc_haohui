package com.rc.notification.delivery;

import com.rc.notification.api.ApiException;
import com.rc.notification.application.RequestSnapshot;
import com.rc.notification.application.SubmissionParser;
import com.rc.notification.auth.ApiKeyAuthStrategy;
import com.rc.notification.runtime.RoleConfiguration;
import com.rc.notification.security.OutboundPolicy;
import java.io.IOException;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/** Exactly one business request, using the accepted immutable bytes and fresh prepared authentication. */
@Component
@Conditional(RoleConfiguration.WorkerEnabled.class)
public class BusinessHttpSender {
    private static final Duration MAX_BUSINESS_TIME = Duration.ofSeconds(10);
    private static final Set<String> TRANSPORT_HEADERS = Set.of("host", "content-length", "connection", "transfer-encoding",
            "content-type", "proxy-authorization", "proxy-connection", "keep-alive", "te", "trailer", "upgrade", "expect");
    private final ControlledHttpClient client;
    private final OutboundPolicy outbound;

    public BusinessHttpSender(ControlledHttpClient client, OutboundPolicy outbound) {
        this.client = client;
        this.outbound = outbound;
    }

    public record SendResult(int statusCode, Map<String, List<String>> headers, Instant httpSendStartedAt) {
        public SendResult {
            Map<String, List<String>> copy = new LinkedHashMap<>();
            headers.forEach((name, values) -> copy.put(name.toLowerCase(Locale.ROOT), List.copyOf(values)));
            headers = Map.copyOf(copy);
        }
        public boolean acknowledged() { return statusCode >= 200 && statusCode < 300; }
        @Override public String toString() { return "SendResult[statusCode=" + statusCode + ",headers=REDACTED,httpSendStartedAt=" + httpSendStartedAt + "]"; }
    }

    public enum FailureKind { TIMEOUT, NETWORK, TARGET_REJECTED, CAPACITY, CANCELLED }

    /** No raw URL, credential, response body or third-party exception message is retained. */
    public static final class SendFailure extends Exception {
        private final FailureKind kind;
        private final Instant httpSendStartedAt;
        private SendFailure(FailureKind kind, Instant httpSendStartedAt) {
            super("Business HTTP failed: " + kind);
            this.kind = kind;
            this.httpSendStartedAt = httpSendStartedAt;
        }
        public FailureKind kind() { return kind; }
        public Instant httpSendStartedAt() { return httpSendStartedAt; }
    }

    public SendResult send(RequestSnapshot snapshot, ApiKeyAuthStrategy.Prepared authentication, Duration remainingBudget)
            throws SendFailure {
        if (remainingBudget == null || remainingBudget.isNegative() || remainingBudget.isZero()) throw new SendFailure(FailureKind.TIMEOUT, null);
        if (Thread.currentThread().isInterrupted()) throw new SendFailure(FailureKind.CANCELLED, null);
        long preparationStarted = System.nanoTime();
        Duration allowed = remainingBudget.compareTo(MAX_BUSINESS_TIME) < 0 ? remainingBudget : MAX_BUSINESS_TIME;
        URI target;
        Map<String, String> headers = new LinkedHashMap<>();
        byte[] body;
        try {
            outbound.validateConfigurationUrl(snapshot.url());
            target = URI.create(snapshot.url());
            if (!Set.of("POST", "PUT", "PATCH").contains(snapshot.method())
                    || !Set.of("application/json", "application/x-www-form-urlencoded").contains(snapshot.contentType())) {
                throw new IllegalArgumentException("Invalid accepted request metadata");
            }
            long ordinaryBytes = 0;
            for (var entry : snapshot.headers().entrySet()) {
                addHeader(headers, entry.getKey(), entry.getValue(), false);
                ordinaryBytes += (long) entry.getKey().getBytes(StandardCharsets.UTF_8).length
                        + entry.getValue().getBytes(StandardCharsets.UTF_8).length + 4;
                if (ordinaryBytes > 16384) throw new IllegalArgumentException("Ordinary header limit exceeded");
            }
            for (var entry : authentication.headers().entrySet()) addHeader(headers, entry.getKey(), entry.getValue(), true);
            headers.put("Content-Type", snapshot.contentType());
            body = snapshot.body();
            if (body.length > SubmissionParser.MAX_BYTES) throw new IllegalArgumentException("Body limit exceeded");
        } catch (ApiException | IllegalArgumentException rejected) {
            throw new SendFailure(FailureKind.TARGET_REJECTED, null);
        }
        long remainingNanos = allowed.toNanos() - (System.nanoTime() - preparationStarted);
        if (remainingNanos <= 0) throw new SendFailure(FailureKind.TIMEOUT, null);
        AtomicReference<Instant> startedAt = new AtomicReference<>();
        try {
            var result = client.execute(target, snapshot.method(), headers, body, Duration.ofNanos(remainingNanos), startedAt::set);
            return new SendResult(result.statusCode(), result.headers(), startedAt.get());
        } catch (TimeoutException | SocketTimeoutException | ConnectionRequestTimeoutException timeout) {
            throw new SendFailure(FailureKind.TIMEOUT, startedAt.get());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SendFailure(FailureKind.CANCELLED, startedAt.get());
        } catch (RejectedExecutionException capacity) {
            throw new SendFailure(FailureKind.CAPACITY, startedAt.get());
        } catch (TargetAddressRejectedException rejected) {
            throw new SendFailure(FailureKind.TARGET_REJECTED, startedAt.get());
        } catch (IOException network) {
            throw new SendFailure(FailureKind.NETWORK, startedAt.get());
        }
    }

    private static void addHeader(Map<String, String> headers, String name, String value, boolean authentication) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || TRANSPORT_HEADERS.contains(lower)
                || !authentication && lower.equals("authorization")) throw new IllegalArgumentException("Header is controlled by the transport or authentication strategy");
        for (String existing : headers.keySet()) if (name.equalsIgnoreCase(existing)) throw new IllegalArgumentException("Header collision");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 && character != '\t' || character == 0x7f) throw new IllegalArgumentException("Header contains a forbidden character");
        }
        if ((long) name.getBytes(StandardCharsets.UTF_8).length + value.getBytes(StandardCharsets.UTF_8).length + 4 > 16384) {
            throw new IllegalArgumentException("Header limit exceeded");
        }
        headers.put(name, value);
    }
}
