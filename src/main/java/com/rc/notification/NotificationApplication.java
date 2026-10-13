package com.rc.notification;

import com.rc.notification.runtime.AppProperties;
import com.rc.notification.runtime.MigrationBootstrap;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableConfigurationProperties(AppProperties.class)
public class NotificationApplication {
    public static void main(String[] args) {
        if (args.length == 1 && "--migrate-only".equals(args[0])) {
            try {
                MigrationBootstrap.migrate(System.getenv());
                System.out.println("MIGRATION_COMPLETED");
            } catch (RuntimeException failure) {
                // Do not print JDBC exceptions, connection properties, or credential values.
                System.err.println("MIGRATION_FAILED");
                System.exit(1);
            }
            return;
        }
        SpringApplication.run(NotificationApplication.class, args);
    }
}
