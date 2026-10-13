package com.rc.notification.delivery;

import com.rc.notification.runtime.RoleConfiguration;
import com.rc.notification.security.OutboundPolicy;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@Conditional(RoleConfiguration.WorkerEnabled.class)
public class HttpDeliveryConfiguration {
    @Bean(destroyMethod = "close")
    ControlledHttpClient businessHttpClient(OutboundPolicy policy, Environment environment) {
        int concurrency = environment.getProperty("app.worker.global-concurrency", Integer.class, 10);
        return new ControlledHttpClient(SystemDefaultDnsResolver.INSTANCE, concurrency, policy::allowsConnectionAddress);
    }
}
