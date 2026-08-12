package com.nexusiq.messaging;

/**
 * Internal Spring event published inside the upload transaction so the actual
 * Kafka send can happen strictly after commit (.claude/rules/backend-java.md:
 * "never publish a Kafka event inside a transaction and assume atomicity").
 */
public record DocumentUploadedEvent(EventEnvelope<DocumentUploadedPayload> envelope) {}
