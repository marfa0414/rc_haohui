package com.rc.notification.worker;

import com.rc.notification.auth.AuthContext;
import com.rc.notification.auth.AuthStrategyRegistry;
import com.rc.notification.auth.BearerChallenge;
import com.rc.notification.auth.PreparedAuthentication;
import com.rc.notification.auth.TokenFailure;
import com.rc.notification.configuration.ConfigurationRepository;
import com.rc.notification.delivery.BusinessHttpSender;
import com.rc.notification.retry.RetryPolicy;
import com.rc.notification.runtime.RoleConfiguration;
import com.rc.notification.storage.DeliveryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/** One durable attempt per invocation. There is deliberately no network retry loop here. */
@Component
@Conditional(RoleConfiguration.WorkerEnabled.class)
public class WorkerEngine {
    private static final Logger LOG = LoggerFactory.getLogger(WorkerEngine.class);
    private final DeliveryRepository repository;
    private final ConfigurationRepository configurations;
    private final AuthStrategyRegistry authenticationStrategies;
    private final BusinessHttpSender sender;
    private final WorkerProperties properties;
    private final List<WorkerLifecycleListener> listeners;
    private final MeterRegistry metrics;
    private final RetryPolicy retryPolicy;
    private final String environment;

    public WorkerEngine(DeliveryRepository repository, ConfigurationRepository configurations, AuthStrategyRegistry authenticationStrategies,
                        BusinessHttpSender sender, WorkerProperties properties,
                        ObjectProvider<WorkerLifecycleListener> listeners, MeterRegistry metrics, RetryPolicy retryPolicy,
                        @Value("${app.environment:production}") String environment) {
        this.repository = repository;
        this.configurations = configurations;
        this.authenticationStrategies = authenticationStrategies;
        this.sender = sender;
        this.properties = properties;
        this.listeners = listeners.orderedStream().toList();
        this.metrics = metrics;
        this.retryPolicy = retryPolicy;
        this.environment = environment;
    }

    public void process(Lease lease) {
        Attempt attempt = null;
        try {
            phase(WorkerLifecycleListener.Phase.AFTER_CLAIM, lease, null);
            var profile = configurations.auth(lease.authProfileId(), false);
            var context = new AuthContext(environment, lease.tenantId(), lease.authProfileId(), profile);
            var cooldown = authenticationStrategies.cooldown(context);
            if (!cooldown.remaining().isZero() && !cooldown.remaining().isNegative()) {
                repository.defer(lease, cooldown.remaining(), cooldown.retryAfterValues(), "AUTH_COOLDOWN");
                return;
            }
            long budgetStarted = System.nanoTime();
            attempt = repository.reserve(lease, properties.attemptTimeout()).orElse(null);
            if (attempt == null) return;
            phase(WorkerLifecycleListener.Phase.AFTER_RESERVE, lease, attempt);
            AuthRecoveryState recovery = repository.authRecovery(lease).orElse(null);
            if (recovery == null) return;
            PreparedAuthentication authentication;
            try {
                authentication = authenticationStrategies.prepare(context, attemptRemaining(lease, attempt, budgetStarted),
                        properties.businessTimeout(), recovery.rejectedTokenDigest());
            } catch (TokenFailure failure) {
                if (Thread.currentThread().isInterrupted() || failure.code().equals("TOKEN_CANCELLED")) return;
                Duration authDelay = failure.retryable() ? authenticationStrategies.cooldown(context).remaining() : Duration.ZERO;
                finish(lease, attempt, new Completion("NOT_SENT", null, failure.code(), null, failure.retryable(), failure.retryAfterValues(),
                        false, null, authDelay));
                return;
            }
            Duration beforeIntent = remaining(lease, attempt, budgetStarted);
            if (beforeIntent.isNegative() || beforeIntent.isZero()) return;
            if (!repository.markRequestStarted(lease, attempt, authentication.transport().credentialVersion(), authentication.tokenDigest())) return;
            phase(WorkerLifecycleListener.Phase.AFTER_REQUEST_STARTED, lease, attempt);
            Duration remaining = remaining(lease, attempt, budgetStarted);
            if (remaining.isNegative() || remaining.isZero()) return;
            try {
                authenticationStrategies.ensureValidForSend(authentication, remaining);
            } catch (TokenFailure expired) {
                finish(lease, attempt, new Completion("NOT_SENT", null, expired.code(), null, true, List.of()));
                return;
            }
            Completion completion;
            try {
                var result = sender.send(lease.snapshot(), authentication.transport(), remaining);
                boolean invalidToken = authentication.oauth() && result.statusCode() == 401 && BearerChallenge.invalidToken(result.headers());
                completion = new Completion(result.acknowledged() ? "HTTP_ACKNOWLEDGED" : "HTTP_REJECTED",
                        result.statusCode(), result.acknowledged() ? null : invalidToken ? "AUTH_INVALID_TOKEN" : "HTTP_REJECTED", result.httpSendStartedAt(),
                        invalidToken || retryPolicy.classify(result.statusCode(), null) == RetryPolicy.Outcome.RETRY,
                        result.headers().getOrDefault("retry-after", List.of()), invalidToken, invalidToken ? authentication.tokenDigest() : null);
            } catch (BusinessHttpSender.SendFailure failure) {
                if (failure.kind() == BusinessHttpSender.FailureKind.CANCELLED) {
                    Thread.currentThread().interrupt();
                    return;
                }
                // A local rejection before execute is provably not sent; network uncertainty remains UNKNOWN.
                boolean notSent = failure.kind() == BusinessHttpSender.FailureKind.TARGET_REJECTED
                        || failure.httpSendStartedAt() == null && failure.kind() == BusinessHttpSender.FailureKind.CAPACITY;
                completion = new Completion(notSent ? "NOT_SENT" : "UNKNOWN", null,
                        "HTTP_" + failure.kind().name(), failure.httpSendStartedAt(),
                        retryPolicy.classify(null, failure.kind()) == RetryPolicy.Outcome.RETRY, List.of());
            }
            phase(WorkerLifecycleListener.Phase.AFTER_HTTP, lease, attempt);
            boolean committed = finish(lease, attempt, completion);
            if (committed && completion.authRecovery() && !recovery.used()) {
                phase(WorkerLifecycleListener.Phase.AFTER_COMPLETION, lease, attempt);
                authenticationStrategies.invalidate(authentication);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOG.info("delivery_interrupted taskId={} run={} attempt={}", lease.taskId(), lease.runNumber(), attempt == null ? 0 : attempt.attemptNo());
        } catch (DataAccessException | TransactionException unavailable) {
            // Neither a failed commit nor an unavailable database licenses a later HTTP call.
            metrics.counter("notification.worker.storage_failure").increment();
            LOG.warn("delivery_storage_unavailable taskId={} run={}", lease.taskId(), lease.runNumber());
        } catch (RuntimeException unexpected) {
            metrics.counter("notification.worker.execution_failure").increment();
            LOG.error("delivery_execution_failed taskId={} run={} exceptionType={}", lease.taskId(), lease.runNumber(), unexpected.getClass().getSimpleName());
        }
    }

    private Duration remaining(Lease lease, Attempt attempt, long budgetStarted) {
        Duration allowed = attemptRemaining(lease, attempt, budgetStarted);
        return allowed.compareTo(properties.businessTimeout()) < 0 ? allowed : properties.businessTimeout();
    }

    private Duration attemptRemaining(Lease lease, Attempt attempt, long budgetStarted) {
        // Subtract database timestamps from each other, never a local wall clock from a database clock.
        // The monotonic start precedes T2, so its database wait is conservatively part of both budgets.
        Duration elapsed = Duration.ofNanos(System.nanoTime() - budgetStarted);
        Duration attemptRemaining = Duration.between(attempt.startedAt(), attempt.executionDeadline()).minus(elapsed);
        Duration leaseRemaining = Duration.between(attempt.startedAt(), lease.leaseUntil()).minus(elapsed).minusMillis(20);
        return attemptRemaining.compareTo(leaseRemaining) < 0 ? attemptRemaining : leaseRemaining;
    }

    private void phase(WorkerLifecycleListener.Phase phase, Lease lease, Attempt attempt) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Worker interrupted");
        metrics.counter("notification.worker.phase", "phase", phase.name()).increment();
        LOG.debug("delivery_phase taskId={} run={} attempt={} phase={}", lease.taskId(), lease.runNumber(),
                attempt == null ? 0 : attempt.attemptNo(), phase);
        for (var listener : listeners) listener.onPhase(phase, lease, attempt);
    }

    private boolean finish(Lease lease, Attempt attempt, Completion completion) {
        boolean committed = repository.complete(lease, attempt, completion);
        metrics.counter("notification.worker.completion", "result", committed ? completion.outcome() : "STALE_OWNER").increment();
        LOG.info("delivery_completion taskId={} run={} attempt={} outcome={} committed={}", lease.taskId(), lease.runNumber(),
                attempt.attemptNo(), completion.outcome(), committed);
        return committed;
    }
}
