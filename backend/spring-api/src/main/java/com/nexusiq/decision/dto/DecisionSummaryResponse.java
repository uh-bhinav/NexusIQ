package com.nexusiq.decision.dto;

import com.nexusiq.decision.entity.DecisionPriority;
import com.nexusiq.decision.entity.DecisionRequestStatus;
import java.time.Instant;
import java.util.UUID;

public record DecisionSummaryResponse(
        UUID id,
        String title,
        String question,
        DecisionPriority priority,
        DecisionRequestStatus status,
        Instant createdAt) {}
