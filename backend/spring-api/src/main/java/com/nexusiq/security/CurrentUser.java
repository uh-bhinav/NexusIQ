package com.nexusiq.security;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Reads the authenticated principal set by JwtAuthenticationFilter. Used only at
 * the controller boundary — services take the caller's id/role as explicit
 * parameters so they stay testable without a security context
 * (.claude/rules/backend-java.md).
 */
public final class CurrentUser {

    private CurrentUser() {}

    public static UUID id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new IllegalStateException("No authenticated user in the security context");
        }
        return UUID.fromString(auth.getName());
    }
}
