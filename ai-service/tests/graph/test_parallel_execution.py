"""Acceptance criterion 6: "Policy and risk demonstrably run in parallel
(span overlap in the trace)." Proven with a real OTel TracerProvider backed
by an in-memory exporter (app/observability/tracing.py::get_in_memory_tracer)
so this is deterministic, not a live-collector timing assumption.

A deliberate artificial delay is added to both policy_analyst's and
risk_analyzer's mock LLM calls — with a near-instant MockProvider, both
calls could complete within the same microsecond and "overlap" would be
true almost by accident. Delaying both by enough that a SEQUENTIAL
execution would show non-overlapping spans (delay > any gap between them)
makes this a real proof, not a coincidence of speed.
"""

import asyncio
import uuid
from pathlib import Path

import pytest
from langgraph.checkpoint.memory import InMemorySaver

from app.config import get_settings
from app.graph.builder import build_graph, initial_state
from app.graph.deps import GraphDeps
from app.llm.mock_provider import MockProvider
from app.models.agents import PolicyAnalysisOutput, RiskAssessment
from app.observability.tracing import get_in_memory_tracer

_FIXTURES_DIR = Path(__file__).resolve().parents[1] / "fixtures" / "llm"
_ARTIFICIAL_DELAY_S = 0.2


class _DelayedProvider(MockProvider):
    """Delays only the two schemas that run in parallel (policy_analyst,
    risk_analyzer) — intent/context_planner/decision stay instant so this
    test isn't slower than it needs to be."""

    async def generate_structured(self, *, system, user, schema, model, **kwargs):
        if schema in (PolicyAnalysisOutput, RiskAssessment):
            await asyncio.sleep(_ARTIFICIAL_DELAY_S)
        return await super().generate_structured(
            system=system, user=user, schema=schema, model=model
        )


class _NullProducer:
    async def publish_progress(self, workspace_id, correlation_id, payload):
        pass


@pytest.mark.asyncio
async def test_policyAnalystAndRiskAnalyzer_spansOverlap():
    settings = get_settings()
    provider = _DelayedProvider(_FIXTURES_DIR)
    tracer, exporter = get_in_memory_tracer()
    checkpointer = InMemorySaver()

    deps = GraphDeps(
        settings=settings,
        provider=provider,
        producer=_NullProducer(),  # type: ignore[arg-type]
        # retrieval_node is monkeypatched below to skip the DB entirely, so
        # no real session is needed for this span-timing test.
        tracer=tracer,
        workspace_id=uuid.uuid4(),
        correlation_id=None,
    )

    # retrieval_node calls execute_context_plan, which needs a real session
    # for vector_search. Rather than seed a workspace (irrelevant to this
    # test's point), monkeypatch retrieval to skip the DB entirely — this
    # test is about span timing, not retrieval correctness (covered
    # elsewhere). Must patch app.graph.builder's own name binding (it did
    # `from app.graph.nodes import retrieval_node` at import time), not
    # app.graph.nodes's — and before build_graph() so the graph captures the
    # replacement, not the original.
    import app.graph.builder as builder_module

    async def fake_retrieval_node(state, deps):
        from app.graph.instrumentation import NodeResult

        return NodeResult(state_update={"retrieved_evidence": []})

    original = builder_module.retrieval_node
    builder_module.retrieval_node = fake_retrieval_node
    try:
        graph = build_graph(deps, checkpointer)
        decision_id = str(uuid.uuid4())
        state = initial_state(
            decision_id, str(deps.workspace_id), "", "Should Vendor Alpha be approved?", "v1"
        )
        await graph.ainvoke(state, {"configurable": {"thread_id": decision_id}})
    finally:
        builder_module.retrieval_node = original

    spans = {s.name: s for s in exporter.get_finished_spans()}
    assert "policy_analyst" in spans
    assert "risk_analyzer" in spans

    policy_span = spans["policy_analyst"]
    risk_span = spans["risk_analyzer"]

    # Overlap: one span starts before the other ends.
    overlap = (
        policy_span.start_time < risk_span.end_time
        and risk_span.start_time < policy_span.end_time
    )
    assert overlap, (
        f"expected overlapping spans, got policy=[{policy_span.start_time}, "
        f"{policy_span.end_time}] risk=[{risk_span.start_time}, {risk_span.end_time}]"
    )
