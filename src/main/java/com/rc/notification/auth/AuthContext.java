package com.rc.notification.auth;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public record AuthContext(String environment, String tenantId, UUID profileId, JsonNode profile) {
    @Override public String toString() { return "AuthContext[profileId=" + profileId + ",configuration=REDACTED]"; }
}
