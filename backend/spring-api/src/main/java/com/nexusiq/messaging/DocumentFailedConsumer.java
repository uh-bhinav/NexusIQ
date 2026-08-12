package com.nexusiq.messaging;

import com.nexusiq.audit.AuditService;
import com.nexusiq.document.DocumentRepository;
import com.nexusiq.document.entity.Document;
import com.nexusiq.messaging.entity.ProcessedEvent;
import com.nexusiq.observability.TraceContextPropagation;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.type.TypeFactory;

/**
 * Mirrors {@link DocumentProcessedConsumer}'s idempotency and error-routing
 * shape — including keeping {@code @Transactional} on {@link #onMessage}
 * itself rather than a self-invoked helper (see that class's Javadoc for why).
 */
@Component
public class DocumentFailedConsumer {

    private static final Logger log = LoggerFactory.getLogger(DocumentFailedConsumer.class);
    public static final String CONSUMER_GROUP = "nexusiq-api-document-failed";

    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final DocumentRepository documentRepository;
    private final AuditService auditService;
    private final TraceContextPropagation traceContext;

    public DocumentFailedConsumer(
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            DocumentRepository documentRepository,
            AuditService auditService,
            TraceContextPropagation traceContext) {
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.documentRepository = documentRepository;
        this.auditService = auditService;
        this.traceContext = traceContext;
    }

    @KafkaListener(topics = KafkaTopics.DOCUMENT_FAILED, groupId = CONSUMER_GROUP)
    @Transactional
    public void onMessage(String json, Acknowledgment acknowledgment) {
        EventEnvelope<DocumentFailedPayload> envelope = objectMapper.readValue(
                json,
                TypeFactory.createDefaultInstance()
                        .constructParametricType(EventEnvelope.class, DocumentFailedPayload.class));

        traceContext.runInSpan(
                "kafka.consume document.failed",
                envelope.traceparent(),
                Map.of(
                        "event_id", envelope.eventId().toString(),
                        "correlation_id", String.valueOf(envelope.correlationId())),
                () -> process(envelope, acknowledgment));
    }

    private void process(EventEnvelope<DocumentFailedPayload> envelope, Acknowledgment acknowledgment) {
        if (processedEventRepository.existsByEventIdAndConsumerGroup(envelope.eventId(), CONSUMER_GROUP)) {
            log.info("Duplicate document.failed event {} — skipping (already applied)", envelope.eventId());
            acknowledgment.acknowledge();
            return;
        }

        DocumentFailedPayload payload = envelope.payload();
        Document document = documentRepository
                .findByIdAndWorkspaceId(payload.documentId(), envelope.workspaceId())
                .orElseThrow(() -> new IllegalStateException(
                        "document.failed for unknown document " + payload.documentId()
                                + " in workspace " + envelope.workspaceId()));

        document.markFailed(payload.reason());
        processedEventRepository.save(new ProcessedEvent(envelope.eventId(), CONSUMER_GROUP));
        auditService.record(envelope.workspaceId(), null, "DOCUMENT_FAILED", "DOCUMENT", document.getId());

        acknowledgment.acknowledge();
    }
}
