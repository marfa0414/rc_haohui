package com.rc.notification.worker;

/** Optional in-process observation of durable boundaries; it never exposes a remote control endpoint. */
public interface WorkerLifecycleListener {
    enum Phase { AFTER_CLAIM, AFTER_RESERVE, AFTER_REQUEST_STARTED, AFTER_HTTP }
    void onPhase(Phase phase, Lease lease, Attempt attempt) throws InterruptedException;
}
