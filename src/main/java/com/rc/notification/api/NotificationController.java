package com.rc.notification.api;

import com.rc.notification.application.NotificationService;
import com.rc.notification.application.SubmissionParser;
import com.rc.notification.application.TaskView;
import com.rc.notification.runtime.RoleConfiguration;
import com.rc.notification.security.CallerPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/notifications")
@Conditional(RoleConfiguration.ApiEnabled.class)
public class NotificationController {
    private static final Logger LOG = LoggerFactory.getLogger(NotificationController.class);
    private final SubmissionParser parser;
    private final NotificationService service;
    public NotificationController(SubmissionParser parser, NotificationService service) {
        this.parser = parser;
        this.service = service;
    }

    @PostMapping(consumes = "application/json")
    public ResponseEntity<NotificationService.Accepted> submit(HttpServletRequest request,
                                                              @AuthenticationPrincipal CallerPrincipal caller) throws IOException {
        long started = System.nanoTime();
        var submission = parser.parse(request.getInputStream());
        var accepted = service.submit(submission, caller);
        // This association is emitted after commit; asynchronous executions join by notificationId.
        LOG.info("notification_accepted requestId={} notificationId={} tenantId={} operation={} runNumber={} phase=ACCEPTED durationMs={} errorCode=NONE deduplicated={}",
                ApiErrors.requestId(request), accepted.notificationId(), submission.tenantId(), submission.operation(), accepted.runNumber(),
                Duration.ofNanos(System.nanoTime() - started).toMillis(), accepted.deduplicated());
        return ResponseEntity.status(accepted.deduplicated() ? 200 : 202).body(accepted);
    }

    @GetMapping("/{id}")
    public TaskView get(@PathVariable UUID id, @AuthenticationPrincipal CallerPrincipal caller) {
        return service.get(id, caller);
    }

    @GetMapping
    public NotificationService.Page list(@RequestParam String tenantId, @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String cursor,
                                          @RequestParam(defaultValue = "20") int limit,
                                          @AuthenticationPrincipal CallerPrincipal caller) {
        return service.list(tenantId, status, cursor, limit, caller);
    }

    @GetMapping("/{id}/attempts")
    public NotificationService.AttemptPage attempts(@PathVariable UUID id, @RequestParam(required = false) String cursor,
                                                   @RequestParam(defaultValue = "20") int limit,
                                                   @AuthenticationPrincipal CallerPrincipal caller) {
        return service.attempts(id, cursor, limit, caller);
    }
}
