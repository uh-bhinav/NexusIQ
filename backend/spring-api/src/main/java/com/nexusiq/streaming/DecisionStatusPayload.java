package com.nexusiq.streaming;

import java.util.List;

/** {@code decision.status} (initial/reconciliation), {@code approval.required},
 * {@code decision.completed} and {@code decision.failed} all share this shape —
 * the client re-fetches full detail via {@code GET .../decisions/{id}} on any
 * terminal event anyway (docs/API/API_DESIGN.md: "never assume the stream was
 * complete"), so the SSE body only needs to carry enough to update a live
 * status indicator without a round trip. */
public record DecisionStatusPayload(String status, List<String> reasons, String failureReason) {

    public static DecisionStatusPayload status(String status) {
        return new DecisionStatusPayload(status, List.of(), null);
    }

    public static DecisionStatusPayload approvalRequired(List<String> reasons) {
        return new DecisionStatusPayload("WAITING_FOR_APPROVAL", reasons, null);
    }

    public static DecisionStatusPayload failed(String reason) {
        return new DecisionStatusPayload("FAILED", List.of(), reason);
    }
}
