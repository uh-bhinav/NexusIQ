package com.nexusiq.workspace.dto;

import com.nexusiq.user.entity.Role;
import java.util.UUID;

/** Used in /auth/me — the workspaces the caller belongs to and their role in each. */
public record WorkspaceSummaryResponse(UUID id, String name, String slug, Role role) {}
