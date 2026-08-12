package com.nexusiq.decision;

import com.nexusiq.decision.entity.Evidence;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvidenceRepository extends JpaRepository<Evidence, UUID> {

    List<Evidence> findAllByDecisionRunId(UUID decisionRunId);
}
