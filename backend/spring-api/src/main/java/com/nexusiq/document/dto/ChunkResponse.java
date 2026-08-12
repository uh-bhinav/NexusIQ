package com.nexusiq.document.dto;

import java.util.UUID;

/** Mirrors ai-service's ChunkResponse (docs/API/API_DESIGN.md
 * "GET .../documents/{documentId}/chunks" — citation resolution). */
public record ChunkResponse(
        UUID id,
        UUID documentId,
        int chunkIndex,
        String content,
        String section,
        String subsection,
        Integer pageNumber,
        boolean isFlagged) {}
