package com.nexusiq.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The only writer of {@code decision.requested}. Partition key is
 * {@code decision_id}, not {@code workspace_id} — per-decision ordering is
 * what matters here (.claude/rules/architecture.md: "decision_id where
 * per-decision ordering matters"), since decision.progress/completed/failed
 * for the same run must never be processed out of order relative to it.
 */
@Component
public class DecisionRequestedProducer {

    private static final Logger log = LoggerFactory.getLogger(DecisionRequestedProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public DecisionRequestedProducer(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void publish(EventEnvelope<DecisionRequestedPayload> envelope) {
        String json = objectMapper.writeValueAsString(envelope);
        kafkaTemplate
                .send(KafkaTopics.DECISION_REQUESTED, envelope.payload().decisionId().toString(), json)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error(
                                "Failed to publish decision.requested for event {} (decision {})",
                                envelope.eventId(),
                                envelope.payload().decisionId(),
                                ex);
                    }
                });
    }
}
