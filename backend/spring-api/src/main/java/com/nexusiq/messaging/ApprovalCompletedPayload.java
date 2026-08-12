package com.nexusiq.messaging;

import java.util.UUID;

/** Payload for {@code approval.completed}. {@code decisionId} is the decision
 * run's own id — the LangGraph checkpoint thread_id on the Python side, same
 * convention as {@code DecisionRequestedPayload}/{@code DecisionCompletedPayload}. */
public record ApprovalCompletedPayload(
        UUID approvalId, UUID decisionId, String outcome, UUID resolvedBy, String notes) {}
