package com.nexusiq.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.nexusiq.audit.AuditService;
import com.nexusiq.common.exception.ResourceNotFoundException;
import com.nexusiq.decision.dto.CreateDecisionRequest;
import com.nexusiq.decision.entity.DecisionPriority;
import com.nexusiq.decision.entity.DecisionRequest;
import com.nexusiq.decision.entity.DecisionRun;
import com.nexusiq.decision.mapper.DecisionMapper;
import com.nexusiq.messaging.DecisionRequestedEvent;
import com.nexusiq.observability.TraceContextPropagation;
import com.nexusiq.workspace.WorkspaceAccessService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class DecisionServiceTest {

    @Mock
    private DecisionRequestRepository decisionRequestRepository;

    @Mock
    private DecisionRunRepository decisionRunRepository;

    @Mock
    private AgentExecutionRepository agentExecutionRepository;

    @Mock
    private EvidenceRepository evidenceRepository;

    @Mock
    private FindingRepository findingRepository;

    @Mock
    private DecisionRepository decisionRepository;

    @Mock
    private WorkspaceAccessService accessService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private TraceContextPropagation traceContext;

    @Mock
    private AuditService auditService;

    private DecisionService service;

    private final UUID workspaceId = UUID.randomUUID();
    private final UUID requesterId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DecisionService(
                decisionRequestRepository,
                decisionRunRepository,
                agentExecutionRepository,
                evidenceRepository,
                findingRepository,
                decisionRepository,
                accessService,
                new DecisionMapper(),
                eventPublisher,
                traceContext,
                auditService);
    }

    @Test
    void create_requiresWorkspaceMembership_createsRequestAndRun_publishesEvent() {
        when(decisionRequestRepository.save(any(DecisionRequest.class))).thenAnswer(inv -> inv.getArgument(0));
        when(decisionRunRepository.save(any(DecisionRun.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateDecisionRequest request = new CreateDecisionRequest(
                "Vendor Alpha approval", "Should Vendor Alpha be approved for EU production?", DecisionPriority.HIGH);

        var response = service.create(workspaceId, requesterId, request);

        assertThat(response.title()).isEqualTo("Vendor Alpha approval");
        org.mockito.Mockito.verify(accessService).requireMembership(workspaceId, requesterId);
        org.mockito.Mockito.verify(eventPublisher).publishEvent(any(DecisionRequestedEvent.class));
    }

    @Test
    void create_recordsAnAuditEvent_soDecisionRequestsAreAuditable() {
        // .claude/rules/security.md: "Every security-relevant action writes an
        // audit_events row: ... decision request, approval/rejection ..." —
        // approval/rejection is covered in ApprovalService; this proves
        // creation is too, using the same lowercase "decision" resource type
        // ApprovalService already writes so GET /audit/resource/decision/{id}
        // returns a single consistent history for one decision.
        when(decisionRequestRepository.save(any(DecisionRequest.class))).thenAnswer(inv -> inv.getArgument(0));
        when(decisionRunRepository.save(any(DecisionRun.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateDecisionRequest request = new CreateDecisionRequest("Title", "Question?", DecisionPriority.NORMAL);
        var response = service.create(workspaceId, requesterId, request);

        org.mockito.Mockito.verify(auditService)
                .record(workspaceId, requesterId, "DECISION_REQUESTED", "decision", response.id());
    }

    @Test
    void create_defaultsToNormalPriority_whenNoneGiven() {
        when(decisionRequestRepository.save(any(DecisionRequest.class))).thenAnswer(inv -> inv.getArgument(0));
        when(decisionRunRepository.save(any(DecisionRun.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateDecisionRequest request = new CreateDecisionRequest("Title", "Question?", null);
        var response = service.create(workspaceId, requesterId, request);

        assertThat(response.priority()).isEqualTo(DecisionPriority.NORMAL);
    }

    @Test
    void get_throwsNotFound_whenDecisionExistsInADifferentWorkspace() {
        UUID decisionId = UUID.randomUUID();
        // The repository query is scoped by workspaceId (findByIdAndWorkspaceId), so a
        // decision belonging to another workspace simply isn't found — this is the
        // behaviour the cross-tenant-denial acceptance criterion depends on.
        when(decisionRequestRepository.findByIdAndWorkspaceId(decisionId, workspaceId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(decisionId, workspaceId, requesterId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void get_returnsDetailEvenWhenNoRunExistsYet() {
        UUID decisionId = UUID.randomUUID();
        DecisionRequest request = new DecisionRequest(
                workspaceId, requesterId, "Title", "Question?", DecisionPriority.NORMAL);
        when(decisionRequestRepository.findByIdAndWorkspaceId(decisionId, workspaceId))
                .thenReturn(Optional.of(request));
        when(decisionRunRepository.findFirstByDecisionRequestIdOrderByStartedAtDesc(request.getId()))
                .thenReturn(Optional.empty());

        var detail = service.get(decisionId, workspaceId, requesterId);

        assertThat(detail.run()).isNull();
        assertThat(detail.agentExecutions()).isEmpty();
        assertThat(detail.evidence()).isEmpty();
        assertThat(detail.findings()).isEmpty();
        assertThat(detail.outcome()).isNull();
    }

    @Test
    void list_requiresWorkspaceMembership() {
        when(decisionRequestRepository.findAllByWorkspaceId(
                        org.mockito.ArgumentMatchers.eq(workspaceId), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        service.list(workspaceId, requesterId, org.springframework.data.domain.Pageable.unpaged());

        org.mockito.Mockito.verify(accessService).requireMembership(workspaceId, requesterId);
    }
}
