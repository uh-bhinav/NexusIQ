package com.nexusiq.auth;

import com.nexusiq.audit.AuditService;
import com.nexusiq.auth.dto.AuthResponse;
import com.nexusiq.auth.dto.LoginRequest;
import com.nexusiq.auth.dto.MeResponse;
import com.nexusiq.auth.dto.RegisterRequest;
import com.nexusiq.common.exception.ConflictException;
import com.nexusiq.common.exception.UnauthorizedException;
import com.nexusiq.security.JwtService;
import com.nexusiq.user.UserRepository;
import com.nexusiq.user.dto.UserSummaryResponse;
import com.nexusiq.user.entity.Role;
import com.nexusiq.user.entity.User;
import com.nexusiq.workspace.WorkspaceMemberRepository;
import com.nexusiq.workspace.WorkspaceRepository;
import com.nexusiq.workspace.dto.WorkspaceSummaryResponse;
import com.nexusiq.workspace.entity.Workspace;
import com.nexusiq.workspace.entity.WorkspaceMember;
import io.jsonwebtoken.Claims;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration and login give identical responses and comparable timing for
 * "no such user" and "wrong password" — .claude/rules/security.md.
 */
@Service
public class AuthService {

    // Self-registration defaults to the least-privileged role that can still use
    // the product meaningfully. ADMIN and APPROVER are granted by an existing
    // admin, never by self-registration.
    private static final Role DEFAULT_SELF_REGISTER_ROLE = Role.ANALYST;

    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService auditService;

    private String dummyHashForTimingSafety;

    public AuthService(
            UserRepository userRepository,
            WorkspaceRepository workspaceRepository,
            WorkspaceMemberRepository workspaceMemberRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            AuditService auditService) {
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.auditService = auditService;
    }

    @PostConstruct
    void init() {
        // Bcrypt-hash a random value once at startup, so a login against an unknown
        // email still pays the cost of a real hash comparison and does not return
        // measurably faster than a login against a known email with a wrong password.
        this.dummyHashForTimingSafety = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new ConflictException("An account with that email already exists");
        }
        User user = new User(
                request.email(), request.name(), passwordEncoder.encode(request.password()), DEFAULT_SELF_REGISTER_ROLE);
        user = userRepository.save(user);

        auditService.record(null, user.getId(), "USER_REGISTERED", "USER", user.getId());

        return issueTokens(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email()).orElse(null);

        boolean matches;
        if (user == null) {
            passwordEncoder.matches(request.password(), dummyHashForTimingSafety);
            matches = false;
        } else {
            matches = passwordEncoder.matches(request.password(), user.getPasswordHash());
        }

        if (!matches || user == null || !user.isActive()) {
            throw new UnauthorizedException("Invalid credentials");
        }

        return issueTokens(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse refresh(String refreshToken) {
        Claims claims = jwtService
                .parseAndValidate(refreshToken)
                .filter(jwtService::isRefreshToken)
                .orElseThrow(() -> new UnauthorizedException("Invalid or expired refresh token"));

        UUID userId = UUID.fromString(claims.getSubject());
        User user = userRepository
                .findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> new UnauthorizedException("Invalid or expired refresh token"));

        return issueTokens(user);
    }

    @Transactional(readOnly = true)
    public MeResponse me(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new UnauthorizedException("User not found"));

        List<WorkspaceMember> memberships = workspaceMemberRepository.findAllByUserId(userId);
        Map<UUID, Workspace> workspacesById = workspaceRepository
                .findAllById(memberships.stream().map(WorkspaceMember::getWorkspaceId).toList())
                .stream()
                .collect(java.util.stream.Collectors.toMap(Workspace::getId, w -> w));

        List<WorkspaceSummaryResponse> workspaces = memberships.stream()
                .map(m -> {
                    Workspace w = workspacesById.get(m.getWorkspaceId());
                    return new WorkspaceSummaryResponse(w.getId(), w.getName(), w.getSlug(), m.getRole());
                })
                .toList();

        return new MeResponse(toSummary(user), workspaces);
    }

    private AuthResponse issueTokens(User user) {
        String access = jwtService.issueAccessToken(user.getId(), user.getEmail(), user.getRole().name());
        String refresh = jwtService.issueRefreshToken(user.getId(), user.getEmail(), user.getRole().name());
        return new AuthResponse(access, refresh, jwtService.accessTtlSeconds(), toSummary(user));
    }

    private UserSummaryResponse toSummary(User user) {
        return new UserSummaryResponse(user.getId(), user.getEmail(), user.getName(), user.getRole());
    }
}
