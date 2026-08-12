package com.nexusiq.messaging;

import com.nexusiq.decision.DecisionRequestRepository;
import com.nexusiq.decision.DecisionRunRepository;
import com.nexusiq.decision.entity.DecisionRequest;
import com.nexusiq.decision.entity.DecisionRun;
import com.nexusiq.messaging.entity.ProcessedEvent;
import com.nexusiq.observability.TraceContextPropagation;
import com.nexusiq.streaming.DecisionStatusPayload;
import com.nexusiq.streaming.SseEmitterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.type.TypeFactory;

@Component
public class DecisionFailedConsumer {

    private static final Logger log = LoggerFactory.getLogger(DecisionFailedConsumer.class);
    public static final String CONSUMER_GROUP = "nexusiq-api-decision-failed";

    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final DecisionRunRepository decisionRunRepository;
    private final DecisionRequestRepository decisionRequestRepository;
    private final TraceContextPropagation traceContext;
    private final SseEmitterRegistry sseRegistry;

    public DecisionFailedConsumer(
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            DecisionRunRepository decisionRunRepository,
            DecisionRequestRepository decisionRequestRepository,
            TraceContextPropagation traceContext,
            SseEmitterRegistry sseRegistry) {
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.decisionRunRepository = decisionRunRepository;
        this.decisionRequestRepository = decisionRequestRepository;
        this.traceContext = traceContext;
        this.sseRegistry = sseRegistry;
    }

    @KafkaListener(topics = KafkaTopics.DECISION_FAILED, groupId = CONSUMER_GROUP)
    @Transactional
    public void onMessage(String json, Acknowledgment acknowledgment) {
        EventEnvelope<DecisionFailedPayload> envelope = objectMapper.readValue(
                json,
                TypeFactory.createDefaultInstance()
                        .constructParametricType(EventEnvelope.class, DecisionFailedPayload.class));

        traceContext.runInSpan(
                "kafka.consume decision.failed",
                envelope.traceparent(),
                Map.of(
                        "event_id", envelope.eventId().toString(),
                        "correlation_id", String.valueOf(envelope.correlationId())),
                () -> process(envelope, acknowledgment));
    }

    private void process(EventEnvelope<DecisionFailedPayload> envelope, Acknowledgment acknowledgment) {
        if (processedEventRepository.existsByEventIdAndConsumerGroup(envelope.eventId(), CONSUMER_GROUP)) {
            log.info("Duplicate decision.failed event {} — skipping (already applied)", envelope.eventId());
            acknowledgment.acknowledge();
            return;
        }

        DecisionFailedPayload payload = envelope.payload();
        DecisionRun run = decisionRunRepository
                .findById(payload.decisionId())
                .orElseThrow(() -> new IllegalStateException(
                        "decision.failed for unknown decision run " + payload.decisionId()));
        DecisionRequest request = decisionRequestRepository
                .findById(run.getDecisionRequestId())
                .orElseThrow(() -> new IllegalStateException(
                        "decision.failed run " + run.getId() + " has no decision_requests row"));

        run.markFailed(payload.reason());
        request.markFailed();

        // Roadmap Phase 8 acceptance criterion 5: "a forced failure surfaces
        // as an error span with the reason." This isn't a thrown exception —
        // the failure is data (the workflow itself reported it cleanly, per
        // .claude/rules/architecture.md's "LLM down → run fails cleanly with
        // a reason, never fabricated") — so the current span (opened by
        // runInSpan above) is marked ERROR explicitly rather than via
        // exception-recording.
        Span.current().setStatus(StatusCode.ERROR, payload.reason());
        Span.current().setAttribute("failure_reason", payload.reason());

        processedEventRepository.save(new ProcessedEvent(envelope.eventId(), CONSUMER_GROUP));

        sseRegistry.complete(
                request.getId(), "decision.failed", DecisionStatusPayload.failed(payload.reason()));

        acknowledgment.acknowledge();
    }
}
