package com.rc.notification.runtime;

public enum AppMode {
    API, WORKER, ALL;

    public boolean apiEnabled() {
        return this == API || this == ALL;
    }

    public boolean workerEnabled() {
        return this == WORKER || this == ALL;
    }
}
