package com.nexusiq.auth.dto;

import com.nexusiq.user.dto.UserSummaryResponse;

public record AuthResponse(String accessToken, String refreshToken, long expiresIn, UserSummaryResponse user) {}
