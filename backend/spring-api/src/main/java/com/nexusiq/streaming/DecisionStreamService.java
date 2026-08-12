package com.nexusiq.streaming;

import com.nexusiq.common.exception.ResourceNotFoundException;
import com.nexusiq.decision.DecisionRequestRepository;
import com.nexusiq.decision.entity.DecisionRequest;
import com.nexusiq.security.JwtService;
import com.nexusiq.user.UserRepository;
import com.nexusiq.user.entity.User;
import com.nexusiq.workspace.WorkspaceAccessService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Authorization + wiring for `.../decisions/{id}/stream-token` and
 * `.../decisions/{id}/stream` (docs/API/API_DESIGN.md "SSE"). Both endpoints
 * repeat the same "does this decision exist in a workspace this user
 * belongs to" check every other decision-scoped endpoint does — filtered in
 * SQL, 404 rather than fetch-then-check (.claude/rules/security.md). */
@Service
public class DecisionStreamService {

    private final WorkspaceAccessService workspaceAccessService;
    private final DecisionRequestRepository decisionRequestRepository;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final SseEmitterRegistry sseRegistry;

    public DecisionStreamService(
            WorkspaceAccessService workspaceAccessService,
            DecisionRequestRepository decisionRequestRepository,
            UserRepository userRepository,
            JwtService jwtService,
            SseEmitterRegistry sseRegistry) {
        this.workspaceAccessService = workspaceAccessService;
        this.decisionRequestRepository = decisionRequestRepository;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.sseRegistry = sseRegistry;
    }

    @Transactional(readOnly = true)
    public String issueStreamToken(UUID workspaceId, UUID decisionId, UUID userId) {
        DecisionRequest request = requireDecision(workspaceId, decisionId, userId);
        User user = userRepository
                .findById(userId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user " + userId + " not found"));
        return jwtService.issueStreamToken(user.getId(), user.getEmail(), user.getRole().name(), request.getId());
    }

    @Transactional(readOnly = true)
    public SseEmitter openStream(UUID workspaceId, UUID decisionId, UUID userId) {
        DecisionRequest request = requireDecision(workspaceId, decisionId, userId);
        SseEmitter emitter = sseRegistry.register(request.getId());
        // Reconciliation event (docs/API/API_DESIGN.md: "on reconnect,
        // reconcile with a fresh GET /decisions/{id} — never assume the
        // stream was complete") — sent immediately so a client that opens
        // the stream after some progress already happened isn't left
        // showing a stale "PENDING" indicator until the next Kafka event.
        sseRegistry.send(request.getId(), "decision.status", DecisionStatusPayload.status(request.getStatus().name()));
        return emitter;
    }

    private DecisionRequest requireDecision(UUID workspaceId, UUID decisionId, UUID userId) {
        workspaceAccessService.requireMembership(workspaceId, userId);
        return decisionRequestRepository
                .findByIdAndWorkspaceId(decisionId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Decision not found"));
    }
}
