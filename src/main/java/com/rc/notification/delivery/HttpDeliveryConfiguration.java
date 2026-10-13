package com.rc.notification.delivery;

import com.rc.notification.runtime.RoleConfiguration;
import com.rc.notification.security.OutboundPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@Conditional(RoleConfiguration.WorkerEnabled.class)
public class HttpDeliveryConfiguration {
    @Bean(destroyMethod = "close")
    @Primary
    ControlledHttpClient businessHttpClient(OutboundPolicy policy, Environment environment, MeterRegistry metrics) {
        int concurrency = environment.getProperty("app.worker.global-concurrency", Integer.class, 10);
        var client = new ControlledHttpClient(SystemDefaultDnsResolver.INSTANCE, concurrency, policy::allowsConnectionAddress);
        client.bindMetrics(metrics, "business");
        return client;
    }

    @Bean(destroyMethod = "close")
    ControlledHttpClient tokenHttpClient(OutboundPolicy policy, Environment environment, MeterRegistry metrics) {
        int concurrency = environment.getProperty("app.oauth.http-concurrency", Integer.class, 10);
        if (concurrency < 1 || concurrency > 1000) throw new IllegalArgumentException("Invalid token HTTP concurrency");
        var client = new ControlledHttpClient(SystemDefaultDnsResolver.INSTANCE, concurrency, policy::allowsConnectionAddress);
        client.bindMetrics(metrics, "token");
        return client;
    }
}
