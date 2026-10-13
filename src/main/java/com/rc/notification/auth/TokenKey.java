package com.rc.notification.auth;

import java.util.List;
import java.util.UUID;

public record TokenKey(String environment, String tenantId, UUID authProfileVersionId, String credentialVersion,
                       String tokenUrl, String clientId, String clientAuthMethod, List<String> normalizedScopes) {
    public TokenKey { normalizedScopes = normalizedScopes.stream().distinct().sorted().toList(); }
    @Override public String toString() { return "TokenKey[authProfileVersionId=" + authProfileVersionId + ",authorizationParameters=REDACTED]"; }
}
