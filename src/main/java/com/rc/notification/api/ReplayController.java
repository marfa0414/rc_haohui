package com.rc.notification.api;

import com.rc.notification.application.ReplayService;
import com.rc.notification.application.SubmissionParser;
import com.rc.notification.runtime.RoleConfiguration;
import com.rc.notification.security.CallerPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.context.annotation.Conditional;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/notifications")
@Conditional(RoleConfiguration.ApiEnabled.class)
public class ReplayController {
    private final ReplayService service;
    private final SubmissionParser parser;
    public ReplayController(ReplayService service, SubmissionParser parser) { this.service = service; this.parser = parser; }
    @PostMapping(value = "/{id}/replay", consumes = "application/json")
    public ReplayService.Response replay(@PathVariable UUID id, HttpServletRequest request, @AuthenticationPrincipal CallerPrincipal caller) throws IOException {
        return service.replay(id, parser.readObject(request.getInputStream()), caller);
    }
}
