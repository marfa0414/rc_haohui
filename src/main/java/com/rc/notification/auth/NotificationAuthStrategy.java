package com.rc.notification.auth;

import java.time.Duration;

public interface NotificationAuthStrategy {
    String type();
    default AuthCooldown cooldown(AuthContext context) { return AuthCooldown.none(); }
    PreparedAuthentication prepare(AuthContext context, Duration remainingBudget, Duration plannedBusinessTime, byte[] rejectedDigest)
            throws TokenFailure, InterruptedException;
}
