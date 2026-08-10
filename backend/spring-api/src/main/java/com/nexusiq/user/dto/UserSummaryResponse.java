package com.nexusiq.user.dto;

import com.nexusiq.user.entity.Role;
import java.util.UUID;

public record UserSummaryResponse(UUID id, String email, String name, Role role) {}
