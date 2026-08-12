"""Per-node instrumentation (docs/AI/ARCHITECTURE.md: "Every node is wrapped
by a decorator that opens an OTel span, times the call, captures tokens and
cost from the ModelProvider, catches and classifies errors, and emits a
decision.progress Kafka event."). Node functions return a NodeResult instead
of a bare dict so this wrapper never has to guess which state keys hold
usage data — that would be a silent way for a new node to be instrumented
incorrectly."""

import time
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any

from app.graph.deps import GraphDeps
from app.graph.errors import WorkflowBudgetExceeded
from app.graph.state import DecisionState
from app.llm.errors import ModelError
from app.messaging.envelope import DecisionProgressPayload
from app.observability.metrics import (
    record_agent_execution,
    record_budget_exceeded,
    record_llm_error,
    record_llm_usage,
)

NodeFn = Callable[[DecisionState], Awaitable[dict[str, Any]]]


@dataclass
class NodeResult:
    state_update: dict[str, Any]
    model: str | None = None
    input_tokens: int = 0
    output_tokens: int = 0
    estimated_cost_usd: float = 0.0
    output_summary: dict[str, Any] | None = None
    status: str = "SUCCESS"
    repaired: bool = False


AgentFn = Callable[[DecisionState, GraphDeps], Awaitable[NodeResult]]


def instrument(agent_name: str, sequence_index: int, deps: GraphDeps, fn: AgentFn) -> NodeFn:
    async def wrapped(state: DecisionState) -> dict[str, Any]:
        start = time.monotonic()
        with deps.tracer.start_as_current_span(agent_name) as span:
            # trace_id read here (not after the `with` exits) because the
            # span context is only valid while the span is current/open.
            trace_id = format(span.get_span_context().trace_id, "032x")
            try:
                result = await fn(state, deps)
            except Exception as e:
                # No explicit span.record_exception/set_status call here:
                # start_as_current_span's context manager already does
                # both automatically when an exception escapes the `with`
                # block (record_exception=True, set_status_on_exception=True
                # are its defaults) — satisfies roadmap AC5 ("a forced
                # failure surfaces as an error span with reason") for
                # every exception-based node failure without extra code.
                latency_ms = int((time.monotonic() - start) * 1000)
                record_agent_execution(agent_name, latency_ms, "FAILED")
                if isinstance(e, ModelError):
                    record_llm_error(type(e).__name__)
                await deps.producer.publish_progress(
                    deps.workspace_id,
                    deps.correlation_id,
                    DecisionProgressPayload(
                        decision_id=uuid.UUID(state["decision_id"]),
                        agent_name=agent_name,
                        sequence_index=sequence_index,
                        status="FAILED",
                        latency_ms=latency_ms,
                        error=str(e)[:500],
                        trace_id=trace_id,
                    ),
                )
                raise

            span.set_attribute("agent.name", agent_name)
            span.set_attribute("agent.sequence_index", sequence_index)
            span.set_attribute("agent.status", result.status)
            if result.model:
                span.set_attribute("llm.model", result.model)
                span.set_attribute("llm.input_tokens", result.input_tokens)
                span.set_attribute("llm.output_tokens", result.output_tokens)
                span.set_attribute("llm.estimated_cost_usd", result.estimated_cost_usd)

        latency_ms = int((time.monotonic() - start) * 1000)
        record_agent_execution(agent_name, latency_ms, result.status)
        if result.model:
            record_llm_usage(
                result.model,
                result.input_tokens,
                result.output_tokens,
                result.estimated_cost_usd,
                result.repaired,
            )
        await deps.producer.publish_progress(
            deps.workspace_id,
            deps.correlation_id,
            DecisionProgressPayload(
                decision_id=uuid.UUID(state["decision_id"]),
                agent_name=agent_name,
                sequence_index=sequence_index,
                status=result.status,
                model=result.model,
                input_tokens=result.input_tokens,
                output_tokens=result.output_tokens,
                latency_ms=latency_ms,
                estimated_cost_usd=result.estimated_cost_usd,
                output=result.output_summary,
                trace_id=trace_id,
            ),
        )

        # Projected total = everything accumulated going into this superstep
        # plus this node's own usage. When two nodes run in the same
        # superstep (policy_analyst, risk_analyzer), each checks against the
        # SAME incoming total independently — a combined overage from both
        # together is caught at the next node (decision) once the reducer
        # below has summed both deltas, not synchronously mid-superstep.
        # That's an accepted, honest limitation of checking before the
        # reducer runs, not a silent gap: nothing proceeds past `decision`
        # without the combined total having been checked at least once.
        projected_input_tokens = state["total_input_tokens"] + result.input_tokens
        projected_output_tokens = state["total_output_tokens"] + result.output_tokens
        projected_cost = state["estimated_cost_usd"] + result.estimated_cost_usd
        projected_total_tokens = projected_input_tokens + projected_output_tokens

        if projected_cost > deps.settings.max_workflow_cost_usd:
            record_budget_exceeded()
            raise WorkflowBudgetExceeded(
                f"estimated_cost_usd={projected_cost:.4f} exceeds "
                f"MAX_WORKFLOW_COST_USD={deps.settings.max_workflow_cost_usd} after {agent_name}"
            )
        if projected_total_tokens > deps.settings.max_workflow_tokens:
            record_budget_exceeded()
            raise WorkflowBudgetExceeded(
                f"total_tokens={projected_total_tokens} exceeds "
                f"MAX_WORKFLOW_TOKENS={deps.settings.max_workflow_tokens} after {agent_name}"
            )

        # Write only this node's own delta — DecisionState's
        # Annotated[int, operator.add] reducer performs the accumulation,
        # which is what makes two parallel nodes writing these keys in the
        # same superstep valid instead of an InvalidUpdateError.
        update = dict(result.state_update)
        update["total_input_tokens"] = result.input_tokens
        update["total_output_tokens"] = result.output_tokens
        update["estimated_cost_usd"] = result.estimated_cost_usd
        return update

    return wrapped
