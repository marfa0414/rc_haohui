package com.rc.notification.observability;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ObservabilityProperties.class)
public class ObservabilityConfiguration { }
