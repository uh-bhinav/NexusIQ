"""Phase 7 (ADR-006): approval_router_node mirrors spring-api's ApprovalGate
to decide whether the graph suspends via interrupt(). Tested through the
full graph (build_graph + ainvoke) rather than calling the node directly —
langgraph.types.interrupt() requires an active Pregel execution context
(confirmed empirically: calling it standalone raises "Called get_config
outside of a runnable context"), so a bare unit test of the node isn't
possible the way validator_node's is.
"""

import uuid
from pathlib import Path
from typing import Any

import pytest
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.types import Command

from app.config import get_settings
from app.graph.builder import build_graph, initial_state
from app.graph.deps import GraphDeps
from app.llm.mock_provider import MockProvider
from app.llm.provider import ModelResult
from app.models.agents import PolicyAnalysisOutput, PolicyFinding, Recommendation
from app.observability.tracing import get_in_memory_tracer
from tests.graph.test_end_to_end import _seed_security_policy_workspace

_FIXTURES_DIR = Path(__file__).resolve().parents[1] / "fixtures" / "llm"


class _NullProducer:
    async def publish_progress(self, workspace_id: Any, correlation_id: Any, payload: Any) -> None:
        pass


class _OverrideRecommendationProvider(MockProvider):
    """Fixture-driven for everything except Recommendation, which a caller
    controls directly — the cleanest way to force a specific confidence
    value through the real graph without fighting real-retrieval/real-model
    unpredictability."""

    def __init__(self, fixtures_dir: Path, recommendation: Recommendation):
        super().__init__(fixtures_dir)
        self._recommendation = recommendation

    async def generate_structured(self, *, system, user, schema, model, **kwargs):  # type: ignore[no-untyped-def]
        if schema is Recommendation:
            return ModelResult(
                value=self._recommendation,
                model=f"mock-{model}",
                input_tokens=0,
                output_tokens=0,
                latency_ms=0,
                estimated_cost_usd=0.0,
                finish_reason="stop",
                repaired=False,
            )
        return await super().generate_structured(
            system=system, user=user, schema=schema, model=model
        )


class _ViolatedFindingProvider(MockProvider):
    """Forces a VIOLATED policy finding regardless of the fixture."""

    async def generate_structured(self, *, system, user, schema, model, **kwargs):  # type: ignore[no-untyped-def]
        if schema is PolicyAnalysisOutput:
            value = PolicyAnalysisOutput(
                findings=[
                    PolicyFinding(
                        policy_name="Data Residency Policy",
                        policy_reference="SP-102 §1",
                        status="VIOLATED",
                        explanation="Data stored outside approved regions.",
                        evidence_ids=["E1"],
                        confidence=0.9,
                    )
                ]
            )
            return ModelResult(
                value=value,
                model=f"mock-{model}",
                input_tokens=0,
                output_tokens=0,
                latency_ms=0,
                estimated_cost_usd=0.0,
                finish_reason="stop",
                repaired=False,
            )
        return await super().generate_structured(
            system=system, user=user, schema=schema, model=model
        )


def _recommendation(confidence: float) -> Recommendation:
    return Recommendation(
        recommendation="APPROVE",
        reasoning_summary="Findings support approval.",
        confidence=confidence,
        key_evidence_ids=[],
        required_actions=[],
        conditions=[],
        unresolved_questions=[],
    )


@pytest.mark.asyncio
async def test_fullGraph_highConfidence_completesWithoutInterrupt():
    workspace_id = await _seed_security_policy_workspace()
    settings = get_settings().model_copy(update={"retrieval_min_similarity": 0.0})
    provider = _OverrideRecommendationProvider(_FIXTURES_DIR, _recommendation(0.95))
    tracer, _ = get_in_memory_tracer()
    checkpointer = InMemorySaver()

    deps = GraphDeps(
        settings=settings,
        provider=provider,
        producer=_NullProducer(),  # type: ignore[arg-type]
        tracer=tracer,
        workspace_id=workspace_id,
        correlation_id=None,
    )
    graph = build_graph(deps, checkpointer)
    decision_id = str(uuid.uuid4())
    state = initial_state(
        decision_id,
        str(workspace_id),
        "",
        "Should Vendor Alpha be approved for EU production?",
        "v1",
    )
    final_state = await graph.ainvoke(state, {"configurable": {"thread_id": decision_id}})

    assert "__interrupt__" not in final_state
    assert final_state["recommendation"].confidence == 0.95


@pytest.mark.asyncio
async def test_fullGraph_lowConfidence_interruptsThenResumesOnApproval():
    workspace_id = await _seed_security_policy_workspace()
    settings = get_settings().model_copy(update={"retrieval_min_similarity": 0.0})
    provider = _OverrideRecommendationProvider(_FIXTURES_DIR, _recommendation(0.40))
    tracer, _ = get_in_memory_tracer()
    checkpointer = InMemorySaver()

    deps = GraphDeps(
        settings=settings,
        provider=provider,
        producer=_NullProducer(),  # type: ignore[arg-type]
        tracer=tracer,
        workspace_id=workspace_id,
        correlation_id=None,
    )
    graph = build_graph(deps, checkpointer)
    decision_id = str(uuid.uuid4())
    thread_config = {"configurable": {"thread_id": decision_id}}
    state = initial_state(
        decision_id,
        str(workspace_id),
        "",
        "Should Vendor Alpha be approved for EU production?",
        "v1",
    )
    interrupted_state = await graph.ainvoke(state, thread_config)

    assert "__interrupt__" in interrupted_state
    # Confidence=0.40 is below HITL_MIN_CONFIDENCE (0.75) — this must be the
    # trigger, not a coincidence of some other check also failing.
    assert interrupted_state["recommendation"].confidence == 0.40

    resumed_state = await graph.ainvoke(
        Command(resume={"outcome": "APPROVED", "resolved_by": "test", "notes": "ok"}),
        thread_config,
    )
    assert "__interrupt__" not in resumed_state
    assert resumed_state["recommendation"].confidence == 0.40


@pytest.mark.asyncio
async def test_fullGraph_violatedFinding_interrupts_regardlessOfConfidence():
    workspace_id = await _seed_security_policy_workspace()
    settings = get_settings().model_copy(update={"retrieval_min_similarity": 0.0})
    provider = _ViolatedFindingProvider(_FIXTURES_DIR)
    tracer, _ = get_in_memory_tracer()
    checkpointer = InMemorySaver()

    deps = GraphDeps(
        settings=settings,
        provider=provider,
        producer=_NullProducer(),  # type: ignore[arg-type]
        tracer=tracer,
        workspace_id=workspace_id,
        correlation_id=None,
    )
    graph = build_graph(deps, checkpointer)
    decision_id = str(uuid.uuid4())
    state = initial_state(
        decision_id,
        str(workspace_id),
        "",
        "Should Vendor Alpha be approved for EU production?",
        "v1",
    )
    final_state = await graph.ainvoke(state, {"configurable": {"thread_id": decision_id}})

    assert "__interrupt__" in final_state
