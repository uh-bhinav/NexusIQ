package com.nexusiq.decision.dto;

import com.nexusiq.decision.entity.AgentExecutionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AgentExecutionResponse(
        UUID id,
        String agentName,
        int sequenceIndex,
        AgentExecutionStatus status,
        String model,
        int inputTokens,
        int outputTokens,
        int latencyMs,
        BigDecimal estimatedCostUsd,
        String error,
        Instant startedAt,
        Instant completedAt) {}
