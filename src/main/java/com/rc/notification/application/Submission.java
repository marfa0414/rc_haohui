package com.rc.notification.application;

import com.fasterxml.jackson.databind.node.ObjectNode;

public record Submission(String tenantId, String operation, String eventId, ObjectNode document, byte[] digest) { }
