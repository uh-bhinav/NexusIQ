package com.nexusiq.messaging;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record FindingPayload(
        String category,
        String policyName,
        String status,
        String severity,
        String title,
        String description,
        BigDecimal confidence,
        List<UUID> evidenceChunkIds) {}
