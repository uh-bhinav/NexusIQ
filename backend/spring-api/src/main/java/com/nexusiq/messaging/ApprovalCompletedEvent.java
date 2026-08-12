package com.nexusiq.messaging;

/** Internal Spring event published inside the approve/reject transaction so
 * the Kafka send happens strictly after commit (.claude/rules/backend-java.md). */
public record ApprovalCompletedEvent(EventEnvelope<ApprovalCompletedPayload> envelope) {}
