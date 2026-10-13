package com.rc.notification.configuration;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rc.notification.application.SubmissionParser;
import com.rc.notification.runtime.RoleConfiguration;
import com.rc.notification.security.CallerPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
@Conditional(RoleConfiguration.ApiEnabled.class)
public class ConfigurationController {
    private final ConfigurationService service;
    private final SubmissionParser parser;
    public ConfigurationController(ConfigurationService service, SubmissionParser parser) { this.service = service; this.parser = parser; }

    @PostMapping(value = "/template-versions", consumes = "application/json")
    public ResponseEntity<ObjectNode> createTemplate(HttpServletRequest request, @AuthenticationPrincipal CallerPrincipal caller) throws IOException {
        return ResponseEntity.status(201).body(service.createTemplate(parser.readObject(request.getInputStream()), caller));
    }
    @PutMapping(value = "/template-versions/{id}", consumes = "application/json")
    public ObjectNode updateTemplate(@PathVariable UUID id, HttpServletRequest request, @AuthenticationPrincipal CallerPrincipal caller) throws IOException {
        return service.updateTemplate(id, parser.readObject(request.getInputStream()), caller);
    }
    @GetMapping("/template-versions/{id}")
    public ObjectNode template(@PathVariable UUID id, @AuthenticationPrincipal CallerPrincipal caller) { return service.template(id, caller); }
    @GetMapping("/template-versions")
    public ConfigurationService.Page templates(@RequestParam(required = false) String tenantId, @RequestParam(required = false) String cursor,
                                               @RequestParam(defaultValue = "20") int limit, @AuthenticationPrincipal CallerPrincipal caller) {
        return service.list(true, tenantId, cursor, limit, caller);
    }
    @PostMapping("/template-versions/{id}/validate")
    public ObjectNode validateTemplate(@PathVariable UUID id, @AuthenticationPrincipal CallerPrincipal caller) { return service.validateTemplate(id, caller); }
    @PostMapping(value = "/template-versions/{id}/preview", consumes = "application/json")
    public ObjectNode preview(@PathVariable UUID id, HttpServletRequest request, @AuthenticationPrincipal CallerPrincipal caller) throws IOException {
        return service.preview(id, parser.readObject(request.getInputStream()), caller);
    }
    @PostMapping(value = "/template-versions/{id}/publish", consumes = "application/json")
    public ObjectNode publishTemplate(@PathVariable UUID id, HttpServletRequest request, @AuthenticationPrincipal CallerPrincipal caller) throws IOException {
        return service.publishTemplate(id, parser.readObject(request.getInputStream()), caller);
    }

    @PostMapping(value = "/auth-profiles", consumes = "application/json")
    public ResponseEntity<ObjectNode> createAuth(HttpServletRequest request, @AuthenticationPrincipal CallerPrincipal caller) throws IOException {
        return ResponseEntity.status(201).body(service.createAuth(parser.readObject(request.getInputStream()), caller));
    }
    @PutMapping(value = "/auth-profiles/{id}", consumes = "application/json")
    public ObjectNode updateAuth(@PathVariable UUID id, HttpServletRequest request, @AuthenticationPrincipal CallerPrincipal caller) throws IOException {
        return service.updateAuth(id, parser.readObject(request.getInputStream()), caller);
    }
    @GetMapping("/auth-profiles/{id}")
    public ObjectNode auth(@PathVariable UUID id, @AuthenticationPrincipal CallerPrincipal caller) { return service.auth(id, caller); }
    @GetMapping("/auth-profiles")
    public ConfigurationService.Page auths(@RequestParam(required = false) String tenantId, @RequestParam(required = false) String cursor,
                                          @RequestParam(defaultValue = "20") int limit, @AuthenticationPrincipal CallerPrincipal caller) {
        return service.list(false, tenantId, cursor, limit, caller);
    }
    @PostMapping(value = "/auth-profiles/{id}/publish", consumes = "application/json")
    public ObjectNode publishAuth(@PathVariable UUID id, HttpServletRequest request, @AuthenticationPrincipal CallerPrincipal caller) throws IOException {
        return service.publishAuth(id, parser.readObject(request.getInputStream()), caller);
    }

    @PutMapping(value = "/routes/{tenantId}/{operation}", consumes = "application/json")
    public ObjectNode switchRoute(@PathVariable String tenantId, @PathVariable String operation, HttpServletRequest request,
                                  @AuthenticationPrincipal CallerPrincipal caller) throws IOException {
        return service.switchRoute(tenantId, operation, parser.readObject(request.getInputStream()), caller);
    }
    @GetMapping("/routes/{tenantId}/{operation}")
    public ObjectNode route(@PathVariable String tenantId, @PathVariable String operation, @AuthenticationPrincipal CallerPrincipal caller) {
        return service.route(tenantId, operation, caller);
    }
    @GetMapping("/routes")
    public ConfigurationService.Page routes(@RequestParam(required = false) String tenantId, @RequestParam(required = false) String cursor,
                                           @RequestParam(defaultValue = "20") int limit, @AuthenticationPrincipal CallerPrincipal caller) {
        return service.routes(tenantId, cursor, limit, caller);
    }
}
