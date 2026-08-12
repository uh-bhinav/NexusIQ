package com.nexusiq.decision.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code requiresHumanApproval} / {@code escalationReasons} are written by the
 * deterministic gate in Java (ADR-006), never copied from model output
 * (docs/DATABASE/SCHEMA.md). That gate is Phase 7 — until it exists, every
 * completed decision sets {@code requiresHumanApproval = true} unconditionally,
 * the safe default (CLAUDE.md non-negotiable #2: no LLM output may finalise a
 * decision on its own).
 */
@Entity
@Table(name = "decisions")
public class Decision {

    @Id
    private UUID id;

    @Column(name = "decision_run_id", nullable = false, unique = true)
    private UUID decisionRunId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RecommendationType recommendation;

    @Column(name = "reasoning_summary", nullable = false)
    private String reasoningSummary;

    @Column(nullable = false, precision = 4, scale = 3)
    private BigDecimal confidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, length = 10)
    private RiskLevel riskLevel;

    @Column(name = "evidence_coverage", precision = 4, scale = 3)
    private BigDecimal evidenceCoverage;

    @Column(name = "validation_passed")
    private Boolean validationPassed;

    // @JdbcTypeCode needed even for a null value: PGJDBC declares the bind
    // parameter's type from the mapping up front (VARCHAR by default for a
    // String field), and Postgres rejects that against a jsonb column
    // regardless of the actual value — confirmed empirically.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation_details")
    private String validationDetails;

    @Column(name = "requires_human_approval", nullable = false)
    private boolean requiresHumanApproval;

    // Always mutable ArrayLists, never List.of()/Stream.toList() — Hibernate
    // mutates these internally during merge/dirty-checking; an immutable
    // list throws UnsupportedOperationException there (confirmed empirically,
    // same root cause as Finding.evidenceIds).
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "escalation_reasons")
    private List<String> escalationReasons = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "required_actions")
    private List<String> requiredActions = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "conditions")
    private List<String> conditions = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "unresolved_questions")
    private List<String> unresolvedQuestions = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "final_status", nullable = false, length = 20)
    private FinalStatus finalStatus = FinalStatus.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Decision() {
        // JPA
    }

    public Decision(
            UUID decisionRunId,
            RecommendationType recommendation,
            String reasoningSummary,
            BigDecimal confidence,
            RiskLevel riskLevel,
            BigDecimal evidenceCoverage,
            Boolean validationPassed,
            List<String> requiredActions,
            List<String> conditions,
            List<String> unresolvedQuestions,
            boolean requiresHumanApproval,
            List<String> escalationReasons) {
        this.id = UUID.randomUUID();
        this.decisionRunId = decisionRunId;
        this.recommendation = recommendation;
        this.reasoningSummary = reasoningSummary;
        this.confidence = confidence;
        this.riskLevel = riskLevel;
        this.evidenceCoverage = evidenceCoverage;
        this.validationPassed = validationPassed;
        this.requiredActions = requiredActions != null ? new ArrayList<>(requiredActions) : new ArrayList<>();
        this.conditions = conditions != null ? new ArrayList<>(conditions) : new ArrayList<>();
        this.unresolvedQuestions =
                unresolvedQuestions != null ? new ArrayList<>(unresolvedQuestions) : new ArrayList<>();
        this.requiresHumanApproval = requiresHumanApproval;
        this.escalationReasons = escalationReasons != null ? new ArrayList<>(escalationReasons) : new ArrayList<>();
        this.createdAt = Instant.now();
    }

    /** Gate (ApprovalGate, ADR-006) found nothing to escalate — finalises immediately. */
    public void markAutoApproved() {
        this.finalStatus = FinalStatus.AUTO_APPROVED;
    }

    /** Called by ApprovalService once, guarded there by the approval row's own
     * PENDING-only transition (Approval.approve/reject) — never called twice. */
    public void markHumanApproved() {
        this.finalStatus = FinalStatus.HUMAN_APPROVED;
    }

    public void markHumanRejected() {
        this.finalStatus = FinalStatus.HUMAN_REJECTED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDecisionRunId() {
        return decisionRunId;
    }

    public RecommendationType getRecommendation() {
        return recommendation;
    }

    public String getReasoningSummary() {
        return reasoningSummary;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public BigDecimal getEvidenceCoverage() {
        return evidenceCoverage;
    }

    public Boolean getValidationPassed() {
        return validationPassed;
    }

    public String getValidationDetails() {
        return validationDetails;
    }

    public boolean isRequiresHumanApproval() {
        return requiresHumanApproval;
    }

    public List<String> getEscalationReasons() {
        return escalationReasons;
    }

    public List<String> getRequiredActions() {
        return requiredActions;
    }

    public List<String> getConditions() {
        return conditions;
    }

    public List<String> getUnresolvedQuestions() {
        return unresolvedQuestions;
    }

    public FinalStatus getFinalStatus() {
        return finalStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
