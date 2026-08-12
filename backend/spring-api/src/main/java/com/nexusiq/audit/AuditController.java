package com.nexusiq.audit;

import com.nexusiq.audit.dto.AuditEventResponse;
import com.nexusiq.audit.entity.AuditEvent;
import com.nexusiq.common.PageResponse;
import com.nexusiq.security.CurrentUser;
import com.nexusiq.workspace.WorkspaceAccessService;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditEventRepository repository;
    private final WorkspaceAccessService workspaceAccessService;

    public AuditController(AuditEventRepository repository, WorkspaceAccessService workspaceAccessService) {
        this.repository = repository;
        this.workspaceAccessService = workspaceAccessService;
    }

    @GetMapping
    public PageResponse<AuditEventResponse> list(
            @RequestParam UUID workspaceId, @PageableDefault(size = 20) Pageable pageable) {
        workspaceAccessService.requireMembership(workspaceId, CurrentUser.id());
        var page = repository.findAllByWorkspaceIdOrderByOccurredAtDesc(workspaceId, pageable);
        return PageResponse.of(page, this::toResponse);
    }

    @GetMapping("/resource/{resourceType}/{resourceId}")
    public PageResponse<AuditEventResponse> forResource(
            @PathVariable String resourceType,
            @PathVariable UUID resourceId,
            @RequestParam UUID workspaceId,
            @PageableDefault(size = 20) Pageable pageable) {
        workspaceAccessService.requireMembership(workspaceId, CurrentUser.id());
        var page = repository.findAllForResource(workspaceId, resourceType, resourceId, pageable);
        return PageResponse.of(page, this::toResponse);
    }

    private AuditEventResponse toResponse(AuditEvent e) {
        return new AuditEventResponse(
                e.getId(),
                e.getWorkspaceId(),
                e.getActorId(),
                e.getEventType(),
                e.getResourceType(),
                e.getResourceId(),
                e.getCorrelationId(),
                e.getMetadata(),
                e.getOccurredAt());
    }
}
