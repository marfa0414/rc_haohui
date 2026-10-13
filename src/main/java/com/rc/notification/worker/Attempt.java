package com.rc.notification.worker;

import java.time.Instant;
import java.util.UUID;

public record Attempt(UUID taskId, int runNumber, int attemptNo, UUID leaseToken,
                      Instant startedAt, Instant executionDeadline) { }
