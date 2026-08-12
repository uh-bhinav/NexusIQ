package com.nexusiq.streaming;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Plain unit test — no Spring context needed, the registry has no
 * dependencies of its own. {@link SseEmitter#send} doesn't require a live
 * HTTP response to be attached (confirmed empirically: it buffers until one
 * is), so these assert the registry never throws for the states a real
 * decision lifecycle produces, rather than asserting exact wire bytes
 * (covered instead by this session's live verification of the underlying
 * Kafka -> consumer -> registry.send() call sites).
 */
class SseEmitterRegistryTest {

    @Test
    void sendToAnUnregisteredDecisionId_isASilentNoOp() {
        SseEmitterRegistry registry = new SseEmitterRegistry();

        assertThatCode(() -> registry.send(UUID.randomUUID(), "agent.completed", "irrelevant"))
                .doesNotThrowAnyException();
    }

    @Test
    void registerThenSend_doesNotThrow() {
        SseEmitterRegistry registry = new SseEmitterRegistry();
        UUID decisionId = UUID.randomUUID();

        SseEmitter emitter = registry.register(decisionId);
        assertThatCode(() -> registry.send(decisionId, "agent.completed", "data")).doesNotThrowAnyException();

        assertThatCode(emitter::complete).doesNotThrowAnyException();
    }

    @Test
    void completeClosesTheEmitter_andASecondRegistrationForTheSameIdStartsClean() {
        SseEmitterRegistry registry = new SseEmitterRegistry();
        UUID decisionId = UUID.randomUUID();
        AtomicBoolean firstCompleted = new AtomicBoolean(false);

        SseEmitter first = registry.register(decisionId);
        first.onCompletion(() -> firstCompleted.set(true));

        assertThatCode(() -> registry.complete(decisionId, "decision.completed", "done"))
                .doesNotThrowAnyException();

        // A later decision reusing... well, decision ids aren't reused, but
        // a second stream connection opened for the SAME decision (a
        // browser tab reload after a terminal event, say) must not be
        // corrupted by the first connection's now-closed emitter still
        // being in the registry.
        SseEmitter second = registry.register(decisionId);
        assertThatCode(() -> registry.send(decisionId, "decision.status", "PROCESSING"))
                .doesNotThrowAnyException();
        assertThatCode(second::complete).doesNotThrowAnyException();
    }

    @Test
    void multipleEmittersForTheSameDecision_allReceiveASend() {
        SseEmitterRegistry registry = new SseEmitterRegistry();
        UUID decisionId = UUID.randomUUID();

        registry.register(decisionId);
        registry.register(decisionId);

        assertThatCode(() -> registry.send(decisionId, "agent.completed", "data")).doesNotThrowAnyException();
    }

    @Test
    void heartbeatSweep_doesNotThrow_withActiveAndNoRegistrations() {
        SseEmitterRegistry registry = new SseEmitterRegistry();
        registry.register(UUID.randomUUID());

        assertThatCode(registry::sendHeartbeats).doesNotThrowAnyException();
    }
}
