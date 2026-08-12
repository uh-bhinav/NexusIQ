package com.nexusiq.approval.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One row per decision run the deterministic gate (ApprovalGate, ADR-006) escalated.
 * {@code status} starts PENDING and is written to APPROVED/REJECTED exactly once —
 * {@code approve()}/{@code reject()} both assert the current status is PENDING first,
 * so a second call on an already-resolved approval is a programming error, not a
 * silent no-op (the service layer turns that into a 409 before it ever reaches here).
 */
@Entity
@Table(name = "approvals")
public class Approval {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "decision_run_id", nullable = false, unique = true)
    private UUID decisionRunId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ApprovalStatus status = ApprovalStatus.PENDING;

    // Mutable ArrayList, never List.of() — Hibernate mutates JPA-managed
    // collections internally during merge/dirty-checking (Finding.evidenceIds,
    // Decision's TEXT[] fields hit this same UnsupportedOperationException
    // empirically in Phase 5/6).
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "reasons")
    private List<String> reasons = new ArrayList<>();

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolution_notes")
    private String resolutionNotes;

    protected Approval() {
        // JPA
    }

    public Approval(UUID workspaceId, UUID decisionRunId, List<String> reasons) {
        this.id = UUID.randomUUID();
        this.workspaceId = workspaceId;
        this.decisionRunId = decisionRunId;
        this.reasons = reasons != null ? new ArrayList<>(reasons) : new ArrayList<>();
        this.requestedAt = Instant.now();
    }

    public void approve(UUID resolvedBy, String notes) {
        if (status != ApprovalStatus.PENDING) {
            throw new IllegalStateException("Approval " + id + " is already resolved (" + status + ")");
        }
        this.status = ApprovalStatus.APPROVED;
        this.resolvedBy = resolvedBy;
        this.resolutionNotes = notes;
        this.resolvedAt = Instant.now();
    }

    public void reject(UUID resolvedBy, String notes) {
        if (status != ApprovalStatus.PENDING) {
            throw new IllegalStateException("Approval " + id + " is already resolved (" + status + ")");
        }
        this.status = ApprovalStatus.REJECTED;
        this.resolvedBy = resolvedBy;
        this.resolutionNotes = notes;
        this.resolvedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public UUID getDecisionRunId() {
        return decisionRunId;
    }

    public ApprovalStatus getStatus() {
        return status;
    }

    public List<String> getReasons() {
        return reasons;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public UUID getResolvedBy() {
        return resolvedBy;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public String getResolutionNotes() {
        return resolutionNotes;
    }
}
