package com.nexusiq.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String VALID_SECRET = "a-secret-that-is-at-least-32-bytes-long-for-hs256";

    @Test
    void init_throws_whenSecretIsMissing() {
        JwtService service = new JwtService(new JwtProperties(null, 3600, 604800));

        assertThatThrownBy(service::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void init_throws_whenSecretIsTooShort() {
        JwtService service = new JwtService(new JwtProperties("too-short", 3600, 604800));

        assertThatThrownBy(service::init).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void issuedAccessToken_parsesBackToTheSameClaimsAndIsMarkedAccess() {
        JwtService service = new JwtService(new JwtProperties(VALID_SECRET, 3600, 604800));
        service.init();

        UUID userId = UUID.randomUUID();
        String token = service.issueAccessToken(userId, "user@example.com", "ANALYST");

        var claims = service.parseAndValidate(token);
        assertThat(claims).isPresent();
        assertThat(claims.get().getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get().get("role", String.class)).isEqualTo("ANALYST");
        assertThat(service.isAccessToken(claims.get())).isTrue();
        assertThat(service.isRefreshToken(claims.get())).isFalse();
    }

    @Test
    void issuedRefreshToken_isMarkedRefresh_notAccess() {
        JwtService service = new JwtService(new JwtProperties(VALID_SECRET, 3600, 604800));
        service.init();

        String token = service.issueRefreshToken(UUID.randomUUID(), "user@example.com", "ANALYST");

        var claims = service.parseAndValidate(token).orElseThrow();
        assertThat(service.isRefreshToken(claims)).isTrue();
        assertThat(service.isAccessToken(claims)).isFalse();
    }

    @Test
    void parseAndValidate_rejectsGarbage() {
        JwtService service = new JwtService(new JwtProperties(VALID_SECRET, 3600, 604800));
        service.init();

        assertThat(service.parseAndValidate("not-a-jwt")).isEmpty();
    }

    @Test
    void parseAndValidate_rejectsATokenSignedWithADifferentSecret() {
        JwtService signer = new JwtService(new JwtProperties(VALID_SECRET, 3600, 604800));
        signer.init();
        String token = signer.issueAccessToken(UUID.randomUUID(), "user@example.com", "ANALYST");

        JwtService verifier =
                new JwtService(new JwtProperties("a-completely-different-secret-that-is-also-32-bytes", 3600, 604800));
        verifier.init();

        assertThat(verifier.parseAndValidate(token)).isEmpty();
    }

    @Test
    void expiredToken_isRejected() throws InterruptedException {
        // 0-second TTL: the token is already expired by the time it's parsed.
        JwtService service = new JwtService(new JwtProperties(VALID_SECRET, 0, 604800));
        service.init();

        String token = service.issueAccessToken(UUID.randomUUID(), "user@example.com", "ANALYST");
        Thread.sleep(50);

        assertThat(service.parseAndValidate(token)).isEmpty();
    }
}
