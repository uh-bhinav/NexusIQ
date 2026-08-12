package com.nexusiq.messaging;

import java.math.BigDecimal;
import java.util.UUID;

public record EvidencePayload(
        UUID documentId,
        UUID chunkId,
        String evidenceText,
        BigDecimal relevanceScore,
        String citationReference) {}
