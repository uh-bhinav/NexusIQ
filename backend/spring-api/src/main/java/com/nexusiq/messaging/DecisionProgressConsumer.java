package com.nexusiq.messaging;

import com.nexusiq.decision.AgentExecutionRepository;
import com.nexusiq.decision.DecisionRunRepository;
import com.nexusiq.decision.entity.AgentExecution;
import com.nexusiq.decision.entity.AgentExecutionStatus;
import com.nexusiq.decision.entity.DecisionRun;
import com.nexusiq.messaging.entity.ProcessedEvent;
import com.nexusiq.observability.TraceContextPropagation;
import com.nexusiq.streaming.AgentEventPayload;
import com.nexusiq.streaming.SseEmitterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
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
 * Writes one {@code agent_executions} row per node completion/failure
 * (roadmap Phase 5 acceptance criterion 5). {@code @Transactional} lives on
 * {@link #onMessage} itself — a same-class call to a private helper would
 * bypass the AOP proxy and silently run without a transaction (see
 * {@link DocumentProcessedConsumer}'s Javadoc for the empirically-confirmed
 * failure mode this avoids).
 */
@Component
public class DecisionProgressConsumer {

    private static final Logger log = LoggerFactory.getLogger(DecisionProgressConsumer.class);
    public static final String CONSUMER_GROUP = "nexusiq-api-decision-progress";

    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final DecisionRunRepository decisionRunRepository;
    private final AgentExecutionRepository agentExecutionRepository;
    private final TraceContextPropagation traceContext;
    private final SseEmitterRegistry sseRegistry;

    public DecisionProgressConsumer(
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            DecisionRunRepository decisionRunRepository,
            AgentExecutionRepository agentExecutionRepository,
            TraceContextPropagation traceContext,
            SseEmitterRegistry sseRegistry) {
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.decisionRunRepository = decisionRunRepository;
        this.agentExecutionRepository = agentExecutionRepository;
        this.traceContext = traceContext;
        this.sseRegistry = sseRegistry;
    }

    @KafkaListener(topics = KafkaTopics.DECISION_PROGRESS, groupId = CONSUMER_GROUP)
    @Transactional
    public void onMessage(String json, Acknowledgment acknowledgment) {
        EventEnvelope<DecisionProgressPayload> envelope = objectMapper.readValue(
                json,
                TypeFactory.createDefaultInstance()
                        .constructParametricType(EventEnvelope.class, DecisionProgressPayload.class));

        traceContext.runInSpan(
                "kafka.consume decision.progress",
                envelope.traceparent(),
                Map.of(
                        "event_id", envelope.eventId().toString(),
                        "correlation_id", String.valueOf(envelope.correlationId()),
                        "agent_name", String.valueOf(envelope.payload().agentName())),
                () -> process(envelope, acknowledgment));
    }

    private void process(EventEnvelope<DecisionProgressPayload> envelope, Acknowledgment acknowledgment) {
        if (processedEventRepository.existsByEventIdAndConsumerGroup(envelope.eventId(), CONSUMER_GROUP)) {
            log.info("Duplicate decision.progress event {} — skipping (already applied)", envelope.eventId());
            acknowledgment.acknowledge();
            return;
        }

        DecisionProgressPayload payload = envelope.payload();
        // "decision_id" in every AI-service-originated payload is the
        // DecisionRun's own id (not the DecisionRequest's) — set once when
        // Java creates the run and echoed back unchanged, so it doubles
        // safely as the LangGraph checkpoint thread_id (a request retried
        // with a new run gets a fresh id, never resuming a stale graph).
        DecisionRun run = decisionRunRepository
                .findById(payload.decisionId())
                .orElseThrow(() -> new IllegalStateException(
                        "decision.progress for unknown decision run " + payload.decisionId()));

        String outputJson = payload.output() != null ? objectMapper.writeValueAsString(payload.output()) : null;
        Instant completedAt = Instant.now();
        Instant startedAt = completedAt.minusMillis(payload.latencyMs());

        agentExecutionRepository.save(new AgentExecution(
                run.getId(),
                payload.agentName(),
                payload.sequenceIndex(),
                AgentExecutionStatus.valueOf(payload.status()),
                payload.model(),
                payload.inputTokens(),
                payload.outputTokens(),
                payload.latencyMs(),
                payload.estimatedCostUsd() != null ? payload.estimatedCostUsd() : BigDecimal.ZERO,
                outputJson,
                payload.error(),
                payload.traceId(),
                startedAt,
                completedAt));

        processedEventRepository.save(new ProcessedEvent(envelope.eventId(), CONSUMER_GROUP));

        sseRegistry.send(
                run.getDecisionRequestId(),
                "FAILED".equals(payload.status()) ? "agent.failed" : "agent.completed",
                new AgentEventPayload(
                        payload.agentName(),
                        payload.status(),
                        payload.sequenceIndex(),
                        payload.model(),
                        payload.inputTokens(),
                        payload.outputTokens(),
                        payload.latencyMs(),
                        payload.estimatedCostUsd() != null ? payload.estimatedCostUsd() : BigDecimal.ZERO,
                        payload.error()));

        acknowledgment.acknowledge();
    }
}
