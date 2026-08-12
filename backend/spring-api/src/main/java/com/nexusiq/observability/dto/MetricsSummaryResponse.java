package com.nexusiq.observability.dto;

import java.util.Map;

public record MetricsSummaryResponse(
        long totalDecisions,
        Map<String, Long> decisionsByStatus,
        Map<String, Long> decisionsByRecommendation,
        long pendingApprovals,
        Double avgConfidence,
        Double avgCostUsd,
        Double avgLatencyMs) {}
