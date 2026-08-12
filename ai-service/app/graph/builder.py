"""Wires the graph (docs/AI/ARCHITECTURE.md):

    START -> intent -> [unsupported -> END] | [context_planner -> retrieval
           -> (policy_analyst, risk_analyzer in parallel) -> decision
           -> validator -> [ACCEPT|ESCALATE: approval_router] | [RETRY: decision]]
           -> approval_router -> [interrupt() if HITL required, else pass through] -> END

Termination proof for the one cycle in this graph (decision <-> validator):
validator_node increments `iteration` on every RETRY and only returns RETRY
when `iteration < MAX_AGENT_ITERATIONS`; once that bound is hit it always
returns ESCALATE instead (graph/nodes.py). So the decision<->validator edge
can fire at most MAX_AGENT_ITERATIONS times before every remaining path
routes to approval_router — a bounded loop, not an unbounded one.
"""

from typing import Any

from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.graph import END, START, StateGraph

from app.graph.deps import GraphDeps
from app.graph.instrumentation import NodeFn, instrument
from app.graph.nodes import (
    approval_router_node,
    context_planner_node,
    decision_node,
    intent_node,
    policy_analyst_node,
    retrieval_node,
    risk_analyzer_node,
    unsupported_node,
    validator_node,
)
from app.graph.state import DecisionState


def _route_after_intent(state: DecisionState) -> str:
    if state["decision_type"] == "unsupported":
        return "unsupported"
    return "continue"


def _route_after_validator(state: DecisionState) -> str:
    assert state["validation_result"] is not None
    action = state["validation_result"].recommended_action
    if action == "RETRY":
        return "retry"
    return "approval_router"


def _add_node(graph: StateGraph[Any, Any, Any, Any], name: str, fn: NodeFn) -> None:
    """Centralizes one suppression instead of scattering it across every
    add_node call: LangGraph's stubs type a node's expected return as the
    full state type, but partial-dict updates (only the keys a node
    changed) are the documented, fully-supported runtime behaviour for
    TypedDict state — confirmed by ARCHITECTURE.md's own node contract
    ("nodes return only the keys they change") and by LangGraph's own
    reducer-merge semantics. This is a stub gap, not a real type error."""
    graph.add_node(name, fn)  # type: ignore[call-overload]


def build_graph(deps: GraphDeps, checkpointer: BaseCheckpointSaver[Any]) -> Any:
    graph: StateGraph[DecisionState, None, DecisionState, DecisionState] = StateGraph(
        DecisionState
    )

    _add_node(graph, "intent", instrument("intent", 0, deps, intent_node))
    _add_node(graph, "unsupported", instrument("unsupported", 1, deps, unsupported_node))
    _add_node(
        graph, "context_planner", instrument("context_planner", 1, deps, context_planner_node)
    )
    _add_node(graph, "retrieval", instrument("retrieval", 2, deps, retrieval_node))
    _add_node(
        graph, "policy_analyst", instrument("policy_analyst", 3, deps, policy_analyst_node)
    )
    _add_node(
        graph, "risk_analyzer", instrument("risk_analyzer", 3, deps, risk_analyzer_node)
    )
    _add_node(graph, "decision", instrument("decision", 4, deps, decision_node))
    _add_node(graph, "validator", instrument("validator", 5, deps, validator_node))

    # Not instrument()-wrapped — see approval_router_node's own docstring for
    # why (interrupt()'s GraphInterrupt would be misreported as a failure).
    # Must be a real `async def`, not a lambda returning a coroutine object —
    # LangGraph detects awaitable nodes via inspect.iscoroutinefunction and a
    # lambda fails that check, so the coroutine itself gets treated as the
    # node's return value instead of being awaited (confirmed empirically:
    # InvalidUpdateError "Expected dict, got <coroutine object ...>").
    async def _approval_router(state: DecisionState) -> dict[str, Any]:
        return await approval_router_node(state, deps)

    _add_node(graph, "approval_router", _approval_router)

    graph.add_edge(START, "intent")
    graph.add_conditional_edges(
        "intent",
        _route_after_intent,
        {"continue": "context_planner", "unsupported": "unsupported"},
    )
    graph.add_edge("unsupported", END)
    graph.add_edge("context_planner", "retrieval")
    graph.add_edge("retrieval", "policy_analyst")
    graph.add_edge("retrieval", "risk_analyzer")
    graph.add_edge("policy_analyst", "decision")
    graph.add_edge("risk_analyzer", "decision")
    graph.add_edge("decision", "validator")
    graph.add_conditional_edges(
        "validator",
        _route_after_validator,
        {"retry": "decision", "approval_router": "approval_router"},
    )
    graph.add_edge("approval_router", END)

    return graph.compile(checkpointer=checkpointer)


def initial_state(
    decision_id: str, workspace_id: str, correlation_id: str, question: str, workflow_version: str
) -> DecisionState:
    return DecisionState(
        decision_id=decision_id,
        workspace_id=workspace_id,
        correlation_id=correlation_id,
        workflow_version=workflow_version,
        question=question,
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
