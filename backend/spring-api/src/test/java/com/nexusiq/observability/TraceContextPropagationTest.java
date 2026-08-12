package com.nexusiq.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Roadmap Phase 8 acceptance criterion 1 ("one trace spans HTTP -> Kafka ->
 * AI service -> each agent node") and criterion 5 ("a forced failure surfaces
 * as an error span with the reason") both depend entirely on this class's
 * inject/extract round trip. Proven here with a real OTel SDK backed by an
 * in-memory exporter (no live collector), mirroring ai-service's
 * get_in_memory_tracer()-based tests (test_parallel_execution.py,
 * test_trace_context.py) so both languages verify the identical mechanism
 * the same deterministic way.
 */
class TraceContextPropagationTest {

    private OpenTelemetry sdkWith(InMemorySpanExporter exporter) {
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
    }

    @Test
    void currentTraceparent_isNull_whenNoActiveSpan() {
        TraceContextPropagation tcp = new TraceContextPropagation(sdkWith(InMemorySpanExporter.create()));

        assertThat(tcp.currentTraceparent()).isNull();
    }

    @Test
    void currentTraceparent_thenRunInSpan_producesAChildOfTheOriginatingTrace() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        OpenTelemetry otel = sdkWith(exporter);
        TraceContextPropagation tcp = new TraceContextPropagation(otel);
        Tracer publisherTracer = otel.getTracer("publisher");

        // Simulates DecisionService.create() publishing decision.requested
        // from inside an active (Spring-auto-instrumented) HTTP request span.
        Span publisherSpan = publisherTracer.spanBuilder("http.request").startSpan();
        String traceparent;
        String publisherTraceId = publisherSpan.getSpanContext().getTraceId();
        String publisherSpanId = publisherSpan.getSpanContext().getSpanId();
        try (Scope scope = publisherSpan.makeCurrent()) {
            traceparent = tcp.currentTraceparent();
        } finally {
            publisherSpan.end();
        }

        assertThat(traceparent).isNotBlank();

        // Simulates the Kafka consumer on the other side of the broker.
        String result = tcp.runInSpan(
                "kafka.consume decision.requested",
                traceparent,
                Map.of("event_id", "abc-123"),
                () -> "handled");

        assertThat(result).isEqualTo("handled");

        SpanData consumerSpan = exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals("kafka.consume decision.requested"))
                .findFirst()
                .orElseThrow();

        assertThat(consumerSpan.getTraceId()).isEqualTo(publisherTraceId);
        assertThat(consumerSpan.getParentSpanId()).isEqualTo(publisherSpanId);
        assertThat(consumerSpan.getAttributes().get(AttributeKey.stringKey("event_id")))
                .isEqualTo("abc-123");
    }

    @Test
    void runInSpan_nullTraceparent_startsAnUnlinkedRootSpan_ratherThanCrashing() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        TraceContextPropagation tcp = new TraceContextPropagation(sdkWith(exporter));

        String result = tcp.runInSpan("kafka.consume x", null, Map.of(), () -> "ok");

        assertThat(result).isEqualTo("ok");
        SpanData span = exporter.getFinishedSpanItems().get(0);
        assertThat(span.getParentSpanId()).isEqualTo("0000000000000000");
    }

    @Test
    void runInSpan_exceptionEscapes_markedErrorAndRethrown() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        TraceContextPropagation tcp = new TraceContextPropagation(sdkWith(exporter));
        RuntimeException boom = new RuntimeException("workflow timeout");

        assertThatThrownBy(() -> tcp.runInSpan("kafka.consume decision.requested", null, Map.of(), () -> {
                    throw boom;
                }))
                .isSameAs(boom);

        SpanData span = exporter.getFinishedSpanItems().get(0);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getStatus().getDescription()).isEqualTo("workflow timeout");
        assertThat(span.getEvents()).anyMatch(e -> e.getName().equals("exception"));
    }
}
