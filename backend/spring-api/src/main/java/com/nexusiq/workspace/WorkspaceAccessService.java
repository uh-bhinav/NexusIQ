package com.nexusiq.workspace;

import com.nexusiq.common.exception.ForbiddenException;
import com.nexusiq.common.exception.ResourceNotFoundException;
import com.nexusiq.user.entity.Role;
import com.nexusiq.workspace.entity.WorkspaceMember;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The single place that answers "may this user act on this workspace?" — every
 * other feature (documents, decisions, approvals...) calls through here rather
 * than re-implementing the check (.claude/rules/security.md,
 * .claude/rules/backend-java.md).
 */
@Service
public class WorkspaceAccessService {

    private final WorkspaceMemberRepository memberRepository;

    public WorkspaceAccessService(WorkspaceMemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    /** Throws 404 (not 403) if the user is not a member — never discloses another tenant's workspace. */
    public WorkspaceMember requireMembership(UUID workspaceId, UUID userId) {
        return memberRepository
                .findByWorkspaceIdAndUserId(workspaceId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Workspace not found"));
    }

    /** Membership plus a minimum role within the workspace. 404 if not a member, 403 if under-privileged. */
    public WorkspaceMember requireRole(UUID workspaceId, UUID userId, Role... allowed) {
        WorkspaceMember membership = requireMembership(workspaceId, userId);
        for (Role role : allowed) {
            if (membership.getRole() == role) {
                return membership;
            }
        }
        throw new ForbiddenException("This action requires one of: " + java.util.Arrays.toString(allowed));
    }

    public boolean isMember(UUID workspaceId, UUID userId) {
        return memberRepository.existsByWorkspaceIdAndUserId(workspaceId, userId);
    }
}
