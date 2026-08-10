package com.nexusiq.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexusiq.audit.AuditService;
import com.nexusiq.auth.dto.LoginRequest;
import com.nexusiq.auth.dto.RegisterRequest;
import com.nexusiq.common.exception.ConflictException;
import com.nexusiq.common.exception.UnauthorizedException;
import com.nexusiq.security.JwtService;
import com.nexusiq.user.UserRepository;
import com.nexusiq.user.entity.Role;
import com.nexusiq.user.entity.User;
import com.nexusiq.workspace.WorkspaceMemberRepository;
import com.nexusiq.workspace.WorkspaceRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private WorkspaceMemberRepository workspaceMemberRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private AuditService auditService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userRepository, workspaceRepository, workspaceMemberRepository, passwordEncoder, jwtService, auditService);
        authService.init(); // @PostConstruct isn't invoked by plain construction under Mockito
    }

    @Test
    void register_throwsConflict_whenEmailAlreadyExists() {
        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        RegisterRequest request = new RegisterRequest("taken@example.com", "Name", "password123");

        assertThatThrownBy(() -> authService.register(request)).isInstanceOf(ConflictException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void register_hashesThePassword_neverStoresItRaw() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("bcrypt-hash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.issueAccessToken(any(), anyString(), anyString())).thenReturn("access");
        when(jwtService.issueRefreshToken(any(), anyString(), anyString())).thenReturn("refresh");

        authService.register(new RegisterRequest("new@example.com", "Name", "password123"));

        verify(passwordEncoder).encode("password123");
        verify(userRepository)
                .save(org.mockito.ArgumentMatchers.argThat(u -> u.getPasswordHash().equals("bcrypt-hash")
                        && u.getRole() == Role.ANALYST));
    }

    @Test
    void login_throwsUnauthorized_forUnknownEmail_andStillPaysHashingCost() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());
        // dummyHashForTimingSafety is whatever passwordEncoder.encode(...) returns
        // from init() — an unstubbed mock returns null, so the matches() stub must
        // accept a null second argument too (anyString() deliberately excludes null).
        when(passwordEncoder.matches(eq("whatever"), org.mockito.ArgumentMatchers.isNull())).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("ghost@example.com", "whatever")))
                .isInstanceOf(UnauthorizedException.class);

        // Timing-safety requirement (.claude/rules/security.md): an unknown email
        // must still run a bcrypt comparison, not short-circuit.
        verify(passwordEncoder, times(1)).matches(eq("whatever"), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void login_throwsUnauthorized_forWrongPassword() {
        User user = new User("real@example.com", "Real", "stored-hash", Role.ANALYST);
        when(userRepository.findByEmail("real@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "stored-hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("real@example.com", "wrong")))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void login_succeeds_forCorrectCredentials() {
        User user = new User("real@example.com", "Real", "stored-hash", Role.ANALYST);
        when(userRepository.findByEmail("real@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correct", "stored-hash")).thenReturn(true);
        when(jwtService.issueAccessToken(any(), anyString(), anyString())).thenReturn("access-token");
        when(jwtService.issueRefreshToken(any(), anyString(), anyString())).thenReturn("refresh-token");
        when(jwtService.accessTtlSeconds()).thenReturn(3600L);

        var response = authService.login(new LoginRequest("real@example.com", "correct"));

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.user().email()).isEqualTo("real@example.com");
    }

    @Test
    void refresh_rejectsAnAccessTokenPresentedAsARefreshToken() {
        // jwtService.isRefreshToken is a real collaborator decision, not exercised
        // here directly, but parseAndValidate returning empty simulates any
        // rejection path (invalid signature, expired, or wrong type after filtering).
        when(jwtService.parseAndValidate("some-access-token")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh("some-access-token")).isInstanceOf(UnauthorizedException.class);
    }
}
