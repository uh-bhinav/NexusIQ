package com.nexusiq.approval.mapper;

import com.nexusiq.approval.dto.ApprovalResponse;
import com.nexusiq.approval.entity.Approval;
import com.nexusiq.decision.entity.DecisionRequest;
import org.springframework.stereotype.Component;

@Component
public class ApprovalMapper {

    public ApprovalResponse toResponse(Approval approval, DecisionRequest request) {
        return new ApprovalResponse(
                approval.getId(),
                approval.getDecisionRunId(),
                request.getId(),
                request.getTitle(),
                approval.getStatus(),
                approval.getReasons(),
                approval.getRequestedAt(),
                approval.getResolvedBy(),
                approval.getResolvedAt(),
                approval.getResolutionNotes());
    }
}
