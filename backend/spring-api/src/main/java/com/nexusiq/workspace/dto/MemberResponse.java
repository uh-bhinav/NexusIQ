package com.nexusiq.workspace.dto;

import com.nexusiq.user.entity.Role;
import java.time.Instant;
import java.util.UUID;

public record MemberResponse(UUID userId, String email, String name, Role role, Instant joinedAt) {}
