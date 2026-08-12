package com.nexusiq.approval;

import com.nexusiq.messaging.DecisionCompletedPayload;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The deterministic gate (ADR-006). Zero LLM calls — reads only validated,
 * schema-constrained fields off {@link DecisionCompletedPayload} and applies
 * thresholds from configuration. This is the one place in the system that
 * decides whether a decision may finalise on its own or needs a human
 * (CLAUDE.md non-negotiable #2): nothing here trusts a model-produced boolean,
 * every trigger is a direct comparison against a persisted, structured value.
 *
 * <p>ai-service's {@code approval_router_node} mirrors this exact rule (same
 * six triggers, same threshold names) to decide whether to suspend the
 * LangGraph run via {@code interrupt()} — but this gate is authoritative for
 * the actual approval record; Python's copy only controls execution, never
 * approval state (ADR-006: "Java owns authority... LangGraph owns execution").
 */
@Component
@EnableConfigurationProperties(HitlProperties.class)
public class ApprovalGate {

    private static final Map<String, Integer> RISK_ORDINAL =
            Map.of("LOW", 0, "MEDIUM", 1, "HIGH", 2, "CRITICAL", 3);

    private final HitlProperties properties;

    public ApprovalGate(HitlProperties properties) {
        this.properties = properties;
    }

    public GateResult evaluate(DecisionCompletedPayload payload) {
        List<String> reasons = new ArrayList<>();

        boolean anyViolated = payload.findings().stream()
                .anyMatch(f -> "POLICY".equals(f.category()) && "VIOLATED".equals(f.status()));
        if (anyViolated) {
            reasons.add("A policy finding is VIOLATED");
        }

        boolean anyInjection =
                payload.findings().stream().anyMatch(f -> "PROMPT_INJECTION_ATTEMPT".equals(f.category()));
        if (anyInjection) {
            reasons.add("A PROMPT_INJECTION_ATTEMPT finding was raised");
        }

        int riskOrdinal = riskOrdinal(payload.riskLevel());
        int thresholdOrdinal = riskOrdinal(properties.escalateOnRisk());
        if (riskOrdinal >= thresholdOrdinal) {
            reasons.add("risk_level=" + payload.riskLevel() + " >= HITL_ESCALATE_ON_RISK="
                    + properties.escalateOnRisk());
        }

        if (payload.confidence() != null && payload.confidence().compareTo(properties.minConfidence()) < 0) {
            reasons.add("confidence=" + payload.confidence() + " < HITL_MIN_CONFIDENCE="
                    + properties.minConfidence());
        }

        BigDecimal coverage = payload.evidenceCoverage();
        if (coverage != null && coverage.compareTo(properties.minEvidenceCoverage()) < 0) {
            reasons.add("evidence_coverage=" + coverage + " < HITL_MIN_EVIDENCE_COVERAGE="
                    + properties.minEvidenceCoverage());
        }

        if (Boolean.TRUE.equals(payload.validationEscalated())) {
            reasons.add("Validator escalated after MAX_AGENT_ITERATIONS");
        }

        return new GateResult(!reasons.isEmpty(), reasons);
    }

    private int riskOrdinal(String riskLevel) {
        Integer ordinal = RISK_ORDINAL.get(riskLevel);
        if (ordinal == null) {
            throw new IllegalArgumentException("Unknown risk level: " + riskLevel);
        }
        return ordinal;
    }

    public record GateResult(boolean requiresApproval, List<String> reasons) {}
}
