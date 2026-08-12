"""Phase 8 acceptance criterion 1 ("one trace spans HTTP -> Kafka -> AI
service -> each agent node") depends entirely on trace_context.py's
current_traceparent()/extract_context() round-tripping the W3C traceparent
string correctly across the Kafka envelope boundary — this proves that
mechanism deterministically, with a real OTel TracerProvider backed by an
in-memory exporter (app/observability/tracing.py::get_in_memory_tracer, the
same seam test_parallel_execution.py uses), not a live-collector assumption.
"""

from app.observability.trace_context import (
    current_span_context_valid,
    current_traceparent,
    extract_context,
)
from app.observability.tracing import get_in_memory_tracer


def test_currentTraceparent_none_whenNoActiveSpan():
    assert current_traceparent() is None
    assert current_span_context_valid() is False


def test_extractContext_thenNewSpan_sharesOriginatingTraceId():
    """Simulates the real cross-process flow: a "publisher" span (stands in
    for Java's HTTP request span) mints a traceparent, which travels on the
    EventEnvelope (as a plain string, per this module's docstring) to a
    "consumer" that extracts it and starts a child span. The child's trace_id
    must equal the publisher's — this is what makes them the same trace in
    Jaeger, not two unrelated ones that happen to be time-adjacent.
    """
    tracer, exporter = get_in_memory_tracer()

    with tracer.start_as_current_span("publisher.span") as publisher_span:
        traceparent = current_traceparent()
        publisher_trace_id = publisher_span.get_span_context().trace_id
        publisher_span_id = publisher_span.get_span_context().span_id

    assert traceparent is not None

    with tracer.start_as_current_span(
        "consumer.span", context=extract_context(traceparent)
    ) as consumer_span:
        consumer_trace_id = consumer_span.get_span_context().trace_id

    assert consumer_trace_id == publisher_trace_id

    finished = {s.name: s for s in exporter.get_finished_spans()}
    consumer_finished = finished["consumer.span"]
    assert consumer_finished.parent is not None
    assert consumer_finished.parent.span_id == publisher_span_id
    assert consumer_finished.parent.trace_id == publisher_trace_id


def test_extractContext_missingTraceparent_startsUnlinkedSpan():
    """A message from before this field existed, or with sampling disabled
    at the source, must not crash — it just starts its own trace rather than
    joining one that was never recorded."""
    tracer, _exporter = get_in_memory_tracer()

    with tracer.start_as_current_span("root.a") as span_a:
        trace_id_a = span_a.get_span_context().trace_id

    with tracer.start_as_current_span("root.b", context=extract_context(None)) as span_b:
        trace_id_b = span_b.get_span_context().trace_id

    assert trace_id_a != trace_id_b


def test_extractContext_blankTraceparent_fallsBackSafely():
    tracer, _exporter = get_in_memory_tracer()
    with tracer.start_as_current_span("root.c", context=extract_context("")) as span_c:
        assert span_c.get_span_context().is_valid
