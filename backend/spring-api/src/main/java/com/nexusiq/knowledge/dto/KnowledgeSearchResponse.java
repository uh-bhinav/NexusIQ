package com.nexusiq.knowledge.dto;

import java.util.List;

public record KnowledgeSearchResponse(
        List<SearchResultResponse> results, String query, boolean cached, double latencyMs) {}
