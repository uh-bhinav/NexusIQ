package com.nexusiq.approval;

import com.nexusiq.approval.dto.ApprovalResponse;
import com.nexusiq.approval.dto.ApproveRequest;
import com.nexusiq.approval.dto.RejectRequest;
import com.nexusiq.approval.entity.ApprovalStatus;
import com.nexusiq.common.PageResponse;
import com.nexusiq.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/approvals")
public class ApprovalController {

    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @GetMapping
    public PageResponse<ApprovalResponse> list(
            @PathVariable UUID workspaceId,
            @RequestParam(required = false) ApprovalStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return PageResponse.of(
                approvalService.list(workspaceId, CurrentUser.id(), status, pageable), r -> r);
    }

    @PostMapping("/{approvalId}/approve")
    public ApprovalResponse approve(
            @PathVariable UUID workspaceId,
            @PathVariable UUID approvalId,
            @RequestBody(required = false) @Valid ApproveRequest request) {
        String notes = request != null ? request.notes() : null;
        return approvalService.approve(workspaceId, approvalId, CurrentUser.id(), notes);
    }

    @PostMapping("/{approvalId}/reject")
    public ApprovalResponse reject(
            @PathVariable UUID workspaceId,
            @PathVariable UUID approvalId,
            @RequestBody @Valid RejectRequest request) {
        return approvalService.reject(workspaceId, approvalId, CurrentUser.id(), request.reason());
    }
}
