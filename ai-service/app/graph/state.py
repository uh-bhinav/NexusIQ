"""The one state object that flows between nodes (docs/AI/ARCHITECTURE.md).
Nodes return only the keys they change and never mutate in place — no
module-level mutable state, no globals (.claude/rules/ai-service.md)."""

import operator
from typing import Annotated, Literal, TypedDict

from app.models.agents import (
    ContextPlan,
    InjectionFinding,
    IntentAnalysis,
    Recommendation,
    RiskAssessment,
    ValidationResult,
)
from app.models.retrieval import RetrievalResult


class NodeError(TypedDict):
    node: str
    message: str
    occurred_at: str


class PolicyFindingDict(TypedDict):
    """Plain-dict mirror of PolicyFinding for state storage — LangGraph
    checkpoints state via serialization, and TypedDict/dict round-trips more
    predictably through that than a Pydantic model does across versions."""

    policy_name: str
    policy_reference: str
    status: Literal["SATISFIED", "PARTIALLY_SATISFIED", "VIOLATED", "UNKNOWN"]
    explanation: str
    evidence_ids: list[str]
    confidence: float


class DecisionState(TypedDict):
    # identity / tracing
    decision_id: str
    workspace_id: str
    correlation_id: str
    workflow_version: str

    # input
    question: str
    decision_type: str | None

    # per-node output
    intent: IntentAnalysis | None
    context_plan: ContextPlan | None
    retrieved_evidence: list[RetrievalResult]
    policy_findings: list[PolicyFindingDict]
    risk_analysis: RiskAssessment | None
    recommendation: Recommendation | None
    validation_result: ValidationResult | None
    injection_findings: list[InjectionFinding]
    # Set only on a validator-forced retry, so decision_node can address the
    # specific failure in its next attempt instead of repeating itself
    # verbatim. None on the first attempt and after an accepted/escalated
    # run — nothing to feed back.
    validation_feedback: str | None

    # control
    iteration: int
    requires_human_approval: bool
    escalation_reasons: list[str]

    # budget accounting. Annotated with operator.add: policy_analyst and
    # risk_analyzer run in the same superstep and both write these keys —
    # without a reducer, LangGraph's default LastValue channel raises
    # InvalidUpdateError ("Can receive only one value per step") the moment
    # two parallel nodes touch the same key. Confirmed by hitting exactly
    # this error empirically. Nodes write their OWN delta, not a running
    # total (instrumentation.py) — the reducer does the summing.
    total_input_tokens: Annotated[int, operator.add]
    total_output_tokens: Annotated[int, operator.add]
    estimated_cost_usd: Annotated[float, operator.add]
    errors: list[NodeError]
