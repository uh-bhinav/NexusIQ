"""OTel metrics SDK wiring for the AI and RAG metric groups
(docs/OPERATIONS/OBSERVABILITY.md "Metrics"). Business metrics
(decisions_processed_total, approval_turnaround_seconds, ...) are Java's
(Micrometer) — Python owns retrieval and agent-execution telemetry only,
matching the ownership split in .claude/rules/architecture.md.

Same "values now, real backend later" call sites this project has used since
Phase 2 (retrieval/metrics.py, guardrails/metrics.py) — this module doesn't
replace their structured log lines, it adds a real metric recorded
alongside each one, per the module docstrings' own stated plan.

Uses the OTLP gRPC exporter against the same collector endpoint as tracing
(the collector's `otlp` receiver serves traces, metrics and logs on one
port; see infrastructure/docker/otel/collector-config.yaml).
"""

from functools import lru_cache

from opentelemetry import metrics
from opentelemetry.exporter.otlp.proto.grpc.metric_exporter import OTLPMetricExporter
from opentelemetry.metrics import Counter, Histogram, Meter
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import (
    InMemoryMetricReader,
    PeriodicExportingMetricReader,
)
from opentelemetry.sdk.resources import SERVICE_NAME, Resource

from app.config import Settings, get_settings


@lru_cache
def _configure_provider(otlp_endpoint: str, service_name: str) -> MeterProvider:
    reader = PeriodicExportingMetricReader(OTLPMetricExporter(endpoint=otlp_endpoint))
    provider = MeterProvider(
        resource=Resource.create({SERVICE_NAME: service_name}), metric_readers=[reader]
    )
    metrics.set_meter_provider(provider)
    return provider


class _Instruments:
    """One instrument per metric name in docs/OPERATIONS/OBSERVABILITY.md's
    AI and RAG groups. Built once per process against a Meter — instruments,
    unlike spans, are meant to be long-lived. Takes a Meter directly (rather
    than resolving one from global state itself) so tests can build an
    identical instrument set against an in-memory reader; see
    get_in_memory_meter() below."""

    def __init__(self, meter: Meter):
        self.agent_duration: Histogram = meter.create_histogram(
            "agent_duration", unit="ms", description="Agent node execution latency"
        )
        self.agent_failure_rate: Counter = meter.create_counter(
            "agent_failure_rate", description="Agent node failures, by agent_name"
        )
        self.llm_tokens_total: Counter = meter.create_counter(
            "llm_tokens_total", description="LLM tokens consumed, by model and direction"
        )
        self.llm_cost_usd_total: Counter = meter.create_counter(
            "llm_cost_usd_total", unit="usd", description="Estimated LLM spend, by model"
        )
        self.llm_error_count: Counter = meter.create_counter(
            "llm_error_count", description="LLM call failures, by error type"
        )
        self.decision_confidence: Histogram = meter.create_histogram(
            "decision_confidence", description="Recommendation confidence at decision_node"
        )
        self.validation_failure_rate: Counter = meter.create_counter(
            "validation_failure_rate", description="Validator check failures, by check"
        )
        self.schema_repair_rate: Counter = meter.create_counter(
            "schema_repair_rate", description="LLM calls needing the one-shot repair retry"
        )
        self.budget_exceeded_count: Counter = meter.create_counter(
            "budget_exceeded_count", description="Workflow runs stopped by cost/token budget"
        )
        self.injection_detected_count: Counter = meter.create_counter(
            "injection_detected_count", description="PROMPT_INJECTION_ATTEMPT findings raised"
        )

        self.retrieval_duration: Histogram = meter.create_histogram(
            "retrieval_duration", unit="ms", description="Retrieval pipeline latency, by stage"
        )
        self.retrieval_result_count: Histogram = meter.create_histogram(
            "retrieval_result_count", description="Results returned per retrieval call"
        )
        self.retrieval_similarity: Histogram = meter.create_histogram(
            "retrieval_similarity", description="Per-result cosine similarity score"
        )
        self.retrieval_empty_count: Counter = meter.create_counter(
            "retrieval_empty_count", description="Retrieval calls returning zero results"
        )


@lru_cache
def _real_instruments(otlp_endpoint: str, service_name: str) -> _Instruments:
    _configure_provider(otlp_endpoint, service_name)
    return _Instruments(metrics.get_meter(service_name))


_test_override: _Instruments | None = None


def _get(settings: Settings | None = None) -> _Instruments:
    if _test_override is not None:
        return _test_override
    settings = settings or get_settings()
    return _real_instruments(settings.otel_exporter_otlp_endpoint, settings.otel_service_name)


def get_in_memory_meter() -> tuple[Meter, InMemoryMetricReader]:
    """For tests: a real MeterProvider backed by an in-memory reader, so
    metric existence and recorded values can be asserted deterministically
    without a live collector. Mirrors tracing.py's get_in_memory_tracer()."""
    reader = InMemoryMetricReader()
    provider = MeterProvider(
        resource=Resource.create({SERVICE_NAME: "test"}), metric_readers=[reader]
    )
    return provider.get_meter("test"), reader


def set_test_instruments(meter: Meter) -> None:
    """Test-only: route every record_* call in this module to instruments
    built against `meter` instead of the real OTLP-backed provider. A setter
    rather than a factory (like get_in_memory_tracer()) because record_*
    call sites across the codebase don't take a meter parameter — call
    clear_test_instruments() in a fixture teardown/finally."""
    global _test_override
    _test_override = _Instruments(meter)


def clear_test_instruments() -> None:
    global _test_override
    _test_override = None


def record_agent_execution(agent_name: str, duration_ms: float, status: str) -> None:
    inst = _get()
    inst.agent_duration.record(duration_ms, {"agent_name": agent_name})
    if status == "FAILED":
        inst.agent_failure_rate.add(1, {"agent_name": agent_name})


def record_llm_usage(
    model: str, input_tokens: int, output_tokens: int, estimated_cost_usd: float, repaired: bool
) -> None:
    inst = _get()
    inst.llm_tokens_total.add(input_tokens, {"model": model, "direction": "input"})
    inst.llm_tokens_total.add(output_tokens, {"model": model, "direction": "output"})
    inst.llm_cost_usd_total.add(estimated_cost_usd, {"model": model})
    if repaired:
        inst.schema_repair_rate.add(1, {"model": model})


def record_llm_error(error_type: str) -> None:
    _get().llm_error_count.add(1, {"type": error_type})


def record_decision_confidence(confidence: float) -> None:
    _get().decision_confidence.record(confidence)


def record_validation_failure(check: str) -> None:
    _get().validation_failure_rate.add(1, {"check": check})


def record_budget_exceeded() -> None:
    _get().budget_exceeded_count.add(1)


def record_injection_detected(count: int = 1) -> None:
    if count:
        _get().injection_detected_count.add(count)


def record_retrieval_duration(stage: str, duration_ms: float) -> None:
    _get().retrieval_duration.record(duration_ms, {"stage": stage})


def record_retrieval_result_count(count: int) -> None:
    inst = _get()
    inst.retrieval_result_count.record(count)
    if count == 0:
        inst.retrieval_empty_count.add(1)


def record_retrieval_similarity(score: float) -> None:
    _get().retrieval_similarity.record(score)
