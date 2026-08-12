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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Immutable — one row written once when the node completes (.claude/rules/database.md). */
@Entity
@Table(name = "agent_executions")
public class AgentExecution {

    @Id
    private UUID id;

    @Column(name = "decision_run_id", nullable = false)
    private UUID decisionRunId;

    @Column(name = "agent_name", nullable = false, length = 50)
    private String agentName;

    @Column(name = "sequence_index", nullable = false)
    private int sequenceIndex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AgentExecutionStatus status;

    @Column(length = 100)
    private String model;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    @Column(name = "latency_ms", nullable = false)
    private int latencyMs;

    @Column(name = "estimated_cost_usd", nullable = false, precision = 10, scale = 6)
    private BigDecimal estimatedCostUsd = BigDecimal.ZERO;

    // @JdbcTypeCode required, not just columnDefinition — see Decision.java's
    // validationDetails comment for why (PGJDBC bind-parameter type mismatch
    // against jsonb, confirmed empirically).
    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private String output;

    private String error;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    protected AgentExecution() {
        // JPA
    }

    public AgentExecution(
            UUID decisionRunId,
            String agentName,
            int sequenceIndex,
            AgentExecutionStatus status,
            String model,
            int inputTokens,
            int outputTokens,
            int latencyMs,
            BigDecimal estimatedCostUsd,
            String output,
            String error,
            String traceId,
            Instant startedAt,
            Instant completedAt) {
        this.id = UUID.randomUUID();
        this.decisionRunId = decisionRunId;
        this.agentName = agentName;
        this.sequenceIndex = sequenceIndex;
        this.status = status;
        this.model = model;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.latencyMs = latencyMs;
        this.estimatedCostUsd = estimatedCostUsd != null ? estimatedCostUsd : BigDecimal.ZERO;
        this.output = output;
        this.error = error;
        this.traceId = traceId;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDecisionRunId() {
        return decisionRunId;
    }

    public String getAgentName() {
        return agentName;
    }

    public int getSequenceIndex() {
        return sequenceIndex;
    }

    public AgentExecutionStatus getStatus() {
        return status;
    }

    public String getModel() {
        return model;
    }

    public int getInputTokens() {
        return inputTokens;
    }

    public int getOutputTokens() {
        return outputTokens;
    }

    public int getLatencyMs() {
        return latencyMs;
    }

    public BigDecimal getEstimatedCostUsd() {
        return estimatedCostUsd;
    }

    public String getOutput() {
        return output;
    }

    public String getError() {
        return error;
    }

    public String getTraceId() {
        return traceId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
