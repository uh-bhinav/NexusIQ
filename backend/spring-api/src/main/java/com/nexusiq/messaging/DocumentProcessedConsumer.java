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
 * Idempotent (.claude/rules/architecture.md: processed_events insert-if-absent
 * in the same transaction as the side effect). A document not found in the
 * given workspace is a real bug (the AI service only ever emits this for a
 * document Java itself created) — it is rethrown so the error handler retries
 * and eventually routes to {@code document.processed.dlq} rather than being
 * silently dropped.
 *
 * {@code @Transactional} lives on {@link #onMessage} — the method Spring Kafka
 * actually invokes through the proxy — not on a private/self-invoked helper.
 * A same-class call (e.g. {@code this.handle(...)}) bypasses the AOP proxy
 * entirely, so an @Transactional helper called that way silently runs with no
 * transaction: the repository calls each get their own auto-committed
 * mini-transaction, the fetched entity is detached the instant its own call
 * returns, and mutating it afterwards (markReady) never gets flushed anywhere.
 * Confirmed empirically: the consumer received and parsed every message and
 * committed its Kafka offset with no exception, yet the document stayed
 * UPLOADED — exactly what silent no-op dirty-checking looks like.
 */
@Component
public class DocumentProcessedConsumer {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessedConsumer.class);
    public static final String CONSUMER_GROUP = "nexusiq-api-document-processed";

    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final DocumentRepository documentRepository;
    private final AuditService auditService;
    private final TraceContextPropagation traceContext;

    public DocumentProcessedConsumer(
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

    @KafkaListener(topics = KafkaTopics.DOCUMENT_PROCESSED, groupId = CONSUMER_GROUP)
    @Transactional
    public void onMessage(String json, Acknowledgment acknowledgment) {
        EventEnvelope<DocumentProcessedPayload> envelope = objectMapper.readValue(
                json,
                TypeFactory.createDefaultInstance()
                        .constructParametricType(EventEnvelope.class, DocumentProcessedPayload.class));

        traceContext.runInSpan(
                "kafka.consume document.processed",
                envelope.traceparent(),
                Map.of(
                        "event_id", envelope.eventId().toString(),
                        "correlation_id", String.valueOf(envelope.correlationId())),
                () -> process(envelope, acknowledgment));
    }

    private void process(EventEnvelope<DocumentProcessedPayload> envelope, Acknowledgment acknowledgment) {
        if (processedEventRepository.existsByEventIdAndConsumerGroup(envelope.eventId(), CONSUMER_GROUP)) {
            log.info("Duplicate document.processed event {} — skipping (already applied)", envelope.eventId());
            acknowledgment.acknowledge();
            return;
        }

        DocumentProcessedPayload payload = envelope.payload();
        Document document = documentRepository
                .findByIdAndWorkspaceId(payload.documentId(), envelope.workspaceId())
                .orElseThrow(() -> new IllegalStateException(
                        "document.processed for unknown document " + payload.documentId()
                                + " in workspace " + envelope.workspaceId()));

        document.markReady(payload.chunkCount());
        processedEventRepository.save(new ProcessedEvent(envelope.eventId(), CONSUMER_GROUP));
        auditService.record(envelope.workspaceId(), null, "DOCUMENT_READY", "DOCUMENT", document.getId());

        acknowledgment.acknowledge();
    }
}
