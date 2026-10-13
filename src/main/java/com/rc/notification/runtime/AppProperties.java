package com.rc.notification.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(AppMode mode) {
    public AppProperties {
        mode = mode == null ? AppMode.ALL : mode;
    }
}
