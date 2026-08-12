package com.nexusiq.approval.dto;

import com.nexusiq.approval.entity.ApprovalStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ApprovalResponse(
        UUID id,
        UUID decisionRunId,
        UUID decisionRequestId,
        String decisionTitle,
        ApprovalStatus status,
        List<String> reasons,
        Instant requestedAt,
        UUID resolvedBy,
        Instant resolvedAt,
        String resolutionNotes) {}
