package com.nexusiq.decision.dto;

import com.nexusiq.decision.entity.FindingCategory;
import com.nexusiq.decision.entity.FindingSeverity;
import com.nexusiq.decision.entity.FindingStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record FindingResponse(
        UUID id,
        FindingCategory category,
        String policyName,
        FindingStatus status,
        FindingSeverity severity,
        String title,
        String description,
        BigDecimal confidence,
        List<UUID> evidenceIds) {}
