package com.nexusiq.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Verifies the Bearer access token and populates the SecurityContext. A missing,
 * invalid, expired, or wrong-type (refresh used as access) token is simply left
 * unauthenticated — Spring Security's entry point then produces the standard 401
 * envelope. No exception is thrown here so it never leaks parsing detail.
 *
 * <p>The one exception: a browser's native {@code EventSource} cannot set custom
 * headers, so the SSE stream route additionally accepts a short-lived, decision-
 * scoped {@code ?token=} query parameter (docs/API/API_DESIGN.md "SSE"). This is
 * deliberately narrow — matched only on the exact stream path, and only a
 * {@code stream}-type token whose embedded decision id matches the one in the
 * URL is accepted, so it can't be reused as a general-purpose bearer token.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String TOKEN_PARAM = "token";
    private static final Pattern STREAM_PATH =
            Pattern.compile(".*/decisions/([0-9a-fA-F-]{36})/stream$");

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader(AUTH_HEADER);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            authenticateIfValid(request, token, jwtService::isAccessToken);
        } else {
            Matcher matcher = STREAM_PATH.matcher(request.getRequestURI());
            String queryToken = request.getParameter(TOKEN_PARAM);
            if (matcher.matches() && queryToken != null) {
                UUID decisionId = UUID.fromString(matcher.group(1));
                authenticateIfValid(request, queryToken, claims -> jwtService.isStreamTokenForDecision(claims, decisionId));
            }
        }

        chain.doFilter(request, response);
    }

    private void authenticateIfValid(
            HttpServletRequest request, String token, java.util.function.Predicate<Claims> tokenTypeCheck) {
        Optional<Claims> maybeClaims = jwtService.parseAndValidate(token);
        if (maybeClaims.isEmpty() || !tokenTypeCheck.test(maybeClaims.get())) {
            return;
        }
        Claims claims = maybeClaims.get();
        String userId = claims.getSubject();
        String role = claims.get("role", String.class);

        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
        var authToken = new UsernamePasswordAuthenticationToken(userId, null, authorities);
        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authToken);
    }
}
