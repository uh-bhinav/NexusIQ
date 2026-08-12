package com.nexusiq.decision.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "decision_requests")
public class DecisionRequest {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(nullable = false)
    private String question;

    @Column(name = "decision_type", length = 50)
    private String decisionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DecisionPriority priority = DecisionPriority.NORMAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DecisionRequestStatus status = DecisionRequestStatus.PENDING;

    @Column(name = "correlation_id")
    private UUID correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DecisionRequest() {
        // JPA
    }

    public DecisionRequest(
            UUID workspaceId, UUID requestedBy, String title, String question, DecisionPriority priority) {
        this.id = UUID.randomUUID();
        this.workspaceId = workspaceId;
        this.requestedBy = requestedBy;
        this.title = title;
        this.question = question;
        this.priority = priority != null ? priority : DecisionPriority.NORMAL;
    }

    public void markProcessing(UUID correlationId) {
        this.status = DecisionRequestStatus.PROCESSING;
        this.correlationId = correlationId;
    }

    public void markCompleted(boolean requiresHumanApproval) {
        this.status = requiresHumanApproval
                ? DecisionRequestStatus.WAITING_FOR_APPROVAL
                : DecisionRequestStatus.APPROVED;
    }

    public void markFailed() {
        this.status = DecisionRequestStatus.FAILED;
    }

    /** Human approval resolved WAITING_FOR_APPROVAL -> APPROVED (ApprovalService, ADR-006). */
    public void markApproved() {
        this.status = DecisionRequestStatus.APPROVED;
    }

    public void markRejected() {
        this.status = DecisionRequestStatus.REJECTED;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public UUID getRequestedBy() {
        return requestedBy;
    }

    public String getTitle() {
        return title;
    }

    public String getQuestion() {
        return question;
    }

    public String getDecisionType() {
        return decisionType;
    }

    public DecisionPriority getPriority() {
        return priority;
    }

    public DecisionRequestStatus getStatus() {
        return status;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
