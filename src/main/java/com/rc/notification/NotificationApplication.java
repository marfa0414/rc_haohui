package com.rc.notification;

import com.rc.notification.runtime.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class NotificationApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }
}
