package com.rc.notification.application;

import com.fasterxml.jackson.databind.JsonNode;

public interface RequestSnapshotFactory {
    RequestSnapshot create(JsonNode template, Submission submission);
}
