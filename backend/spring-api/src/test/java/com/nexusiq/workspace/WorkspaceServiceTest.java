package com.nexusiq.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexusiq.audit.AuditService;
import com.nexusiq.common.exception.ConflictException;
import com.nexusiq.common.exception.ForbiddenException;
import com.nexusiq.common.exception.ResourceNotFoundException;
import com.nexusiq.user.UserRepository;
import com.nexusiq.user.entity.Role;
import com.nexusiq.user.entity.User;
import com.nexusiq.workspace.dto.AddMemberRequest;
import com.nexusiq.workspace.dto.CreateWorkspaceRequest;
import com.nexusiq.workspace.entity.Workspace;
import com.nexusiq.workspace.entity.WorkspaceMember;
import com.nexusiq.workspace.mapper.WorkspaceMapper;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkspaceServiceTest {

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private WorkspaceMemberRepository memberRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private WorkspaceAccessService accessService;

    @Mock
    private AuditService auditService;

    private WorkspaceService service;

    private final UUID creatorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new WorkspaceService(
                workspaceRepository, memberRepository, userRepository, accessService, new WorkspaceMapper(), auditService);
    }

    @Test
    void create_addsTheCreatorAsAnAdminMember() {
        when(workspaceRepository.existsBySlug(any())).thenReturn(false);
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(inv -> inv.getArgument(0));

        service.create(new CreateWorkspaceRequest("Acme Corp", "desc"), creatorId);

        verify(memberRepository)
                .save(org.mockito.ArgumentMatchers.argThat(
                        m -> m.getUserId().equals(creatorId) && m.getRole() == Role.ADMIN));
    }

    @Test
    void create_retriesTheSlug_onCollision() {
        when(workspaceRepository.existsBySlug("acme-corp")).thenReturn(true);
        when(workspaceRepository.existsBySlug(org.mockito.ArgumentMatchers.startsWith("acme-corp-")))
                .thenReturn(false);
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.create(new CreateWorkspaceRequest("Acme Corp", null), creatorId);

        assertThat(result.slug()).startsWith("acme-corp-").isNotEqualTo("acme-corp");
    }

    @Test
    void addMember_throwsForbidden_whenRequesterIsNotAnAdmin() {
        when(accessService.requireRole(any(), any(), org.mockito.ArgumentMatchers.eq(Role.ADMIN)))
                .thenThrow(new ForbiddenException("nope"));

        assertThatThrownBy(() -> service.addMember(
                        UUID.randomUUID(), UUID.randomUUID(), new AddMemberRequest("x@example.com", Role.VIEWER)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void addMember_throwsNotFound_whenNoUserHasThatEmail() {
        UUID workspaceId = UUID.randomUUID();
        UUID requesterId = UUID.randomUUID();
        when(accessService.requireRole(workspaceId, requesterId, Role.ADMIN))
                .thenReturn(new WorkspaceMember(workspaceId, requesterId, Role.ADMIN));
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addMember(
                        workspaceId, requesterId, new AddMemberRequest("nobody@example.com", Role.VIEWER)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void addMember_throwsConflict_whenUserIsAlreadyAMember() {
        UUID workspaceId = UUID.randomUUID();
        UUID requesterId = UUID.randomUUID();
        User existingUser = new User("member@example.com", "Member", "hash", Role.ANALYST);
        when(accessService.requireRole(workspaceId, requesterId, Role.ADMIN))
                .thenReturn(new WorkspaceMember(workspaceId, requesterId, Role.ADMIN));
        when(userRepository.findByEmail("member@example.com")).thenReturn(Optional.of(existingUser));
        when(memberRepository.existsByWorkspaceIdAndUserId(workspaceId, existingUser.getId()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.addMember(
                        workspaceId, requesterId, new AddMemberRequest("member@example.com", Role.VIEWER)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void removeMember_throwsNotFound_whenTargetIsNotAMember() {
        UUID workspaceId = UUID.randomUUID();
        UUID requesterId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(accessService.requireRole(workspaceId, requesterId, Role.ADMIN))
                .thenReturn(new WorkspaceMember(workspaceId, requesterId, Role.ADMIN));
        when(memberRepository.existsByWorkspaceIdAndUserId(workspaceId, targetId)).thenReturn(false);

        assertThatThrownBy(() -> service.removeMember(workspaceId, requesterId, targetId))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
