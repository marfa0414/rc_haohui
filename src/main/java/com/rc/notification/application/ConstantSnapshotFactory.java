package com.rc.notification.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rc.notification.api.ApiException;
import com.rc.notification.mapping.MappingFailure;
import com.rc.notification.mapping.RequestMapper;
import com.rc.notification.security.OutboundPolicy;
import org.springframework.stereotype.Component;

/** Retains the original bean name; CP03 now supports the complete finite mapping grammar. */
@Component
public class ConstantSnapshotFactory implements RequestSnapshotFactory {
    private final RequestMapper mapping;
    private final OutboundPolicy outbound;

    public ConstantSnapshotFactory(ObjectMapper mapper, OutboundPolicy outbound) {
        mapping = new RequestMapper(mapper);
        this.outbound = outbound;
    }

    @Override public void validate(JsonNode template) {
        try {
            mapping.validate(template);
            outbound.validateConfigurationUrl(template.path("url").textValue());
        } catch (MappingFailure invalid) {
            throw new ApiException(400, "INVALID_CONFIGURATION", invalid.getMessage());
        }
    }

    @Override public RequestSnapshot create(JsonNode template, Submission submission) {
        try {
            validate(template);
        } catch (ApiException invalidConfiguration) {
            throw ApiException.routeUnavailable();
        }
        try {
            return mapping.render(template, submission);
        } catch (MappingFailure invalid) {
            if (invalid.kind() == MappingFailure.Kind.INPUT) throw ApiException.invalid();
            throw ApiException.routeUnavailable();
        }
    }
}
