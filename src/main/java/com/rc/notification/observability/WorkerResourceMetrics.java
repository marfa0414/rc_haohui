package com.rc.notification.observability;

import com.rc.notification.auth.AuthProperties;
import com.rc.notification.auth.TokenCache;
import com.rc.notification.runtime.RoleConfiguration;
import com.rc.notification.worker.WorkerProperties;
import com.rc.notification.worker.WorkerRuntime;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/** Aggregate process resources; provider names and authentication keys are deliberately absent from labels. */
@Component
@Conditional(RoleConfiguration.WorkerEnabled.class)
public final class WorkerResourceMetrics {
    public WorkerResourceMetrics(WorkerRuntime worker, WorkerProperties workers, TokenCache tokens,
                                 AuthProperties oauth, MeterRegistry registry) {
        Gauge.builder("notification.worker.queued", worker, WorkerRuntime::queuedCount).register(registry);
        Gauge.builder("notification.worker.capacity", workers, WorkerProperties::globalConcurrency).register(registry);
        Gauge.builder("notification.worker.provider.active.max", worker, WorkerRuntime::maxActiveForAnyProvider).register(registry);
        Gauge.builder("notification.worker.provider.entries", worker, WorkerRuntime::providerEntryCount).register(registry);
        Gauge.builder("notification.worker.provider.capacity", workers, WorkerProperties::providerConcurrency).register(registry);
        Gauge.builder("notification.oauth.cache.entries", tokens, TokenCache::size).register(registry);
        Gauge.builder("notification.oauth.inflight", tokens, TokenCache::inFlightCount).register(registry);
        Gauge.builder("notification.oauth.leaders.active", tokens, TokenCache::activeLeaderCount).register(registry);
        Gauge.builder("notification.oauth.leaders.capacity", oauth, AuthProperties::httpConcurrency).register(registry);
    }
}
