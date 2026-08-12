package com.nexusiq.decision.dto;

import com.nexusiq.decision.entity.DecisionRunStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record DecisionRunResponse(
        UUID id,
        String workflowVersion,
        String promptVersion,
        String llmModel,
        String embeddingModel,
        DecisionRunStatus status,
        BigDecimal confidence,
        int totalInputTokens,
        int totalOutputTokens,
        BigDecimal estimatedCostUsd,
        Integer latencyMs,
        String failureReason,
        Instant startedAt,
        Instant completedAt) {}
