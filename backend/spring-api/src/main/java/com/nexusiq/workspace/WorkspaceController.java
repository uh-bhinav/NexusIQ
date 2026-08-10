package com.nexusiq.workspace;

import com.nexusiq.common.PageResponse;
import com.nexusiq.security.CurrentUser;
import com.nexusiq.workspace.dto.AddMemberRequest;
import com.nexusiq.workspace.dto.CreateWorkspaceRequest;
import com.nexusiq.workspace.dto.MemberResponse;
import com.nexusiq.workspace.dto.WorkspaceResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    // VIEWER may read workspaces but not create one — acceptance criterion #5.
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'APPROVER')")
    public ResponseEntity<WorkspaceResponse> create(@Valid @RequestBody CreateWorkspaceRequest request) {
        WorkspaceResponse created = workspaceService.create(request, CurrentUser.id());
        return ResponseEntity.created(URI.create("/api/v1/workspaces/" + created.id())).body(created);
    }

    @GetMapping
    public PageResponse<WorkspaceResponse> list(@PageableDefault(size = 20) Pageable pageable) {
        return PageResponse.of(workspaceService.listForUser(CurrentUser.id(), pageable), r -> r);
    }

    @GetMapping("/{id}")
    public WorkspaceResponse get(@PathVariable UUID id) {
        return workspaceService.getForUser(id, CurrentUser.id());
    }

    @GetMapping("/{id}/members")
    public List<MemberResponse> listMembers(@PathVariable UUID id) {
        return workspaceService.listMembers(id, CurrentUser.id());
    }

    @PostMapping("/{id}/members")
    public MemberResponse addMember(@PathVariable UUID id, @Valid @RequestBody AddMemberRequest request) {
        return workspaceService.addMember(id, CurrentUser.id(), request);
    }

    @DeleteMapping("/{id}/members/{userId}")
    public ResponseEntity<Void> removeMember(@PathVariable UUID id, @PathVariable UUID userId) {
        workspaceService.removeMember(id, CurrentUser.id(), userId);
        return ResponseEntity.noContent().build();
    }
}
