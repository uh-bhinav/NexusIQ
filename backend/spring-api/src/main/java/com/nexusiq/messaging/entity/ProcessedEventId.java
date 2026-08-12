package com.nexusiq.messaging.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** {@link jakarta.persistence.IdClass} for {@link ProcessedEvent}'s composite key. */
public class ProcessedEventId implements Serializable {

    private UUID eventId;
    private String consumerGroup;

    public ProcessedEventId() {
        // JPA
    }

    public ProcessedEventId(UUID eventId, String consumerGroup) {
        this.eventId = eventId;
        this.consumerGroup = consumerGroup;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProcessedEventId that)) return false;
        return Objects.equals(eventId, that.eventId) && Objects.equals(consumerGroup, that.consumerGroup);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId, consumerGroup);
    }
}
