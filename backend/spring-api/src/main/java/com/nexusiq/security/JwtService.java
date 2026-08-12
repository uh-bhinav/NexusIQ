package com.nexusiq.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies access/refresh JWTs. Fails startup loudly if the secret is
 * missing or too short — never falls back to a default (.claude/rules/security.md).
 */
@Service
@EnableConfigurationProperties(JwtProperties.class)
public class JwtService {

    private static final String CLAIM_TOKEN_TYPE = "type";
    private static final String CLAIM_DECISION_ID = "did";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";
    private static final String TYPE_STREAM = "stream";
    // Long enough for a browser to open the EventSource connection after
    // fetching the token; short enough that the value briefly appearing in
    // a query string (docs/API/API_DESIGN.md's documented reason a bare
    // access token must never go there) is a low-value target even if it
    // leaks into an access log.
    private static final long STREAM_TTL_SECONDS = 30;
    private static final int MIN_SECRET_BYTES = 32;

    private final JwtProperties properties;
    private SecretKey signingKey;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void init() {
        if (properties.secret() == null || properties.secret().isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET is not set. Refusing to start with no signing key. Set it in .env.");
        }
        byte[] keyBytes = properties.secret().getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET must be at least " + MIN_SECRET_BYTES + " bytes; got " + keyBytes.length + ".");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public String issueAccessToken(UUID userId, String email, String role) {
        return issue(userId, email, role, TYPE_ACCESS, properties.accessTtlSeconds());
    }

    public String issueRefreshToken(UUID userId, String email, String role) {
        return issue(userId, email, role, TYPE_REFRESH, properties.refreshTtlSeconds());
    }

    /** Scoped to exactly one decision's SSE stream (docs/API/API_DESIGN.md
     * "SSE": "if EventSource cannot set headers, use a short-lived
     * single-use stream token issued by the API — never put the access
     * token in a query string"). {@link #isStreamTokenForDecision} is how a
     * caller checks the scope actually matches the stream being opened. */
    public String issueStreamToken(UUID userId, String email, String role, UUID decisionId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .claim("role", role)
                .claim(CLAIM_TOKEN_TYPE, TYPE_STREAM)
                .claim(CLAIM_DECISION_ID, decisionId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(STREAM_TTL_SECONDS)))
                .signWith(signingKey)
                .compact();
    }

    private String issue(UUID userId, String email, String role, String type, long ttlSeconds) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .claim("role", role)
                .claim(CLAIM_TOKEN_TYPE, type)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(signingKey)
                .compact();
    }

    public Optional<Claims> parseAndValidate(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(signingKey).build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(claims);
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public boolean isAccessToken(Claims claims) {
        return TYPE_ACCESS.equals(claims.get(CLAIM_TOKEN_TYPE, String.class));
    }

    public boolean isRefreshToken(Claims claims) {
        return TYPE_REFRESH.equals(claims.get(CLAIM_TOKEN_TYPE, String.class));
    }

    /** True only for a stream token whose embedded decision id matches the
     * one actually being requested — a stream token for decision A must not
     * authenticate a request for decision B's stream. */
    public boolean isStreamTokenForDecision(Claims claims, UUID decisionId) {
        return TYPE_STREAM.equals(claims.get(CLAIM_TOKEN_TYPE, String.class))
                && decisionId.toString().equals(claims.get(CLAIM_DECISION_ID, String.class));
    }

    public long accessTtlSeconds() {
        return properties.accessTtlSeconds();
    }
}
