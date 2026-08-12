package com.nexusiq.decision;

import com.nexusiq.decision.entity.AgentExecution;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentExecutionRepository extends JpaRepository<AgentExecution, UUID> {

    List<AgentExecution> findAllByDecisionRunIdOrderBySequenceIndexAsc(UUID decisionRunId);
}
