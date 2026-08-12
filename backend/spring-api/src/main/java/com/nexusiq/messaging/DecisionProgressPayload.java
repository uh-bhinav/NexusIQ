package com.nexusiq.messaging;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/** Payload for {@code decision.progress} — one per node completion (or
 * failure), mirrors ai-service's DecisionProgressPayload. */
public record DecisionProgressPayload(
        UUID decisionId,
        String agentName,
        int sequenceIndex,
        String status,
        String model,
        int inputTokens,
        int outputTokens,
        int latencyMs,
        BigDecimal estimatedCostUsd,
        Map<String, Object> output,
        String error,
        // Phase 8: the OTel trace id of the span ai-service opened for this
        // node — finally populates agent_executions.trace_id, a column that
        // existed since Phase 5's V7 migration but nothing wrote until now.
        String traceId) {}
