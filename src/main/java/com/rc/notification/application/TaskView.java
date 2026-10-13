package com.rc.notification.application;

import java.time.Instant;
import java.util.UUID;

/** Public task projection deliberately omits request bodies, headers, secrets and token fingerprints. */
public record TaskView(UUID notificationId, String tenantId, String operation, String eventId, String status,
                       int runNumber, int runAttemptCount, long totalAttemptCount, Instant nextAttemptAt,
                       Instant deadlineAt, String lastOutcome, String lastErrorCode, Integer lastHttpStatus,
                       UUID templateVersionId, UUID authProfileId, long routeRevision,
                       Instant createdAt, Instant updatedAt) { }
