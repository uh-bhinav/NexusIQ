package com.nexusiq.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Explicit W3C trace-context propagation across the Kafka boundary
 * (ADR-007, docs/OPERATIONS/OBSERVABILITY.md: "Kafka envelope.correlation_id
 * ← explicit; automatic propagation does not cross a broker" — the same is
 * true of trace context, which this class carries the same way, as a plain
 * string field on {@link com.nexusiq.messaging.EventEnvelope} rather than a
 * Kafka header, matching how correlation_id already travels).
 *
 * <p>Spring's Micrometer/OTel bridge auto-instruments HTTP requests, so
 * {@link #currentTraceparent()} called from inside a request thread (e.g.
 * DecisionService.create()) captures that request's real trace id with no
 * extra wiring — the Kafka producer never has to construct a span itself,
 * only read the one already active.
 */
@Component
public class TraceContextPropagation {

    private static final TextMapSetter<Map<String, String>> SETTER = Map::put;
    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    private final OpenTelemetry openTelemetry;
    private final Tracer tracer;

    public TraceContextPropagation(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
        this.tracer = openTelemetry.getTracer("nexusiq-spring-api");
    }

    /** The current span's context as a W3C traceparent string, to embed in an
     * outgoing EventEnvelope. Null if nothing is currently tracing (e.g. no
     * sampled span is active) — callers must treat that as "no context to
     * propagate", not an error. */
    public String currentTraceparent() {
        Map<String, String> carrier = new HashMap<>();
        openTelemetry.getPropagators().getTextMapPropagator().inject(Context.current(), carrier, SETTER);
        return carrier.get("traceparent");
    }

    /** Runs {@code work} inside a new span that is a child of the trace
     * carried by {@code traceparent} (falls back to a fresh root span if
     * null/blank — a message from before this phase shipped, or with
     * sampling disabled at the source). Marks the span ERROR and records the
     * exception on failure (roadmap Phase 8 acceptance criterion 5: "a
     * forced failure surfaces as an error span with the reason"), then
     * re-throws — this never swallows the caller's own error handling. */
    public <T> T runInSpan(String spanName, String traceparent, Map<String, String> attributes, Supplier<T> work) {
        Context parent = extract(traceparent);
        Span span = tracer.spanBuilder(spanName).setParent(parent).startSpan();
        attributes.forEach(span::setAttribute);
        try (Scope scope = span.makeCurrent()) {
            return work.get();
        } catch (RuntimeException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }

    /** {@code void}-returning convenience for Kafka {@code onMessage} handlers. */
    public void runInSpan(String spanName, String traceparent, Map<String, String> attributes, Runnable work) {
        runInSpan(spanName, traceparent, attributes, () -> {
            work.run();
            return null;
        });
    }

    private Context extract(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return Context.current();
        }
        Map<String, String> carrier = Map.of("traceparent", traceparent);
        return openTelemetry.getPropagators().getTextMapPropagator().extract(Context.current(), carrier, GETTER);
    }
}
