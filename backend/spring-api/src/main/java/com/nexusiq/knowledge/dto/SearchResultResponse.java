package com.nexusiq.knowledge.dto;

import java.util.UUID;

/** Mirrors ai-service's RetrievalResult (docs/AI/RAG.md "Result contract"). */
public record SearchResultResponse(
        UUID chunkId,
        UUID documentId,
        String documentName,
        String documentType,
        int documentVersion,
        boolean isCurrent,
        String section,
        String subsection,
        Integer pageNumber,
        String content,
        double similarityScore,
        Double rerankScore,
        String trustLevel,
        boolean isFlagged,
        String citationReference) {}
