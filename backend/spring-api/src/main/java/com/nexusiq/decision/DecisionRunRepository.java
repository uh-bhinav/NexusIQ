package com.nexusiq.decision;

import com.nexusiq.decision.entity.DecisionRun;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DecisionRunRepository extends JpaRepository<DecisionRun, UUID> {

    /** One run per request in Phase 5 (no retry-driven multi-run history yet). */
    Optional<DecisionRun> findFirstByDecisionRequestIdOrderByStartedAtDesc(UUID decisionRequestId);
}
