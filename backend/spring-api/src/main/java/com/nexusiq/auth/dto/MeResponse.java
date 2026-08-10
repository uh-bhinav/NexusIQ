package com.nexusiq.auth.dto;

import com.nexusiq.user.dto.UserSummaryResponse;
import com.nexusiq.workspace.dto.WorkspaceSummaryResponse;
import java.util.List;

public record MeResponse(UserSummaryResponse user, List<WorkspaceSummaryResponse> workspaces) {}
