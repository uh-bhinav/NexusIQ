package com.nexusiq.messaging.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The idempotency record for exactly-once side effects across every Kafka
 * consumer, Java or Python (.claude/rules/architecture.md). One row per
 * (event_id, consumer_group), inserted in the same transaction as the side
 * effect it guards.
 */
@Entity
@Table(name = "processed_events")
@IdClass(ProcessedEventId.class)
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Id
    @Column(name = "consumer_group", nullable = false, length = 200)
    private String consumerGroup;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
        // JPA
    }

    public ProcessedEvent(UUID eventId, String consumerGroup) {
        this.eventId = eventId;
        this.consumerGroup = consumerGroup;
    }

    @PrePersist
    void onCreate() {
        this.processedAt = Instant.now();
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
