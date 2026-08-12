package com.nexusiq.messaging;

import com.nexusiq.messaging.entity.ProcessedEvent;
import com.nexusiq.messaging.entity.ProcessedEventId;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEventId> {

    boolean existsByEventIdAndConsumerGroup(UUID eventId, String consumerGroup);
}
