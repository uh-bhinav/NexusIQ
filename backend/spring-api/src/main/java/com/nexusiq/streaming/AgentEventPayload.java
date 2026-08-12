package com.nexusiq.streaming;

import java.math.BigDecimal;

/** {@code agent.completed} / {@code agent.failed} SSE event body
 * (docs/API/API_DESIGN.md "SSE"). */
public record AgentEventPayload(
        String node,
        String status,
        int sequenceIndex,
        String model,
        int inputTokens,
        int outputTokens,
        int latencyMs,
        BigDecimal estimatedCostUsd,
        String error) {}
