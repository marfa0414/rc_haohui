package com.rc.notification.health;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final ReadinessProbe readiness;

    public HealthController(ReadinessProbe readiness) {
        this.readiness = readiness;
    }

    public record HealthResponse(String status) { }

    @GetMapping("/health/live")
    public HealthResponse live() {
        return new HealthResponse("UP");
    }

    @GetMapping("/health/ready")
    public ResponseEntity<HealthResponse> ready() {
        boolean ready = readiness.isReady();
        return ResponseEntity.status(ready ? 200 : 503)
                .body(new HealthResponse(ready ? "UP" : "DOWN"));
    }
}
