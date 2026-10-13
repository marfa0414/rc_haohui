package com.rc.notification.runtime;

import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** CP01 role markers. No business endpoint or scheduler exists at this checkpoint. */
@Configuration(proxyBeanMethods = false)
public class RoleConfiguration {
    public record ApiRole() { }
    public record WorkerRole() { }

    @Bean
    @Conditional(ApiEnabled.class)
    ApiRole apiRole() {
        return new ApiRole();
    }

    @Bean
    @Conditional(WorkerEnabled.class)
    WorkerRole workerRole() {
        return new WorkerRole();
    }

    private static AppMode mode(ConditionContext context) {
        String value = context.getEnvironment().getProperty("app.mode", "all");
        return AppMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    public static class ApiEnabled implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return mode(context).apiEnabled();
        }
    }

    public static class WorkerEnabled implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return mode(context).workerEnabled();
        }
    }
}
