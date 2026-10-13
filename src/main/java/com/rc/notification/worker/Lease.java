package com.rc.notification.worker;

import com.rc.notification.application.RequestSnapshot;
import java.time.Instant;
import java.util.UUID;

public record Lease(UUID taskId, int runNumber, UUID leaseToken, String leaseOwner, Instant leaseUntil,
                    String tenantId, String operation, String eventId, String providerId, String endpointGroup,
                    UUID authProfileId, RequestSnapshot snapshot) { }
