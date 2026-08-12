package com.nexusiq.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * The one envelope shape every topic uses (.claude/rules/architecture.md).
 * {@code eventId} is the idempotency key; {@code payload} carries IDs and facts
 * only — never document text, embeddings, or prompt bodies.
 *
 * <p>{@code traceparent} (Phase 8, ADR-007) is the W3C trace-context string
 * for the span active when this event was published — explicit, like
 * {@code correlationId}, because automatic propagation does not cross a
 * Kafka broker (docs/OPERATIONS/OBSERVABILITY.md). Nullable: a message
 * published with no sampled span active (or from before this field existed)
 * simply starts a fresh trace on the consuming side.
 */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        UUID workspaceId,
        UUID correlationId,
        UUID causationId,
        String traceparent,
        T payload) {

    public static <T> EventEnvelope<T> newEvent(
            String eventType, UUID workspaceId, UUID correlationId, String traceparent, T payload) {
        return new EventEnvelope<>(
                UUID.randomUUID(), eventType, 1, Instant.now(), workspaceId, correlationId, null, traceparent,
                payload);
    }
}
