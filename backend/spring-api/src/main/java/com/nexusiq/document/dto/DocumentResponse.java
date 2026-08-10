package com.nexusiq.document.dto;

import com.nexusiq.document.entity.DocumentStatus;
import com.nexusiq.document.entity.DocumentType;
import java.time.Instant;
import java.util.UUID;

public record DocumentResponse(
        UUID id,
        UUID workspaceId,
        String name,
        DocumentType documentType,
        int version,
        boolean isCurrent,
        DocumentStatus status,
        String failureReason,
        int chunkCount,
        UUID uploadedBy,
        Instant createdAt,
        Instant updatedAt) {}
