package com.nexusiq.decision.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "findings")
public class Finding {

    @Id
    private UUID id;

    @Column(name = "decision_run_id", nullable = false)
    private UUID decisionRunId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private FindingCategory category;

    @Column(name = "policy_name", length = 500)
    private String policyName;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private FindingStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private FindingSeverity severity;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false, precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** A finding may cite several pieces of evidence (docs/DATABASE/SCHEMA.md).
     * Always a mutable ArrayList, never List.of()/Stream.toList() — Hibernate's
     * PersistentBag wraps this collection and calls mutating methods on it
     * internally during merge/dirty-checking; an immutable list throws
     * UnsupportedOperationException there (confirmed empirically via a real
     * decision.completed message reaching this consumer and DLQing). */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "findings_evidence", joinColumns = @JoinColumn(name = "finding_id"))
    @Column(name = "evidence_id")
    private List<UUID> evidenceIds = new ArrayList<>();

    protected Finding() {
        // JPA
    }

    public Finding(
            UUID decisionRunId,
            FindingCategory category,
            String policyName,
            FindingStatus status,
            FindingSeverity severity,
            String title,
            String description,
            BigDecimal confidence,
            List<UUID> evidenceIds) {
        this.id = UUID.randomUUID();
        this.decisionRunId = decisionRunId;
        this.category = category;
        this.policyName = policyName;
        this.status = status;
        this.severity = severity;
        this.title = title;
        this.description = description;
        this.confidence = confidence;
        this.evidenceIds = evidenceIds != null ? new ArrayList<>(evidenceIds) : new ArrayList<>();
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getDecisionRunId() {
        return decisionRunId;
    }

    public FindingCategory getCategory() {
        return category;
    }

    public String getPolicyName() {
        return policyName;
    }

    public FindingStatus getStatus() {
        return status;
    }

    public FindingSeverity getSeverity() {
        return severity;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<UUID> getEvidenceIds() {
        return evidenceIds;
    }
}
