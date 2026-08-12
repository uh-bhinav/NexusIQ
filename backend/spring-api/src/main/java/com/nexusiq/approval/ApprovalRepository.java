package com.nexusiq.approval;

import com.nexusiq.approval.entity.Approval;
import com.nexusiq.approval.entity.ApprovalStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalRepository extends JpaRepository<Approval, UUID> {

    Optional<Approval> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    Optional<Approval> findByDecisionRunId(UUID decisionRunId);

    Page<Approval> findAllByWorkspaceIdAndStatus(UUID workspaceId, ApprovalStatus status, Pageable pageable);

    Page<Approval> findAllByWorkspaceId(UUID workspaceId, Pageable pageable);
}
