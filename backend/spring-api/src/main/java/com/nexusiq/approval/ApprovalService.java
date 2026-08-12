package com.nexusiq.approval;

import com.nexusiq.approval.dto.ApprovalResponse;
import com.nexusiq.approval.entity.Approval;
import com.nexusiq.approval.entity.ApprovalStatus;
import com.nexusiq.approval.mapper.ApprovalMapper;
import com.nexusiq.audit.AuditService;
import com.nexusiq.common.CorrelationIdFilter;
import com.nexusiq.common.exception.ConflictException;
import com.nexusiq.common.exception.ForbiddenException;
import com.nexusiq.common.exception.ResourceNotFoundException;
import com.nexusiq.decision.DecisionRepository;
import com.nexusiq.decision.DecisionRequestRepository;
import com.nexusiq.decision.DecisionRunRepository;
import com.nexusiq.decision.entity.Decision;
import com.nexusiq.decision.entity.DecisionRequest;
import com.nexusiq.decision.entity.DecisionRun;
import com.nexusiq.messaging.ApprovalCompletedEvent;
import com.nexusiq.messaging.ApprovalCompletedPayload;
import com.nexusiq.messaging.EventEnvelope;
import com.nexusiq.observability.TraceContextPropagation;
import com.nexusiq.streaming.DecisionStatusPayload;
import com.nexusiq.streaming.SseEmitterRegistry;
import com.nexusiq.user.entity.Role;
import com.nexusiq.workspace.WorkspaceAccessService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the human side of ADR-006: creating a pending approval when
 * {@link ApprovalGate} escalates, and resolving it. Separation of duties and
 * the APPROVER/ADMIN-only restriction are enforced here, not in the
 * controller (.claude/rules/backend-java.md).
 */
@Service
public class ApprovalService {

    private final ApprovalRepository approvalRepository;
    private final DecisionRunRepository decisionRunRepository;
    private final DecisionRequestRepository decisionRequestRepository;
    private final DecisionRepository decisionRepository;
    private final WorkspaceAccessService workspaceAccessService;
    private final AuditService auditService;
    private final ApprovalMapper mapper;
    private final ApplicationEventPublisher eventPublisher;
    private final TraceContextPropagation traceContext;
    private final MeterRegistry meterRegistry;
    private final SseEmitterRegistry sseRegistry;

    public ApprovalService(
            ApprovalRepository approvalRepository,
            DecisionRunRepository decisionRunRepository,
            DecisionRequestRepository decisionRequestRepository,
            DecisionRepository decisionRepository,
            WorkspaceAccessService workspaceAccessService,
            AuditService auditService,
            ApprovalMapper mapper,
            ApplicationEventPublisher eventPublisher,
            TraceContextPropagation traceContext,
            MeterRegistry meterRegistry,
            SseEmitterRegistry sseRegistry) {
        this.approvalRepository = approvalRepository;
        this.decisionRunRepository = decisionRunRepository;
        this.decisionRequestRepository = decisionRequestRepository;
        this.decisionRepository = decisionRepository;
        this.workspaceAccessService = workspaceAccessService;
        this.auditService = auditService;
        this.mapper = mapper;
        this.eventPublisher = eventPublisher;
        this.traceContext = traceContext;
        this.meterRegistry = meterRegistry;
        this.sseRegistry = sseRegistry;
    }

    /** Called from DecisionCompletedConsumer when ApprovalGate escalates. Not
     * separately @Transactional — joins the caller's already-open transaction. */
    public void createPending(UUID workspaceId, UUID decisionRunId, List<String> reasons) {
        approvalRepository.save(new Approval(workspaceId, decisionRunId, reasons));
    }

    @Transactional(readOnly = true)
    public Page<ApprovalResponse> list(
            UUID workspaceId, UUID requesterId, ApprovalStatus status, Pageable pageable) {
        workspaceAccessService.requireMembership(workspaceId, requesterId);
        Page<Approval> page = status != null
                ? approvalRepository.findAllByWorkspaceIdAndStatus(workspaceId, status, pageable)
                : approvalRepository.findAllByWorkspaceId(workspaceId, pageable);
        return page.map(approval -> mapper.toResponse(approval, requestFor(approval)));
    }

    @Transactional
    public ApprovalResponse approve(UUID workspaceId, UUID approvalId, UUID actorId, String notes) {
        workspaceAccessService.requireRole(workspaceId, actorId, Role.APPROVER, Role.ADMIN);
        Approval approval = requireApproval(workspaceId, approvalId);
        DecisionRun run = runFor(approval);
        DecisionRequest request = requestFor(run);

        requireNotOwnRequest(request, actorId);
        requirePending(approval);

        approval.approve(actorId, notes);
        recordTurnaround(approval, "approved");
        decisionRepository
                .findByDecisionRunId(run.getId())
                .ifPresent(Decision::markHumanApproved);
        request.markApproved();

        auditService.record(
                workspaceId,
                actorId,
                "APPROVAL_GRANTED",
                "decision",
                request.getId(),
                Map.of("approval_id", approval.getId().toString()));

        publishCompleted(workspaceId, run.getId(), approval, "APPROVED", actorId, notes);
        sseRegistry.complete(request.getId(), "decision.completed", DecisionStatusPayload.status("APPROVED"));

        return mapper.toResponse(approval, request);
    }

    @Transactional
    public ApprovalResponse reject(UUID workspaceId, UUID approvalId, UUID actorId, String reason) {
        workspaceAccessService.requireRole(workspaceId, actorId, Role.APPROVER, Role.ADMIN);
        Approval approval = requireApproval(workspaceId, approvalId);
        DecisionRun run = runFor(approval);
        DecisionRequest request = requestFor(run);

        requireNotOwnRequest(request, actorId);
        requirePending(approval);

        approval.reject(actorId, reason);
        recordTurnaround(approval, "rejected");
        decisionRepository
                .findByDecisionRunId(run.getId())
                .ifPresent(Decision::markHumanRejected);
        request.markRejected();

        auditService.record(
                workspaceId,
                actorId,
                "APPROVAL_REJECTED",
                "decision",
                request.getId(),
                Map.of("approval_id", approval.getId().toString(), "reason", reason));

        publishCompleted(workspaceId, run.getId(), approval, "REJECTED", actorId, reason);
        sseRegistry.complete(request.getId(), "decision.completed", DecisionStatusPayload.status("REJECTED"));

        return mapper.toResponse(approval, request);
    }

    /** Business metric (docs/OPERATIONS/OBSERVABILITY.md): time from the gate
     * escalating to a human resolving it. */
    private void recordTurnaround(Approval approval, String outcome) {
        Duration turnaround = Duration.between(approval.getRequestedAt(), approval.getResolvedAt());
        meterRegistry
                .timer("approval_turnaround_seconds", "outcome", outcome)
                .record(turnaround);
    }

    private void publishCompleted(
            UUID workspaceId, UUID decisionRunId, Approval approval, String outcome, UUID actorId, String notes) {
        UUID correlationId = safeCorrelationId();
        ApprovalCompletedPayload payload =
                new ApprovalCompletedPayload(approval.getId(), decisionRunId, outcome, actorId, notes);
        EventEnvelope<ApprovalCompletedPayload> envelope = EventEnvelope.newEvent(
                "APPROVAL_COMPLETED", workspaceId, correlationId, traceContext.currentTraceparent(), payload);
        eventPublisher.publishEvent(new ApprovalCompletedEvent(envelope));
    }

    private UUID safeCorrelationId() {
        try {
            return UUID.fromString(CorrelationIdFilter.currentOrNew());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Approval requireApproval(UUID workspaceId, UUID approvalId) {
        return approvalRepository
                .findByIdAndWorkspaceId(approvalId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Approval not found"));
    }

    private DecisionRun runFor(Approval approval) {
        return decisionRunRepository
                .findById(approval.getDecisionRunId())
                .orElseThrow(() -> new IllegalStateException(
                        "Approval " + approval.getId() + " references unknown decision run"));
    }

    private DecisionRequest requestFor(DecisionRun run) {
        return decisionRequestRepository
                .findById(run.getDecisionRequestId())
                .orElseThrow(() -> new IllegalStateException(
                        "Decision run " + run.getId() + " has no decision_requests row"));
    }

    private DecisionRequest requestFor(Approval approval) {
        return requestFor(runFor(approval));
    }

    /** ADR-006 / .claude/rules/backend-java.md: "A user may never approve a
     * decision they requested." Checked here, tested at both the service and
     * HTTP level. */
    private void requireNotOwnRequest(DecisionRequest request, UUID actorId) {
        if (request.getRequestedBy().equals(actorId)) {
            throw new ForbiddenException("You cannot act on a decision you requested yourself");
        }
    }

    private void requirePending(Approval approval) {
        if (approval.getStatus() != ApprovalStatus.PENDING) {
            throw new ConflictException("This approval has already been resolved (" + approval.getStatus() + ")");
        }
    }
}
