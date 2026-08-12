package com.nexusiq.observability;

import com.nexusiq.decision.entity.DecisionRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Read-only aggregate queries backing {@code GET .../metrics/summary}
 * (docs/OPERATIONS/OBSERVABILITY.md "Business" group). Native SQL, not JPQL —
 * decision_requests/decision_runs/decisions/approvals have no
 * {@code @ManyToOne} entity relationships between them (raw UUID foreign
 * keys only), so there's no entity graph to navigate.
 *
 * <p>Anchored on {@link DecisionRequest} only because Spring Data needs some
 * managed entity to bind the repository to — every method here is a custom
 * {@code @Query} returning raw aggregate rows, not entity-mapped results.
 * Deliberately extends the bare marker {@link Repository}, not
 * {@code JpaRepository} — this is read-only aggregation, not CRUD
 * (.claude/rules/database.md: "No SELECT *"; every query here is a targeted
 * aggregate, never a row dump).
 */
public interface MetricsRepository extends Repository<DecisionRequest, UUID> {

    @Query(
            value = "SELECT status, COUNT(*) FROM decision_requests WHERE workspace_id = :workspaceId GROUP BY status",
            nativeQuery = true)
    List<Object[]> countRequestsByStatus(@Param("workspaceId") UUID workspaceId);

    @Query(
            value =
                    """
                    SELECT d.recommendation, COUNT(*)
                    FROM decisions d
                    JOIN decision_runs r ON r.id = d.decision_run_id
                    JOIN decision_requests req ON req.id = r.decision_request_id
                    WHERE req.workspace_id = :workspaceId
                    GROUP BY d.recommendation
                    """,
            nativeQuery = true)
    List<Object[]> countByRecommendation(@Param("workspaceId") UUID workspaceId);

    @Query(
            value =
                    """
                    SELECT AVG(d.confidence), AVG(r.estimated_cost_usd), AVG(r.latency_ms)
                    FROM decisions d
                    JOIN decision_runs r ON r.id = d.decision_run_id
                    JOIN decision_requests req ON req.id = r.decision_request_id
                    WHERE req.workspace_id = :workspaceId
                    """,
            nativeQuery = true)
    List<Object[]> averages(@Param("workspaceId") UUID workspaceId);

    @Query(
            value = "SELECT COUNT(*) FROM approvals WHERE workspace_id = :workspaceId AND status = 'PENDING'",
            nativeQuery = true)
    long countPendingApprovals(@Param("workspaceId") UUID workspaceId);
}
