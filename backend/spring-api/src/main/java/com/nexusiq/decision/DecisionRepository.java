package com.nexusiq.decision;

import com.nexusiq.decision.entity.Decision;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DecisionRepository extends JpaRepository<Decision, UUID> {

    Optional<Decision> findByDecisionRunId(UUID decisionRunId);
}
