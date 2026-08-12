package com.nexusiq.decision.dto;

import com.nexusiq.decision.entity.DecisionPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateDecisionRequest(
        @NotBlank @Size(max = 500) String title,
        @NotBlank String question,
        DecisionPriority priority) {}
