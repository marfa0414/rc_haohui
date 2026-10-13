package com.rc.notification.worker;

import java.time.Instant;
import java.util.UUID;

public record AttemptView(UUID taskId, int runNumber, int attemptNo, String phase, String outcome,
                          Integer httpStatus, String errorCode, Instant startedAt, Instant endedAt,
                          Instant requestStartedAt, Instant httpSendStartedAt, Instant executionDeadline,
                          String credentialVersion) { }
