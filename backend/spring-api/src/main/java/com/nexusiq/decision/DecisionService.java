package com.nexusiq.decision;

import com.nexusiq.audit.AuditService;
import com.nexusiq.common.CorrelationIdFilter;
import com.nexusiq.common.exception.ResourceNotFoundException;
import com.nexusiq.decision.dto.CreateDecisionRequest;
import com.nexusiq.decision.dto.DecisionDetailResponse;
import com.nexusiq.decision.dto.DecisionSummaryResponse;
import com.nexusiq.decision.entity.AgentExecution;
import com.nexusiq.decision.entity.Decision;
import com.nexusiq.decision.entity.DecisionRequest;
import com.nexusiq.decision.entity.DecisionRun;
import com.nexusiq.decision.entity.Evidence;
import com.nexusiq.decision.entity.Finding;
import com.nexusiq.decision.mapper.DecisionMapper;
import com.nexusiq.messaging.DecisionRequestedEvent;
import com.nexusiq.messaging.DecisionRequestedPayload;
import com.nexusiq.messaging.EventEnvelope;
import com.nexusiq.observability.TraceContextPropagation;
import com.nexusiq.workspace.WorkspaceAccessService;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DecisionService {

    private static final Logger log = LoggerFactory.getLogger(DecisionService.class);

    /** Every run declares which prompt set it used (docs/AI/PROMPTS.md);
     * all five agent prompts are currently the _v1.md files. */
    private static final String WORKFLOW_VERSION = "v1";

    private final DecisionRequestRepository decisionRequestRepository;
    private final DecisionRunRepository decisionRunRepository;
    private final AgentExecutionRepository agentExecutionRepository;
    private final EvidenceRepository evidenceRepository;
    private final FindingRepository findingRepository;
    private final DecisionRepository decisionRepository;
    private final WorkspaceAccessService accessService;
    private final DecisionMapper mapper;
    private final ApplicationEventPublisher eventPublisher;
    private final TraceContextPropagation traceContext;
    private final AuditService auditService;

    public DecisionService(
            DecisionRequestRepository decisionRequestRepository,
            DecisionRunRepository decisionRunRepository,
            AgentExecutionRepository agentExecutionRepository,
            EvidenceRepository evidenceRepository,
            FindingRepository findingRepository,
            DecisionRepository decisionRepository,
            WorkspaceAccessService accessService,
            DecisionMapper mapper,
            ApplicationEventPublisher eventPublisher,
            TraceContextPropagation traceContext,
            AuditService auditService) {
        this.decisionRequestRepository = decisionRequestRepository;
        this.decisionRunRepository = decisionRunRepository;
        this.agentExecutionRepository = agentExecutionRepository;
        this.evidenceRepository = evidenceRepository;
        this.findingRepository = findingRepository;
        this.decisionRepository = decisionRepository;
        this.accessService = accessService;
        this.mapper = mapper;
        this.eventPublisher = eventPublisher;
        this.traceContext = traceContext;
        this.auditService = auditService;
    }

    @Transactional
    public DecisionSummaryResponse create(UUID workspaceId, UUID requesterId, CreateDecisionRequest request) {
        accessService.requireMembership(workspaceId, requesterId);

        DecisionRequest decisionRequest = new DecisionRequest(
                workspaceId, requesterId, request.title(), request.question(), request.priority());
        decisionRequest = decisionRequestRepository.save(decisionRequest);

        // The run's own id is what's sent as "decision_id" — it's the
        // LangGraph checkpoint thread_id on the Python side, so it must be
        // unique per run, not per request (a retried request would collide
        // on and incorrectly resume a stale graph otherwise).
        DecisionRun run = new DecisionRun(decisionRequest.getId(), WORKFLOW_VERSION);
        run.markRunning();
        run = decisionRunRepository.save(run);

        UUID correlationId = parseCorrelationId(CorrelationIdFilter.currentOrNew());
        decisionRequest.markProcessing(correlationId);

        DecisionRequestedPayload payload = new DecisionRequestedPayload(run.getId(), request.question());
        EventEnvelope<DecisionRequestedPayload> envelope = EventEnvelope.newEvent(
                "DECISION_REQUESTED", workspaceId, correlationId, traceContext.currentTraceparent(), payload);
        eventPublisher.publishEvent(new DecisionRequestedEvent(envelope));

        auditService.record(workspaceId, requesterId, "DECISION_REQUESTED", "decision", decisionRequest.getId());

        // Significant lifecycle event (.claude/rules/backend-java.md
        // "Observability": structured JSON logging with correlation_id in
        // MDC). %X{correlationId} in the log pattern already carries this
        // for every line on this thread, but it's repeated explicitly here
        // too so the id is findable even by something parsing plain message
        // text rather than the MDC field (roadmap Phase 8 acceptance
        // criterion 2: "the same correlation_id appears in Java logs,
        // Python logs and the trace").
        log.info(
                "Decision request created: decision_id={}, run_id={}, correlation_id={}",
                decisionRequest.getId(),
                run.getId(),
                correlationId);

        return mapper.toSummary(decisionRequest);
    }

    @Transactional(readOnly = true)
    public Page<DecisionSummaryResponse> list(UUID workspaceId, UUID requesterId, Pageable pageable) {
        accessService.requireMembership(workspaceId, requesterId);
        return decisionRequestRepository.findAllByWorkspaceId(workspaceId, pageable).map(mapper::toSummary);
    }

    @Transactional(readOnly = true)
    public DecisionDetailResponse get(UUID decisionId, UUID workspaceId, UUID requesterId) {
        accessService.requireMembership(workspaceId, requesterId);
        DecisionRequest request = decisionRequestRepository
                .findByIdAndWorkspaceId(decisionId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Decision not found"));

        DecisionRun run = decisionRunRepository
                .findFirstByDecisionRequestIdOrderByStartedAtDesc(request.getId())
                .orElse(null);

        List<AgentExecution> agentExecutions = run != null
                ? agentExecutionRepository.findAllByDecisionRunIdOrderBySequenceIndexAsc(run.getId())
                : List.of();
        List<Evidence> evidence = run != null ? evidenceRepository.findAllByDecisionRunId(run.getId()) : List.of();
        List<Finding> findings = run != null ? findingRepository.findAllByDecisionRunId(run.getId()) : List.of();
        Decision decision = run != null ? decisionRepository.findByDecisionRunId(run.getId()).orElse(null) : null;

        return mapper.toDetail(request, run, agentExecutions, evidence, findings, decision);
    }

    private static UUID parseCorrelationId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return UUID.randomUUID();
        }
    }
}
