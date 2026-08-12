package com.nexusiq.observability;

import com.nexusiq.observability.dto.MetricsSummaryResponse;
import com.nexusiq.workspace.WorkspaceAccessService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MetricsService {

    private final MetricsRepository metricsRepository;
    private final WorkspaceAccessService accessService;

    public MetricsService(MetricsRepository metricsRepository, WorkspaceAccessService accessService) {
        this.metricsRepository = metricsRepository;
        this.accessService = accessService;
    }

    @Transactional(readOnly = true)
    public MetricsSummaryResponse summary(UUID workspaceId, UUID requesterId) {
        accessService.requireMembership(workspaceId, requesterId);

        Map<String, Long> byStatus = toStringLongMap(metricsRepository.countRequestsByStatus(workspaceId));
        Map<String, Long> byRecommendation = toStringLongMap(metricsRepository.countByRecommendation(workspaceId));
        long totalDecisions = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long pendingApprovals = metricsRepository.countPendingApprovals(workspaceId);

        List<Object[]> averagesRows = metricsRepository.averages(workspaceId);
        Double avgConfidence = null;
        Double avgCostUsd = null;
        Double avgLatencyMs = null;
        if (!averagesRows.isEmpty()) {
            Object[] row = averagesRows.get(0);
            avgConfidence = toDouble(row[0]);
            avgCostUsd = toDouble(row[1]);
            avgLatencyMs = toDouble(row[2]);
        }

        return new MetricsSummaryResponse(
                totalDecisions, byStatus, byRecommendation, pendingApprovals, avgConfidence, avgCostUsd,
                avgLatencyMs);
    }

    private Map<String, Long> toStringLongMap(List<Object[]> rows) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (Object[] row : rows) {
            result.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        return result;
    }

    private Double toDouble(Object value) {
        return value != null ? ((Number) value).doubleValue() : null;
    }
}
