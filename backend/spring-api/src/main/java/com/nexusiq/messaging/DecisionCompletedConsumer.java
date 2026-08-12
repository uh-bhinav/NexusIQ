package com.nexusiq.messaging;

import com.nexusiq.approval.ApprovalGate;
import com.nexusiq.approval.ApprovalService;
import com.nexusiq.decision.DecisionRepository;
import com.nexusiq.decision.DecisionRequestRepository;
import com.nexusiq.decision.DecisionRunRepository;
import com.nexusiq.decision.EvidenceRepository;
import com.nexusiq.decision.FindingRepository;
import com.nexusiq.decision.entity.Decision;
import com.nexusiq.decision.entity.DecisionRequest;
import com.nexusiq.decision.entity.DecisionRun;
import com.nexusiq.decision.entity.Evidence;
import com.nexusiq.decision.entity.Finding;
import com.nexusiq.decision.entity.FindingCategory;
import com.nexusiq.decision.entity.FindingSeverity;
import com.nexusiq.decision.entity.FindingStatus;
import com.nexusiq.decision.entity.RecommendationType;
import com.nexusiq.decision.entity.RiskLevel;
import com.nexusiq.messaging.entity.ProcessedEvent;
import com.nexusiq.observability.TraceContextPropagation;
import com.nexusiq.streaming.DecisionStatusPayload;
import com.nexusiq.streaming.SseEmitterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.type.TypeFactory;

/**
 * Writes evidence, findings and the final decisions row from
 * {@code decision.completed} — Python never writes these tables itself
 * (.claude/rules/architecture.md). {@link ApprovalGate} (ADR-006) decides
 * {@code requiresHumanApproval} deterministically from the payload's own
 * validated fields; when it escalates, an {@code approvals} row is created
 * via {@link ApprovalService}.
 */
@Component
public class DecisionCompletedConsumer {

    private static final Logger log = LoggerFactory.getLogger(DecisionCompletedConsumer.class);
    public static final String CONSUMER_GROUP = "nexusiq-api-decision-completed";

    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final DecisionRunRepository decisionRunRepository;
    private final DecisionRequestRepository decisionRequestRepository;
    private final EvidenceRepository evidenceRepository;
    private final FindingRepository findingRepository;
    private final DecisionRepository decisionRepository;
    private final ApprovalGate approvalGate;
    private final ApprovalService approvalService;
    private final TraceContextPropagation traceContext;
    private final MeterRegistry meterRegistry;
    private final SseEmitterRegistry sseRegistry;

    public DecisionCompletedConsumer(
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            DecisionRunRepository decisionRunRepository,
            DecisionRequestRepository decisionRequestRepository,
            EvidenceRepository evidenceRepository,
            FindingRepository findingRepository,
            DecisionRepository decisionRepository,
            ApprovalGate approvalGate,
            ApprovalService approvalService,
            TraceContextPropagation traceContext,
            MeterRegistry meterRegistry,
            SseEmitterRegistry sseRegistry) {
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.decisionRunRepository = decisionRunRepository;
        this.decisionRequestRepository = decisionRequestRepository;
        this.evidenceRepository = evidenceRepository;
        this.findingRepository = findingRepository;
        this.decisionRepository = decisionRepository;
        this.approvalGate = approvalGate;
        this.approvalService = approvalService;
        this.traceContext = traceContext;
        this.meterRegistry = meterRegistry;
        this.sseRegistry = sseRegistry;
    }

    @KafkaListener(topics = KafkaTopics.DECISION_COMPLETED, groupId = CONSUMER_GROUP)
    @Transactional
    public void onMessage(String json, Acknowledgment acknowledgment) {
        EventEnvelope<DecisionCompletedPayload> envelope = objectMapper.readValue(
                json,
                TypeFactory.createDefaultInstance()
                        .constructParametricType(EventEnvelope.class, DecisionCompletedPayload.class));

        traceContext.runInSpan(
                "kafka.consume decision.completed",
                envelope.traceparent(),
                Map.of(
                        "event_id", envelope.eventId().toString(),
                        "correlation_id", String.valueOf(envelope.correlationId())),
                () -> process(envelope, acknowledgment));
    }

    private void process(EventEnvelope<DecisionCompletedPayload> envelope, Acknowledgment acknowledgment) {
        if (processedEventRepository.existsByEventIdAndConsumerGroup(envelope.eventId(), CONSUMER_GROUP)) {
            log.info("Duplicate decision.completed event {} — skipping (already applied)", envelope.eventId());
            acknowledgment.acknowledge();
            return;
        }

        DecisionCompletedPayload payload = envelope.payload();
        DecisionRun run = decisionRunRepository
                .findById(payload.decisionId())
                .orElseThrow(() -> new IllegalStateException(
                        "decision.completed for unknown decision run " + payload.decisionId()));
        DecisionRequest request = decisionRequestRepository
                .findById(run.getDecisionRequestId())
                .orElseThrow(() -> new IllegalStateException(
                        "decision.completed run " + run.getId() + " has no decision_requests row"));

        // chunk_id -> evidence.id, so findings can link via findings_evidence
        // once each evidence row actually has a persisted id.
        Map<UUID, UUID> evidenceIdByChunkId = new HashMap<>();
        for (EvidencePayload item : payload.evidence()) {
            Evidence evidence = evidenceRepository.save(new Evidence(
                    run.getId(),
                    item.documentId(),
                    item.chunkId(),
                    item.evidenceText(),
                    item.relevanceScore(),
                    item.citationReference()));
            evidenceIdByChunkId.put(item.chunkId(), evidence.getId());
        }

        for (FindingPayload item : payload.findings()) {
            List<UUID> evidenceIds = item.evidenceChunkIds().stream()
                    .map(evidenceIdByChunkId::get)
                    .filter(Objects::nonNull)
                    .toList();
            findingRepository.save(new Finding(
                    run.getId(),
                    FindingCategory.valueOf(item.category()),
                    item.policyName(),
                    item.status() != null ? FindingStatus.valueOf(item.status()) : null,
                    item.severity() != null ? FindingSeverity.valueOf(item.severity()) : null,
                    item.title(),
                    item.description(),
                    item.confidence(),
                    evidenceIds));
        }

        run.markCompleted(
                payload.promptVersion(),
                payload.llmModel(),
                payload.embeddingModel(),
                payload.confidence(),
                payload.totalInputTokens(),
                payload.totalOutputTokens(),
                payload.estimatedCostUsd(),
                payload.latencyMs());

        ApprovalGate.GateResult gateResult = approvalGate.evaluate(payload);

        // The gate's own structured triggers, plus Python's more specific
        // detail (e.g. "Validator: failed after 2 retries — [...]") when the
        // validator itself was the trigger — both are real, neither replaces
        // the other.
        List<String> escalationReasons = new ArrayList<>(gateResult.reasons());
        escalationReasons.addAll(payload.escalationReasons());

        Decision decision = new Decision(
                run.getId(),
                RecommendationType.valueOf(payload.recommendation()),
                payload.reasoningSummary(),
                payload.confidence(),
                RiskLevel.valueOf(payload.riskLevel()),
                payload.evidenceCoverage(),
                payload.validationPassed(),
                payload.requiredActions(),
                payload.conditions(),
                payload.unresolvedQuestions(),
                gateResult.requiresApproval(),
                escalationReasons);
        if (!gateResult.requiresApproval()) {
            decision.markAutoApproved();
        }
        decisionRepository.save(decision);

        request.markCompleted(gateResult.requiresApproval());
        if (gateResult.requiresApproval()) {
            approvalService.createPending(envelope.workspaceId(), run.getId(), gateResult.reasons());
        }

        processedEventRepository.save(new ProcessedEvent(envelope.eventId(), CONSUMER_GROUP));

        // Business metrics (docs/OPERATIONS/OBSERVABILITY.md "Business" group).
        // human_escalation_rate is deliberately not its own metric — it's the
        // ratio of the "escalated" tag here over the total, computed in
        // Grafana via PromQL rather than duplicated as a second counter.
        Counter.builder("decisions_processed_total")
                .tag("status", gateResult.requiresApproval() ? "escalated" : "auto_approved")
                .register(meterRegistry)
                .increment();
        Counter.builder("decisions_by_recommendation")
                .tag("recommendation", payload.recommendation())
                .register(meterRegistry)
                .increment();

        // Escalation is not terminal for the SSE stream — the client keeps
        // watching until a human resolves it (ApprovalService pushes the
        // eventual decision.completed) or, more rarely, a resume failure.
        // Auto-approval closes the stream here, immediately.
        if (gateResult.requiresApproval()) {
            sseRegistry.send(
                    request.getId(), "approval.required", DecisionStatusPayload.approvalRequired(gateResult.reasons()));
        } else {
            sseRegistry.complete(
                    request.getId(), "decision.completed", DecisionStatusPayload.status("APPROVED"));
        }

        acknowledgment.acknowledge();
    }
}
