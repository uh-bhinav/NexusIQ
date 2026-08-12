package com.nexusiq.observability;

import com.nexusiq.observability.dto.MetricsSummaryResponse;
import com.nexusiq.security.CurrentUser;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Backs the Grafana-adjacent dashboard summary (roadmap Phase 8 deliverable
 * "GET /api/v1/metrics/summary"). Workspace-scoped like every other data
 * endpoint in this system (.claude/rules/security.md) — the roadmap's
 * shorthand path omits the prefix the same way it does for /decisions. */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/metrics")
public class MetricsController {

    private final MetricsService metricsService;

    public MetricsController(MetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @GetMapping("/summary")
    public MetricsSummaryResponse summary(@PathVariable UUID workspaceId) {
        return metricsService.summary(workspaceId, CurrentUser.id());
    }
}
