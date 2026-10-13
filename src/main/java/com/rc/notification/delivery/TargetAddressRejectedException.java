package com.rc.notification.delivery;

import java.net.UnknownHostException;

/** Distinguishes a permanent outbound-policy rejection from a transient DNS lookup failure. */
public final class TargetAddressRejectedException extends UnknownHostException {
    public TargetAddressRejectedException() { super("Target address rejected by policy"); }
}
