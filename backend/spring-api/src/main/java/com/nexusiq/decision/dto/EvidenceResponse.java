package com.nexusiq.decision.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record EvidenceResponse(
        UUID id,
        UUID documentId,
        UUID chunkId,
        String evidenceText,
        BigDecimal relevanceScore,
        String citationReference) {}
