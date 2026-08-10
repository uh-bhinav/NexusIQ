package com.nexusiq.common.exception;

/**
 * Thrown for both "does not exist" and "exists but caller has no access" —
 * deliberately, so a workspace-scoped lookup never discloses another tenant's
 * resource by returning a different status (.claude/rules/security.md).
 */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
