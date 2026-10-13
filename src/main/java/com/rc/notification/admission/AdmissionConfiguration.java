package com.rc.notification.admission;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdmissionProperties.class)
public class AdmissionConfiguration { }
