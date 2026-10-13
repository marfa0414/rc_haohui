package com.rc.notification.application;

import com.fasterxml.jackson.databind.JsonNode;

public interface RequestSnapshotFactory {
    /** Offline validation only: no DNS, HTTP or secret lookup. */
    void validate(JsonNode template);

    RequestSnapshot create(JsonNode template, Submission submission);
}
