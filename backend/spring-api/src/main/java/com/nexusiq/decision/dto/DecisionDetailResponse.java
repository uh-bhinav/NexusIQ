package com.nexusiq.decision.dto;

import com.nexusiq.decision.entity.DecisionPriority;
import com.nexusiq.decision.entity.DecisionRequestStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** GET /decisions/{id} — the full assembled result (docs/API/API_DESIGN.md). */
public record DecisionDetailResponse(
        UUID id,
        String title,
        String question,
        DecisionPriority priority,
        DecisionRequestStatus status,
        Instant createdAt,
        DecisionRunResponse run,
        List<AgentExecutionResponse> agentExecutions,
        List<EvidenceResponse> evidence,
        List<FindingResponse> findings,
        DecisionOutcomeResponse outcome) {}
