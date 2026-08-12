package com.nexusiq.streaming;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live {@code decision.requested}/{@code .progress}/{@code .completed}/
 * {@code .failed} events pushed to any open SSE connection for that decision
 * (docs/API/API_DESIGN.md "SSE"; roadmap Phase 9 acceptance criterion 2).
 * Keyed by {@code decision_requests.id} (the id the REST API and the URL
 * both already use), not the decision *run* id Kafka payloads carry —
 * callers resolve run id -> request id once and key on the latter here.
 *
 * <p>In-memory only, one instance per spring-api process. Fine for this
 * project's single-instance local deployment (ADR-010); a multi-instance
 * deployment would need a fan-out layer (Redis pub/sub or similar) — not
 * attempted here, out of scope for Phase 9.
 */
@Component
public class SseEmitterRegistry {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterRegistry.class);

    // Bounds how long a connection can stay open with no terminal event —
    // generous relative to WORKFLOW_TIMEOUT_SECONDS (ai-service default 300s)
    // so a slow-but-healthy run is never cut off mid-stream.
    private static final long EMITTER_TIMEOUT_MS = 15 * 60 * 1000;
    private static final long HEARTBEAT_INTERVAL_MS = 15_000;

    private final Map<UUID, List<SseEmitter>> emittersByDecisionId = new ConcurrentHashMap<>();

    public SseEmitter register(UUID decisionId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        List<SseEmitter> emitters =
                emittersByDecisionId.computeIfAbsent(decisionId, id -> new CopyOnWriteArrayList<>());
        emitters.add(emitter);

        Runnable cleanup = () -> remove(decisionId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        return emitter;
    }

    /** A non-terminal event (agent progress, approval-required, etc). The
     * connection stays open afterward. */
    public void send(UUID decisionId, String eventName, Object data) {
        emitAll(decisionId, eventName, data, false);
    }

    /** A terminal event — server closes the connection after sending it
     * (docs/API/API_DESIGN.md: "Server closes on a terminal event"). */
    public void complete(UUID decisionId, String eventName, Object data) {
        emitAll(decisionId, eventName, data, true);
    }

    private void emitAll(UUID decisionId, String eventName, Object data, boolean terminal) {
        List<SseEmitter> emitters = emittersByDecisionId.get(decisionId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : List.copyOf(emitters)) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
                if (terminal) {
                    emitter.complete();
                }
            } catch (IOException | IllegalStateException e) {
                // Client already gone (closed tab, network drop) — this is
                // the normal way a dead connection is discovered, not an
                // application error.
                emitter.completeWithError(e);
            }
        }
        if (terminal) {
            emittersByDecisionId.remove(decisionId);
        }
    }

    private void remove(UUID decisionId, SseEmitter emitter) {
        List<SseEmitter> emitters = emittersByDecisionId.get(decisionId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                emittersByDecisionId.remove(decisionId);
            }
        }
    }

    @Scheduled(fixedRate = HEARTBEAT_INTERVAL_MS)
    void sendHeartbeats() {
        for (Map.Entry<UUID, List<SseEmitter>> entry : emittersByDecisionId.entrySet()) {
            for (SseEmitter emitter : List.copyOf(entry.getValue())) {
                try {
                    emitter.send(SseEmitter.event().name("heartbeat").data(""));
                } catch (IOException | IllegalStateException e) {
                    log.debug("Heartbeat failed for decision {} — connection already gone", entry.getKey());
                    emitter.completeWithError(e);
                }
            }
        }
    }
}
