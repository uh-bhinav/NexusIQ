import uuid

import pytest

from app.config import get_settings
from app.graph.deps import GraphDeps
from app.graph.errors import WorkflowBudgetExceeded
from app.graph.instrumentation import NodeResult, instrument
from app.graph.state import DecisionState
from app.messaging.envelope import DecisionProgressPayload
from app.observability.tracing import get_in_memory_tracer


class _FakeProducer:
    def __init__(self):
        self.progress_calls: list[DecisionProgressPayload] = []

    async def publish_progress(self, workspace_id, correlation_id, payload):
        self.progress_calls.append(payload)


def _state() -> DecisionState:
    return DecisionState(
        decision_id=str(uuid.uuid4()),
        workspace_id=str(uuid.uuid4()),
        correlation_id=str(uuid.uuid4()),
        workflow_version="v1",
        question="q",
        decision_type=None,
        intent=None,
        context_plan=None,
        retrieved_evidence=[],
        policy_findings=[],
        risk_analysis=None,
        recommendation=None,
        validation_result=None,
        injection_findings=[],
        validation_feedback=None,
        iteration=0,
        requires_human_approval=False,
        escalation_reasons=[],
        total_input_tokens=0,
        total_output_tokens=0,
        estimated_cost_usd=0.0,
        errors=[],
    )


def _deps(**settings_overrides) -> tuple[GraphDeps, _FakeProducer]:
    tracer, _ = get_in_memory_tracer()
    producer = _FakeProducer()
    settings = get_settings().model_copy(update=settings_overrides)
    deps = GraphDeps(
        settings=settings,
        provider=None,  # type: ignore[arg-type]
        producer=producer,  # type: ignore[arg-type]
        tracer=tracer,
        workspace_id=uuid.uuid4(),
        correlation_id=None,
    )
    return deps, producer


@pytest.mark.asyncio
async def test_instrument_successfulNode_emitsProgressAndAccumulatesTotals():
    deps, producer = _deps()

    async def fn(state, deps):
        return NodeResult(
            state_update={"decision_type": "vendor_approval"},
            model="gemini-2.5-flash",
            input_tokens=10,
            output_tokens=20,
            estimated_cost_usd=0.001,
        )

    wrapped = instrument("intent", 0, deps, fn)
    update = await wrapped(_state())

    assert update["decision_type"] == "vendor_approval"
    assert update["total_input_tokens"] == 10
    assert update["total_output_tokens"] == 20
    assert update["estimated_cost_usd"] == 0.001
    assert len(producer.progress_calls) == 1
    assert producer.progress_calls[0].status == "SUCCESS"
    assert producer.progress_calls[0].agent_name == "intent"


@pytest.mark.asyncio
async def test_instrument_failingNode_emitsFailedProgressAndReraises():
    deps, producer = _deps()

    async def fn(state, deps):
        raise ValueError("boom")

    wrapped = instrument("intent", 0, deps, fn)
    with pytest.raises(ValueError, match="boom"):
        await wrapped(_state())

    assert len(producer.progress_calls) == 1
    assert producer.progress_calls[0].status == "FAILED"
    assert "boom" in producer.progress_calls[0].error


@pytest.mark.asyncio
async def test_instrument_costExceedsBudget_raisesWorkflowBudgetExceeded():
    deps, _ = _deps(max_workflow_cost_usd=0.01)

    async def fn(state, deps):
        return NodeResult(state_update={}, estimated_cost_usd=0.02)

    wrapped = instrument("decision", 4, deps, fn)
    with pytest.raises(WorkflowBudgetExceeded, match="cost"):
        await wrapped(_state())


@pytest.mark.asyncio
async def test_instrument_tokensExceedBudget_raisesWorkflowBudgetExceeded():
    deps, _ = _deps(max_workflow_tokens=100)

    async def fn(state, deps):
        return NodeResult(state_update={}, input_tokens=60, output_tokens=60)

    wrapped = instrument("decision", 4, deps, fn)
    with pytest.raises(WorkflowBudgetExceeded, match="tokens"):
        await wrapped(_state())


@pytest.mark.asyncio
async def test_instrument_writesOwnDeltaNotCumulativeTotal():
    """DecisionState's total_* fields are Annotated[int, operator.add] /
    Annotated[float, operator.add] (graph/state.py) specifically so two
    nodes writing them in the SAME superstep (policy_analyst,
    risk_analyzer) sum instead of colliding — LangGraph's reducer does the
    accumulation, not this wrapper. The wrapper must therefore return only
    this node's own usage, never a running total, or the reducer would
    double-count. Simulating two SEQUENTIAL calls (the reducer would sum
    5+5=10 in a real graph run) proves each call's return value is the
    delta (5), not an accumulated 5-then-10."""
    deps, _ = _deps(max_workflow_cost_usd=1.0)
    state = _state()

    async def fn(state, deps):
        return NodeResult(state_update={}, input_tokens=5, output_tokens=5, estimated_cost_usd=0.1)

    wrapped = instrument("n", 0, deps, fn)
    update1 = await wrapped(state)
    assert update1["total_input_tokens"] == 5
    assert update1["estimated_cost_usd"] == pytest.approx(0.1)

    # A real graph would apply the Annotated reducer between calls (state
    # would read total_input_tokens=5 here); simulate that explicitly since
    # this test calls the wrapper directly, bypassing LangGraph.
    state["total_input_tokens"] = 5
    state["total_output_tokens"] = 5
    state["estimated_cost_usd"] = 0.1

    update2 = await wrapped(state)
    assert update2["total_input_tokens"] == 5  # still the delta, not 10
    assert update2["estimated_cost_usd"] == pytest.approx(0.1)
