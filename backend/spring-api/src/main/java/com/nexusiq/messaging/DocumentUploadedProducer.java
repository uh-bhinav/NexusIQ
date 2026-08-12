package com.nexusiq.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The only writer of {@code document.uploaded}. Partition key is
 * {@code workspace_id} for tenant-ordered processing (.claude/rules/architecture.md).
 * Called only from {@link DocumentUploadedEventListener}, after the enclosing
 * transaction commits — never from inside the transactional service method.
 */
@Component
public class DocumentUploadedProducer {

    private static final Logger log = LoggerFactory.getLogger(DocumentUploadedProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public DocumentUploadedProducer(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void publish(EventEnvelope<DocumentUploadedPayload> envelope) {
        String json = objectMapper.writeValueAsString(envelope);
        kafkaTemplate
                .send(KafkaTopics.DOCUMENT_UPLOADED, envelope.workspaceId().toString(), json)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error(
                                "Failed to publish document.uploaded for event {} (document {})",
                                envelope.eventId(),
                                envelope.payload().documentId(),
                                ex);
                    }
                });
    }
}
