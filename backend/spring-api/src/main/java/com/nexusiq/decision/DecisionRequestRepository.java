package com.nexusiq.decision;

import com.nexusiq.decision.entity.DecisionRequest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DecisionRequestRepository extends JpaRepository<DecisionRequest, UUID> {

    Optional<DecisionRequest> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    Page<DecisionRequest> findAllByWorkspaceId(UUID workspaceId, Pageable pageable);
}
