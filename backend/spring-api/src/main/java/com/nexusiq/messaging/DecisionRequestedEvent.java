package com.nexusiq.messaging;

/** Internal Spring event published inside the decision-request transaction so
 * the Kafka send happens strictly after commit (.claude/rules/backend-java.md). */
public record DecisionRequestedEvent(EventEnvelope<DecisionRequestedPayload> envelope) {}
