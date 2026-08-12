package com.nexusiq.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The only writer of {@code approval.completed}. Partition key is
 * {@code decision_id} (the run's own id), matching every other decision-scoped
 * topic — per-decision ordering, not per-workspace.
 */
@Component
public class ApprovalCompletedProducer {

    private static final Logger log = LoggerFactory.getLogger(ApprovalCompletedProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public ApprovalCompletedProducer(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void publish(EventEnvelope<ApprovalCompletedPayload> envelope) {
        String json = objectMapper.writeValueAsString(envelope);
        kafkaTemplate
                .send(KafkaTopics.APPROVAL_COMPLETED, envelope.payload().decisionId().toString(), json)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error(
                                "Failed to publish approval.completed for event {} (decision {})",
                                envelope.eventId(),
                                envelope.payload().decisionId(),
                                ex);
                    }
                });
    }
}
