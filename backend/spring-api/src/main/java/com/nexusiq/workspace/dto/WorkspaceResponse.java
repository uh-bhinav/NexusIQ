package com.nexusiq.workspace.dto;

import java.time.Instant;
import java.util.UUID;

public record WorkspaceResponse(
        UUID id, String name, String slug, String description, UUID createdBy, Instant createdAt, Instant updatedAt) {}
