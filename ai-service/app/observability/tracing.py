"""OTel wiring (ADR-007, docs/OPERATIONS/OBSERVABILITY.md). Originally built
in Phase 5 just for node span overlap; Phase 8 adds the collector's real
Jaeger/Prometheus fan-out and registers the configured `TracerProvider`
globally (`trace.set_tracer_provider`) so that call sites which don't hold a
`GraphDeps` — retrieval, the LLM provider — can still get a real tracer via
`trace.get_tracer(__name__)` instead of every module needing an explicit
`Tracer` threaded through its signature. Nodes keep using `deps.tracer`
(the same underlying provider) unchanged."""

from functools import lru_cache

from opentelemetry import trace
from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk.resources import SERVICE_NAME, Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor, SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from app.config import Settings, get_settings


@lru_cache
def _configure_provider(otlp_endpoint: str, service_name: str) -> TracerProvider:
    provider = TracerProvider(resource=Resource.create({SERVICE_NAME: service_name}))
    provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter(endpoint=otlp_endpoint)))
    trace.set_tracer_provider(provider)
    return provider


def get_tracer(settings: Settings | None = None) -> trace.Tracer:
    settings = settings or get_settings()
    provider = _configure_provider(settings.otel_exporter_otlp_endpoint, settings.otel_service_name)
    return provider.get_tracer(settings.otel_service_name)


def get_in_memory_tracer() -> tuple[trace.Tracer, InMemorySpanExporter]:
    """For tests: a real TracerProvider backed by an in-memory exporter, so
    span overlap (start/end timestamps) can be asserted deterministically
    without a live collector — see tests/graph/test_parallel_execution.py.
    Uses SimpleSpanProcessor (synchronous, exports on span end), not
    BatchSpanProcessor (production's choice, in get_tracer() above) — a
    batch processor exports on its own timer/queue-size trigger, so a test
    reading exporter.get_finished_spans() immediately after the graph
    returns would race it and see nothing, confirmed empirically."""
    exporter = InMemorySpanExporter()
    provider = TracerProvider(resource=Resource.create({SERVICE_NAME: "test"}))
    provider.add_span_processor(SimpleSpanProcessor(exporter))
    return provider.get_tracer("test"), exporter
