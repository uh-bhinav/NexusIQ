package com.nexusiq.knowledge.dto;

import java.util.UUID;

/** Request body sent to the AI service's POST /internal/search. */
public record SearchRequest(UUID workspaceId, String query, SearchFiltersRequest filters) {}
