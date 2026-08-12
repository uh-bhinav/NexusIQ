"""Explicit W3C trace-context propagation across the Kafka boundary
(ADR-007, docs/OPERATIONS/OBSERVABILITY.md: "Kafka envelope.correlation_id
← explicit; automatic propagation does not cross a broker" — the same is
true of trace context, carried the same way, as a plain string field on
EventEnvelope rather than a Kafka header). Mirrors
backend/spring-api/.../observability/TraceContextPropagation.java.
"""

from opentelemetry import context as otel_context
from opentelemetry import trace
from opentelemetry.propagate import extract, inject


def current_traceparent() -> str | None:
    """The current span's context as a W3C traceparent string, to embed in
    an outgoing EventEnvelope. None if nothing is currently tracing."""
    carrier: dict[str, str] = {}
    inject(carrier)
    return carrier.get("traceparent")


def extract_context(traceparent: str | None) -> otel_context.Context:
    """A Context carrying the remote parent span from `traceparent`, to
    attach before starting new spans so they become children of the trace
    that originated it (e.g. the Java HTTP request that published
    decision.requested). Falls back to the current context if traceparent is
    absent — a message from before this field existed, or with sampling
    disabled at the source."""
    if not traceparent:
        return otel_context.get_current()
    return extract({"traceparent": traceparent})


def current_span_context_valid() -> bool:
    """True if a real (non-no-op) span is currently active — useful for
    tests asserting propagation actually attached a parent rather than
    silently no-op'ing."""
    return trace.get_current_span().get_span_context().is_valid
