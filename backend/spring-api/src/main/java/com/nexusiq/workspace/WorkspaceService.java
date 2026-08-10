package com.nexusiq.workspace;

import com.nexusiq.audit.AuditService;
import com.nexusiq.common.exception.ConflictException;
import com.nexusiq.common.exception.ResourceNotFoundException;
import com.nexusiq.user.UserRepository;
import com.nexusiq.user.entity.Role;
import com.nexusiq.user.entity.User;
import com.nexusiq.workspace.dto.AddMemberRequest;
import com.nexusiq.workspace.dto.CreateWorkspaceRequest;
import com.nexusiq.workspace.dto.MemberResponse;
import com.nexusiq.workspace.dto.WorkspaceResponse;
import com.nexusiq.workspace.entity.Workspace;
import com.nexusiq.workspace.entity.WorkspaceMember;
import com.nexusiq.workspace.mapper.WorkspaceMapper;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkspaceService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final WorkspaceAccessService accessService;
    private final WorkspaceMapper mapper;
    private final AuditService auditService;

    public WorkspaceService(
            WorkspaceRepository workspaceRepository,
            WorkspaceMemberRepository memberRepository,
            UserRepository userRepository,
            WorkspaceAccessService accessService,
            WorkspaceMapper mapper,
            AuditService auditService) {
        this.workspaceRepository = workspaceRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.accessService = accessService;
        this.mapper = mapper;
        this.auditService = auditService;
    }

    @Transactional
    public WorkspaceResponse create(CreateWorkspaceRequest request, UUID creatorId) {
        String slug = generateUniqueSlug(request.name());
        Workspace workspace = new Workspace(request.name(), slug, request.description(), creatorId);
        workspace = workspaceRepository.save(workspace);

        // The creator is always an ADMIN of their own workspace.
        memberRepository.save(new WorkspaceMember(workspace.getId(), creatorId, Role.ADMIN));

        auditService.record(workspace.getId(), creatorId, "WORKSPACE_CREATED", "WORKSPACE", workspace.getId());

        return mapper.toResponse(workspace);
    }

    @Transactional(readOnly = true)
    public Page<WorkspaceResponse> listForUser(UUID userId, Pageable pageable) {
        return workspaceRepository.findAllForUser(userId, pageable).map(mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public WorkspaceResponse getForUser(UUID workspaceId, UUID userId) {
        Workspace workspace = workspaceRepository
                .findByIdForUser(workspaceId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Workspace not found"));
        return mapper.toResponse(workspace);
    }

    @Transactional(readOnly = true)
    public List<MemberResponse> listMembers(UUID workspaceId, UUID requesterId) {
        accessService.requireMembership(workspaceId, requesterId);

        List<WorkspaceMember> members = memberRepository.findAllByWorkspaceId(workspaceId);
        var usersById = userRepository
                .findAllById(members.stream().map(WorkspaceMember::getUserId).toList())
                .stream()
                .collect(java.util.stream.Collectors.toMap(User::getId, u -> u));

        return members.stream()
                .map(m -> mapper.toMemberResponse(m, usersById.get(m.getUserId())))
                .toList();
    }

    @Transactional
    public MemberResponse addMember(UUID workspaceId, UUID requesterId, AddMemberRequest request) {
        accessService.requireRole(workspaceId, requesterId, Role.ADMIN);

        User user = userRepository
                .findByEmail(request.email())
                .orElseThrow(() -> new ResourceNotFoundException("No user found with that email"));

        if (memberRepository.existsByWorkspaceIdAndUserId(workspaceId, user.getId())) {
            throw new ConflictException("User is already a member of this workspace");
        }

        WorkspaceMember member = memberRepository.save(new WorkspaceMember(workspaceId, user.getId(), request.role()));

        auditService.record(
                workspaceId,
                requesterId,
                "WORKSPACE_MEMBER_ADDED",
                "WORKSPACE_MEMBER",
                user.getId(),
                java.util.Map.of("email", user.getEmail(), "role", request.role().name()));

        return mapper.toMemberResponse(member, user);
    }

    @Transactional
    public void removeMember(UUID workspaceId, UUID requesterId, UUID targetUserId) {
        accessService.requireRole(workspaceId, requesterId, Role.ADMIN);

        if (!memberRepository.existsByWorkspaceIdAndUserId(workspaceId, targetUserId)) {
            throw new ResourceNotFoundException("Member not found in this workspace");
        }

        memberRepository.deleteByWorkspaceIdAndUserId(workspaceId, targetUserId);

        auditService.record(workspaceId, requesterId, "WORKSPACE_MEMBER_REMOVED", "WORKSPACE_MEMBER", targetUserId);
    }

    private String generateUniqueSlug(String name) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (base.isBlank()) {
            base = "workspace";
        }
        String slug = base;
        while (workspaceRepository.existsBySlug(slug)) {
            slug = base + "-" + Integer.toHexString(RANDOM.nextInt(0xFFFF));
        }
        return slug;
    }
}
