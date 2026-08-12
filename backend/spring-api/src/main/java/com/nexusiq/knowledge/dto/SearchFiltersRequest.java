package com.nexusiq.knowledge.dto;

import java.util.List;

/** Sent to the AI service as-is; snake_case on the wire via the global Jackson config. */
public record SearchFiltersRequest(List<String> documentTypes) {}
