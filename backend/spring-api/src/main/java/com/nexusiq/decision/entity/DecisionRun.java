package com.nexusiq.decision.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Immutable once terminal (COMPLETED/FAILED) — .claude/rules/database.md. Enforced at the
 * service layer: nothing calls markCompleted/markFailed twice for the same run. */
@Entity
@Table(name = "decision_runs")
public class DecisionRun {

    @Id
    private UUID id;

    @Column(name = "decision_request_id", nullable = false)
    private UUID decisionRequestId;

    @Column(name = "workflow_version", nullable = false, length = 20)
    private String workflowVersion;

    @Column(name = "prompt_version", length = 20)
    private String promptVersion;

    @Column(name = "llm_model", length = 100)
    private String llmModel;

    @Column(name = "embedding_model", length = 200)
    private String embeddingModel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DecisionRunStatus status = DecisionRunStatus.QUEUED;

    @Column(precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(name = "total_input_tokens", nullable = false)
    private int totalInputTokens = 0;

    @Column(name = "total_output_tokens", nullable = false)
    private int totalOutputTokens = 0;

    @Column(name = "estimated_cost_usd", nullable = false, precision = 10, scale = 6)
    private BigDecimal estimatedCostUsd = BigDecimal.ZERO;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "iteration_count", nullable = false)
    private int iterationCount = 0;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected DecisionRun() {
        // JPA
    }

    public DecisionRun(UUID decisionRequestId, String workflowVersion) {
        this.id = UUID.randomUUID();
        this.decisionRequestId = decisionRequestId;
        this.workflowVersion = workflowVersion;
        this.startedAt = Instant.now();
    }

    public void markRunning() {
        this.status = DecisionRunStatus.RUNNING;
    }

    public void markCompleted(
            String promptVersion,
            String llmModel,
            String embeddingModel,
            BigDecimal confidence,
            int totalInputTokens,
            int totalOutputTokens,
            BigDecimal estimatedCostUsd,
            int latencyMs) {
        this.status = DecisionRunStatus.COMPLETED;
        this.promptVersion = promptVersion;
        this.llmModel = llmModel;
        this.embeddingModel = embeddingModel;
        this.confidence = confidence;
        this.totalInputTokens = totalInputTokens;
        this.totalOutputTokens = totalOutputTokens;
        this.estimatedCostUsd = estimatedCostUsd;
        this.latencyMs = latencyMs;
        this.completedAt = Instant.now();
    }

    public void markFailed(String reason) {
        this.status = DecisionRunStatus.FAILED;
        this.failureReason = reason;
        this.completedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getDecisionRequestId() {
        return decisionRequestId;
    }

    public String getWorkflowVersion() {
        return workflowVersion;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public String getLlmModel() {
        return llmModel;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public DecisionRunStatus getStatus() {
        return status;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public int getTotalInputTokens() {
        return totalInputTokens;
    }

    public int getTotalOutputTokens() {
        return totalOutputTokens;
    }

    public BigDecimal getEstimatedCostUsd() {
        return estimatedCostUsd;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public int getIterationCount() {
        return iterationCount;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
