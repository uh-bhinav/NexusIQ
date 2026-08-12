package com.nexusiq.decision.mapper;

import com.nexusiq.decision.dto.AgentExecutionResponse;
import com.nexusiq.decision.dto.DecisionDetailResponse;
import com.nexusiq.decision.dto.DecisionOutcomeResponse;
import com.nexusiq.decision.dto.DecisionRunResponse;
import com.nexusiq.decision.dto.DecisionSummaryResponse;
import com.nexusiq.decision.dto.EvidenceResponse;
import com.nexusiq.decision.dto.FindingResponse;
import com.nexusiq.decision.entity.AgentExecution;
import com.nexusiq.decision.entity.Decision;
import com.nexusiq.decision.entity.DecisionRequest;
import com.nexusiq.decision.entity.DecisionRun;
import com.nexusiq.decision.entity.Evidence;
import com.nexusiq.decision.entity.Finding;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class DecisionMapper {

    public DecisionSummaryResponse toSummary(DecisionRequest request) {
        return new DecisionSummaryResponse(
                request.getId(),
                request.getTitle(),
                request.getQuestion(),
                request.getPriority(),
                request.getStatus(),
                request.getCreatedAt());
    }

    public DecisionDetailResponse toDetail(
            DecisionRequest request,
            DecisionRun run,
            List<AgentExecution> agentExecutions,
            List<Evidence> evidence,
            List<Finding> findings,
            Decision decision) {
        return new DecisionDetailResponse(
                request.getId(),
                request.getTitle(),
                request.getQuestion(),
                request.getPriority(),
                request.getStatus(),
                request.getCreatedAt(),
                run != null ? toRunResponse(run) : null,
                agentExecutions.stream().map(this::toAgentExecutionResponse).toList(),
                evidence.stream().map(this::toEvidenceResponse).toList(),
                findings.stream().map(this::toFindingResponse).toList(),
                decision != null ? toOutcomeResponse(decision) : null);
    }

    private DecisionRunResponse toRunResponse(DecisionRun run) {
        return new DecisionRunResponse(
                run.getId(),
                run.getWorkflowVersion(),
                run.getPromptVersion(),
                run.getLlmModel(),
                run.getEmbeddingModel(),
                run.getStatus(),
                run.getConfidence(),
                run.getTotalInputTokens(),
                run.getTotalOutputTokens(),
                run.getEstimatedCostUsd(),
                run.getLatencyMs(),
                run.getFailureReason(),
                run.getStartedAt(),
                run.getCompletedAt());
    }

    private AgentExecutionResponse toAgentExecutionResponse(AgentExecution execution) {
        return new AgentExecutionResponse(
                execution.getId(),
                execution.getAgentName(),
                execution.getSequenceIndex(),
                execution.getStatus(),
                execution.getModel(),
                execution.getInputTokens(),
                execution.getOutputTokens(),
                execution.getLatencyMs(),
                execution.getEstimatedCostUsd(),
                execution.getError(),
                execution.getStartedAt(),
                execution.getCompletedAt());
    }

    private EvidenceResponse toEvidenceResponse(Evidence item) {
        return new EvidenceResponse(
                item.getId(),
                item.getDocumentId(),
                item.getChunkId(),
                item.getEvidenceText(),
                item.getRelevanceScore(),
                item.getCitationReference());
    }

    private FindingResponse toFindingResponse(Finding finding) {
        return new FindingResponse(
                finding.getId(),
                finding.getCategory(),
                finding.getPolicyName(),
                finding.getStatus(),
                finding.getSeverity(),
                finding.getTitle(),
                finding.getDescription(),
                finding.getConfidence(),
                // List.copyOf materialises the lazy @ElementCollection here,
                // inside the caller's @Transactional method — the response
                // record is built and serialised well after that transaction
                // closes, and a bare reference to the lazy proxy throws
                // LazyInitializationException at serialization time instead
                // (confirmed empirically). Staying LAZY on the entity itself
                // per .claude/rules/backend-java.md; only this call site
                // needs eager materialisation.
                List.copyOf(finding.getEvidenceIds()));
    }

    private DecisionOutcomeResponse toOutcomeResponse(Decision decision) {
        return new DecisionOutcomeResponse(
                decision.getRecommendation(),
                decision.getReasoningSummary(),
                decision.getConfidence(),
                decision.getRiskLevel(),
                decision.getEvidenceCoverage(),
                decision.getValidationPassed(),
                decision.isRequiresHumanApproval(),
                decision.getEscalationReasons(),
                decision.getRequiredActions(),
                decision.getConditions(),
                decision.getUnresolvedQuestions(),
                decision.getFinalStatus());
    }
}
