package com.nexusiq.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.nexusiq.common.exception.ForbiddenException;
import com.nexusiq.common.exception.ResourceNotFoundException;
import com.nexusiq.user.entity.Role;
import com.nexusiq.workspace.entity.WorkspaceMember;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * WorkspaceAccessService is the single place that decides "may this user act on
 * this workspace?" — every other feature depends on this being right
 * (.claude/rules/security.md).
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceAccessServiceTest {

    @Mock
    private WorkspaceMemberRepository memberRepository;

    private WorkspaceAccessService service;

    private final UUID workspaceId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new WorkspaceAccessService(memberRepository);
    }

    @Test
    void requireMembership_returnsMembership_whenUserIsAMember() {
        WorkspaceMember membership = new WorkspaceMember(workspaceId, userId, Role.ANALYST);
        when(memberRepository.findByWorkspaceIdAndUserId(workspaceId, userId)).thenReturn(Optional.of(membership));

        WorkspaceMember result = service.requireMembership(workspaceId, userId);

        assertThat(result).isSameAs(membership);
    }

    @Test
    void requireMembership_throwsNotFound_whenUserIsNotAMember() {
        when(memberRepository.findByWorkspaceIdAndUserId(workspaceId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireMembership(workspaceId, userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void requireRole_throwsNotFound_notForbidden_whenUserIsNotAMember() {
        when(memberRepository.findByWorkspaceIdAndUserId(workspaceId, userId)).thenReturn(Optional.empty());

        // Non-membership must never be distinguishable from under-privileged
        // membership via a different status code (.claude/rules/security.md).
        assertThatThrownBy(() -> service.requireRole(workspaceId, userId, Role.ADMIN))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void requireRole_throwsForbidden_whenMemberLacksTheRequiredRole() {
        WorkspaceMember membership = new WorkspaceMember(workspaceId, userId, Role.VIEWER);
        when(memberRepository.findByWorkspaceIdAndUserId(workspaceId, userId)).thenReturn(Optional.of(membership));

        assertThatThrownBy(() -> service.requireRole(workspaceId, userId, Role.ADMIN))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void requireRole_succeeds_whenMemberHasOneOfTheAllowedRoles() {
        WorkspaceMember membership = new WorkspaceMember(workspaceId, userId, Role.APPROVER);
        when(memberRepository.findByWorkspaceIdAndUserId(workspaceId, userId)).thenReturn(Optional.of(membership));

        WorkspaceMember result = service.requireRole(workspaceId, userId, Role.ADMIN, Role.APPROVER);

        assertThat(result).isSameAs(membership);
    }

    @Test
    void isMember_reflectsRepository() {
        when(memberRepository.existsByWorkspaceIdAndUserId(workspaceId, userId)).thenReturn(true);

        assertThat(service.isMember(workspaceId, userId)).isTrue();
    }
}
