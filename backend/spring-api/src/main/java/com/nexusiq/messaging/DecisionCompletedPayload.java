package com.nexusiq.messaging;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Payload for {@code decision.completed}, mirrors ai-service's
 * DecisionCompletedPayload. Carries facts, not the raw evidence block or
 * prompt bodies (.claude/rules/architecture.md). */
public record DecisionCompletedPayload(
        UUID decisionId,
        String workflowVersion,
        String promptVersion,
        String llmModel,
        String embeddingModel,
        String recommendation,
        String reasoningSummary,
        BigDecimal confidence,
        String riskLevel,
        // Phase 7 (ADR-006): the deterministic gate (ApprovalGate) reads these
        // two directly — null-safe (the `unsupported`-classification path never
        // runs the validator, so both are legitimately absent there).
        BigDecimal evidenceCoverage,
        Boolean validationPassed,
        Boolean validationEscalated,
        List<String> requiredActions,
        List<String> conditions,
        List<String> unresolvedQuestions,
        List<UUID> keyEvidenceChunkIds,
        List<EvidencePayload> evidence,
        List<FindingPayload> findings,
        List<String> escalationReasons,
        int totalInputTokens,
        int totalOutputTokens,
        BigDecimal estimatedCostUsd,
        int latencyMs) {}
