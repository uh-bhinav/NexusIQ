"""Phase 8 acceptance: "metric existence is asserted" (docs/OPERATIONS/
OBSERVABILITY.md "Verification"). Exercises every record_* function in
app/observability/metrics.py against a real MeterProvider backed by an
in-memory reader (get_in_memory_meter/set_test_instruments — the metrics
equivalent of tracing.py's get_in_memory_tracer seam) and asserts the
resulting data points exist with the labels the AI/RAG metric groups
require, rather than just asserting "it didn't raise".
"""

from collections.abc import Iterator

import pytest

from app.observability import metrics as om


@pytest.fixture
def in_memory_metrics() -> Iterator[object]:
    meter, reader = om.get_in_memory_meter()
    om.set_test_instruments(meter)
    try:
        yield reader
    finally:
        om.clear_test_instruments()


def _data_points(reader, metric_name: str) -> list:
    points = []
    for resource_metrics in reader.get_metrics_data().resource_metrics:
        for scope_metrics in resource_metrics.scope_metrics:
            for metric in scope_metrics.metrics:
                if metric.name == metric_name:
                    points.extend(metric.data.data_points)
    return points


def _names(reader) -> set[str]:
    names: set[str] = set()
    for resource_metrics in reader.get_metrics_data().resource_metrics:
        for scope_metrics in resource_metrics.scope_metrics:
            names.update(m.name for m in scope_metrics.metrics)
    return names


def test_recordAgentExecution_success_emitsDurationOnly(in_memory_metrics):
    reader = in_memory_metrics
    om.record_agent_execution("policy_analyst", 123.0, "SUCCESS")

    duration_points = _data_points(reader, "agent_duration")
    assert any(p.attributes.get("agent_name") == "policy_analyst" for p in duration_points)
    assert "agent_failure_rate" not in _names(reader)


def test_recordAgentExecution_failed_alsoIncrementsFailureRate(in_memory_metrics):
    reader = in_memory_metrics
    om.record_agent_execution("risk_analyzer", 45.0, "FAILED")

    failure_points = _data_points(reader, "agent_failure_rate")
    assert len(failure_points) == 1
    assert failure_points[0].attributes["agent_name"] == "risk_analyzer"
    assert failure_points[0].value == 1


def test_recordLlmUsage_emitsTokensAndCost_byModelAndDirection(in_memory_metrics):
    reader = in_memory_metrics
    om.record_llm_usage("gemini-2.5-flash", 100, 50, 0.002, repaired=False)

    token_points = {
        p.attributes["direction"]: p.value for p in _data_points(reader, "llm_tokens_total")
    }
    assert token_points == {"input": 100, "output": 50}

    cost_points = _data_points(reader, "llm_cost_usd_total")
    assert cost_points[0].attributes["model"] == "gemini-2.5-flash"
    assert cost_points[0].value == pytest.approx(0.002)

    # repaired=False must not touch schema_repair_rate at all.
    assert "schema_repair_rate" not in _names(reader)


def test_recordLlmUsage_repaired_incrementsSchemaRepairRate(in_memory_metrics):
    reader = in_memory_metrics
    om.record_llm_usage("gemini-2.5-flash", 10, 5, 0.0001, repaired=True)

    repair_points = _data_points(reader, "schema_repair_rate")
    assert len(repair_points) == 1
    assert repair_points[0].attributes["model"] == "gemini-2.5-flash"


def test_recordLlmError_labelsByExceptionType(in_memory_metrics):
    reader = in_memory_metrics
    om.record_llm_error("ModelTimeout")

    points = _data_points(reader, "llm_error_count")
    assert points[0].attributes["type"] == "ModelTimeout"


def test_recordDecisionConfidence_isAHistogram(in_memory_metrics):
    reader = in_memory_metrics
    om.record_decision_confidence(0.87)

    points = _data_points(reader, "decision_confidence")
    assert points[0].sum == pytest.approx(0.87)
    assert points[0].count == 1


def test_recordValidationFailure_labelsByCheck(in_memory_metrics):
    reader = in_memory_metrics
    om.record_validation_failure("COMPLETENESS")

    points = _data_points(reader, "validation_failure_rate")
    assert points[0].attributes["check"] == "COMPLETENESS"


def test_recordBudgetExceeded_incrementsCounter(in_memory_metrics):
    reader = in_memory_metrics
    om.record_budget_exceeded()
    om.record_budget_exceeded()

    points = _data_points(reader, "budget_exceeded_count")
    assert points[0].value == 2


def test_recordInjectionDetected_defaultsToOne_andSkipsZero(in_memory_metrics):
    reader = in_memory_metrics
    om.record_injection_detected()
    om.record_injection_detected(0)  # must not emit a spurious zero data point
    om.record_injection_detected(3)

    points = _data_points(reader, "injection_detected_count")
    assert points[0].value == 4


def test_recordRetrieval_duration_resultCount_similarity_emptyCount(in_memory_metrics):
    reader = in_memory_metrics
    om.record_retrieval_duration("vector_search", 42.5)
    om.record_retrieval_result_count(3)
    om.record_retrieval_similarity(0.91)
    om.record_retrieval_similarity(0.72)

    duration_points = _data_points(reader, "retrieval_duration")
    assert duration_points[0].attributes["stage"] == "vector_search"

    result_count_points = _data_points(reader, "retrieval_result_count")
    assert result_count_points[0].sum == 3

    similarity_points = _data_points(reader, "retrieval_similarity")
    assert similarity_points[0].count == 2
    assert "retrieval_empty_count" not in _names(reader)


def test_recordRetrievalResultCount_zero_incrementsEmptyCount(in_memory_metrics):
    reader = in_memory_metrics
    om.record_retrieval_result_count(0)

    empty_points = _data_points(reader, "retrieval_empty_count")
    assert empty_points[0].value == 1
