package com.nexusiq.audit.dto;

import java.time.Instant;
import java.util.UUID;

public record AuditEventResponse(
        UUID id,
        UUID workspaceId,
        UUID actorId,
        String eventType,
        String resourceType,
        UUID resourceId,
        UUID correlationId,
        String metadata,
        Instant occurredAt) {}
