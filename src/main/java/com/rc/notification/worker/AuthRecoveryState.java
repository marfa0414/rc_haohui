package com.rc.notification.worker;

public record AuthRecoveryState(boolean used, boolean required, byte[] rejectedTokenDigest) {
    public AuthRecoveryState { rejectedTokenDigest = rejectedTokenDigest == null ? null : rejectedTokenDigest.clone(); }
    @Override public byte[] rejectedTokenDigest() { return rejectedTokenDigest == null ? null : rejectedTokenDigest.clone(); }
    @Override public String toString() { return "AuthRecoveryState[used=" + used + ",required=" + required + ",digest=REDACTED]"; }
}
