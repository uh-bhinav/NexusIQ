package com.nexusiq.user.entity;

/**
 * Global user role and workspace-membership role share this enum (docs/DATABASE/SCHEMA.md).
 * Authorization always checks both: global role via @PreAuthorize, membership role via
 * WorkspaceAccessService (.claude/rules/backend-java.md).
 */
public enum Role {
    ADMIN,
    ANALYST,
    APPROVER,
    VIEWER
}
