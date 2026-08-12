package com.nexusiq.approval;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexusiq.messaging.DecisionCompletedPayload;
import com.nexusiq.messaging.FindingPayload;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** ApprovalGate is the deterministic gate (ADR-006, CLAUDE.md non-negotiable
 * #2) — a pure function over structured, validated fields, exactly the
 * "correctness is cheap to establish and expensive to get wrong" case
 * .claude/rules/testing.md calls out for heavy unit testing. Previously only
 * exercised indirectly through ApprovalFlowIT/DecisionEventConsumersIT; this
 * tests each of the seven triggers directly and in isolation, proving each
 * one alone is sufficient (not requiring another to also fire) and that a
 * genuinely clean payload requires none of them. */
class ApprovalGateTest {

    private final ApprovalGate gate =
            new ApprovalGate(new HitlProperties("HIGH", new BigDecimal("0.75"), new BigDecimal("0.60")));

    private DecisionCompletedPayload cleanPayload() {
        return new DecisionCompletedPayload(
                UUID.randomUUID(),
                "v1",
                "v1",
                "gemini-2.5-flash",
                "BAAI/bge-small-en-v1.5",
                "APPROVE",
                "Findings support approval.",
                new BigDecimal("0.90"),
                "LOW",
                new BigDecimal("0.95"),
                true,
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new FindingPayload(
                                "POLICY", "Data Residency Policy", "SATISFIED", null,
                                "Data Residency Policy (SP-102 §1)", "Evidence confirms compliance.",
                                new BigDecimal("0.9"), List.of())),
                List.of(),
                100,
                50,
                new BigDecimal("0.001"),
                500);
    }

    @Test
    void cleanPayload_requiresNoApproval() {
        var result = gate.evaluate(cleanPayload());

        assertThat(result.requiresApproval()).isFalse();
        assertThat(result.reasons()).isEmpty();
    }

    @Test
    void violatedPolicyFinding_requiresApproval_regardlessOfConfidence() {
        var payload = cleanPayload();
        var withViolation = new DecisionCompletedPayload(
                payload.decisionId(), payload.workflowVersion(), payload.promptVersion(),
                payload.llmModel(), payload.embeddingModel(), payload.recommendation(),
                payload.reasoningSummary(), payload.confidence(), payload.riskLevel(),
                payload.evidenceCoverage(), payload.validationPassed(), payload.validationEscalated(),
                payload.requiredActions(), payload.conditions(), payload.unresolvedQuestions(),
                payload.keyEvidenceChunkIds(), payload.evidence(),
                List.of(new FindingPayload(
                        "POLICY", "Data Residency Policy", "VIOLATED", null,
                        "Data Residency Policy (SP-102 §1)", "Data stored outside approved regions.",
                        new BigDecimal("0.9"), List.of())),
                payload.escalationReasons(), payload.totalInputTokens(), payload.totalOutputTokens(),
                payload.estimatedCostUsd(), payload.latencyMs());

        var result = gate.evaluate(withViolation);

        assertThat(result.requiresApproval()).isTrue();
        assertThat(result.reasons()).contains("A policy finding is VIOLATED");
    }

    @Test
    void promptInjectionFinding_requiresApproval() {
        var payload = cleanPayload();
        var withInjection = new DecisionCompletedPayload(
                payload.decisionId(), payload.workflowVersion(), payload.promptVersion(),
                payload.llmModel(), payload.embeddingModel(), payload.recommendation(),
                payload.reasoningSummary(), payload.confidence(), payload.riskLevel(),
                payload.evidenceCoverage(), payload.validationPassed(), payload.validationEscalated(),
                payload.requiredActions(), payload.conditions(), payload.unresolvedQuestions(),
                payload.keyEvidenceChunkIds(), payload.evidence(),
                List.of(new FindingPayload(
                        "PROMPT_INJECTION_ATTEMPT", null, null, "HIGH",
                        "Possible prompt injection", "Retrieved content attempted to issue instructions.",
                        new BigDecimal("0.9"), List.of())),
                payload.escalationReasons(), payload.totalInputTokens(), payload.totalOutputTokens(),
                payload.estimatedCostUsd(), payload.latencyMs());

        var result = gate.evaluate(withInjection);

        assertThat(result.requiresApproval()).isTrue();
        assertThat(result.reasons()).contains("A PROMPT_INJECTION_ATTEMPT finding was raised");
    }

    @Test
    void riskAtOrAboveThreshold_requiresApproval() {
        var payload = cleanPayload();
        var highRisk = new DecisionCompletedPayload(
                payload.decisionId(), payload.workflowVersion(), payload.promptVersion(),
                payload.llmModel(), payload.embeddingModel(), payload.recommendation(),
                payload.reasoningSummary(), payload.confidence(), "HIGH",
                payload.evidenceCoverage(), payload.validationPassed(), payload.validationEscalated(),
                payload.requiredActions(), payload.conditions(), payload.unresolvedQuestions(),
                payload.keyEvidenceChunkIds(), payload.evidence(), payload.findings(),
                payload.escalationReasons(), payload.totalInputTokens(), payload.totalOutputTokens(),
                payload.estimatedCostUsd(), payload.latencyMs());

        var result = gate.evaluate(highRisk);

        assertThat(result.requiresApproval()).isTrue();
        assertThat(result.reasons()).contains("risk_level=HIGH >= HITL_ESCALATE_ON_RISK=HIGH");
    }

    @Test
    void confidenceBelowThreshold_requiresApproval() {
        var payload = cleanPayload();
        var lowConfidence = new DecisionCompletedPayload(
                payload.decisionId(), payload.workflowVersion(), payload.promptVersion(),
                payload.llmModel(), payload.embeddingModel(), payload.recommendation(),
                payload.reasoningSummary(), new BigDecimal("0.40"), payload.riskLevel(),
                payload.evidenceCoverage(), payload.validationPassed(), payload.validationEscalated(),
                payload.requiredActions(), payload.conditions(), payload.unresolvedQuestions(),
                payload.keyEvidenceChunkIds(), payload.evidence(), payload.findings(),
                payload.escalationReasons(), payload.totalInputTokens(), payload.totalOutputTokens(),
                payload.estimatedCostUsd(), payload.latencyMs());

        var result = gate.evaluate(lowConfidence);

        assertThat(result.requiresApproval()).isTrue();
        assertThat(result.reasons()).contains("confidence=0.40 < HITL_MIN_CONFIDENCE=0.75");
    }

    @Test
    void evidenceCoverageBelowThreshold_requiresApproval() {
        var payload = cleanPayload();
        var lowCoverage = new DecisionCompletedPayload(
                payload.decisionId(), payload.workflowVersion(), payload.promptVersion(),
                payload.llmModel(), payload.embeddingModel(), payload.recommendation(),
                payload.reasoningSummary(), payload.confidence(), payload.riskLevel(),
                new BigDecimal("0.10"), payload.validationPassed(), payload.validationEscalated(),
                payload.requiredActions(), payload.conditions(), payload.unresolvedQuestions(),
                payload.keyEvidenceChunkIds(), payload.evidence(), payload.findings(),
                payload.escalationReasons(), payload.totalInputTokens(), payload.totalOutputTokens(),
                payload.estimatedCostUsd(), payload.latencyMs());

        var result = gate.evaluate(lowCoverage);

        assertThat(result.requiresApproval()).isTrue();
        assertThat(result.reasons()).contains("evidence_coverage=0.10 < HITL_MIN_EVIDENCE_COVERAGE=0.60");
    }

    @Test
    void validatorEscalated_requiresApproval() {
        var payload = cleanPayload();
        var validatorEscalated = new DecisionCompletedPayload(
                payload.decisionId(), payload.workflowVersion(), payload.promptVersion(),
                payload.llmModel(), payload.embeddingModel(), payload.recommendation(),
                payload.reasoningSummary(), payload.confidence(), payload.riskLevel(),
                payload.evidenceCoverage(), false, true,
                payload.requiredActions(), payload.conditions(), payload.unresolvedQuestions(),
                payload.keyEvidenceChunkIds(), payload.evidence(), payload.findings(),
                payload.escalationReasons(), payload.totalInputTokens(), payload.totalOutputTokens(),
                payload.estimatedCostUsd(), payload.latencyMs());

        var result = gate.evaluate(validatorEscalated);

        assertThat(result.requiresApproval()).isTrue();
        assertThat(result.reasons()).contains("Validator escalated after MAX_AGENT_ITERATIONS");
    }

    @Test
    void conflictingEvidenceRecommendation_requiresApproval_regardlessOfConfidence() {
        // .claude/rules/testing.md failure scenario #2: "Contradictory
        // documents -> conflict identified, escalated to human." Unlike
        // INSUFFICIENT_INFORMATION (a complete, honest answer needing no
        // human follow-up unless another trigger also fires),
        // CONFLICTING_EVIDENCE must always escalate. Confidence is
        // deliberately high (0.90, from cleanPayload()) to prove this
        // trigger alone is sufficient, not a coincidence of low_confidence.
        var payload = cleanPayload();
        var conflicting = new DecisionCompletedPayload(
                payload.decisionId(), payload.workflowVersion(), payload.promptVersion(),
                payload.llmModel(), payload.embeddingModel(), "CONFLICTING_EVIDENCE",
                "Security Policy v1 and v2 disagree on data residency and neither supersedes "
                        + "the other in this context.",
                payload.confidence(), payload.riskLevel(),
                payload.evidenceCoverage(), payload.validationPassed(), payload.validationEscalated(),
                payload.requiredActions(), payload.conditions(), payload.unresolvedQuestions(),
                payload.keyEvidenceChunkIds(), payload.evidence(), payload.findings(),
                payload.escalationReasons(), payload.totalInputTokens(), payload.totalOutputTokens(),
                payload.estimatedCostUsd(), payload.latencyMs());

        var result = gate.evaluate(conflicting);

        assertThat(result.requiresApproval()).isTrue();
        assertThat(result.reasons()).contains("recommendation=CONFLICTING_EVIDENCE");
    }

    @Test
    void insufficientInformation_doesNotAloneRequireApproval() {
        // Deliberate asymmetry with CONFLICTING_EVIDENCE above:
        // INSUFFICIENT_INFORMATION is a complete, honest terminal answer —
        // "I don't know" needs no human follow-up unless another trigger
        // (e.g. low confidence) also fires. Confirms this isn't accidentally
        // caught by a broader "any non-APPROVE recommendation escalates"
        // check that was never intended.
        var payload = cleanPayload();
        var insufficientInfo = new DecisionCompletedPayload(
                payload.decisionId(), payload.workflowVersion(), payload.promptVersion(),
                payload.llmModel(), payload.embeddingModel(), "INSUFFICIENT_INFORMATION",
                "The question was not classified as a supported decision type.",
                payload.confidence(), payload.riskLevel(),
                payload.evidenceCoverage(), payload.validationPassed(), payload.validationEscalated(),
                payload.requiredActions(), payload.conditions(), payload.unresolvedQuestions(),
                payload.keyEvidenceChunkIds(), payload.evidence(), payload.findings(),
                payload.escalationReasons(), payload.totalInputTokens(), payload.totalOutputTokens(),
                payload.estimatedCostUsd(), payload.latencyMs());

        var result = gate.evaluate(insufficientInfo);

        assertThat(result.requiresApproval()).isFalse();
    }
}
