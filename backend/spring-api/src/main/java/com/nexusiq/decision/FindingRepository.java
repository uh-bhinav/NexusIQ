package com.nexusiq.decision;

import com.nexusiq.decision.entity.Finding;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FindingRepository extends JpaRepository<Finding, UUID> {

    List<Finding> findAllByDecisionRunId(UUID decisionRunId);
}
