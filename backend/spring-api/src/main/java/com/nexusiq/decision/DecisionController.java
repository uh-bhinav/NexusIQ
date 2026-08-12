package com.nexusiq.decision;

import com.nexusiq.common.PageResponse;
import com.nexusiq.decision.dto.CreateDecisionRequest;
import com.nexusiq.decision.dto.DecisionDetailResponse;
import com.nexusiq.decision.dto.DecisionSummaryResponse;
import com.nexusiq.security.CurrentUser;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Nested under the workspace path so every lookup filters on workspace_id in
 * SQL (.claude/rules/security.md, .claude/rules/database.md).
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/decisions")
public class DecisionController {

    private final DecisionService decisionService;

    public DecisionController(DecisionService decisionService) {
        this.decisionService = decisionService;
    }

    /** Long-running work — returns 202, never blocks
     * (.claude/rules/backend-java.md). The AI workflow runs asynchronously
     * via decision.requested; the client polls GET /{id} or subscribes to
     * the SSE stream (Phase 8+). */
    @PostMapping
    public ResponseEntity<DecisionSummaryResponse> create(
            @PathVariable UUID workspaceId, @RequestBody @Valid CreateDecisionRequest request) {
        DecisionSummaryResponse created = decisionService.create(workspaceId, CurrentUser.id(), request);
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/workspaces/" + workspaceId + "/decisions/" + created.id()))
                .body(created);
    }

    @GetMapping
    public PageResponse<DecisionSummaryResponse> list(
            @PathVariable UUID workspaceId, @PageableDefault(size = 20) Pageable pageable) {
        return PageResponse.of(decisionService.list(workspaceId, CurrentUser.id(), pageable), r -> r);
    }

    @GetMapping("/{decisionId}")
    public DecisionDetailResponse get(@PathVariable UUID workspaceId, @PathVariable UUID decisionId) {
        return decisionService.get(decisionId, workspaceId, CurrentUser.id());
    }
}
