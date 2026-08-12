class WorkflowBudgetExceeded(Exception):
    """Raised by the instrumentation wrapper the moment accumulated cost or
    tokens cross MAX_WORKFLOW_COST_USD / MAX_WORKFLOW_TOKENS
    (.claude/rules/ai-service.md: "stop the graph, mark the run FAILED").
    Propagates out of graph.ainvoke() so the consumer treats it exactly like
    a node failure, just with a specific, honest reason."""


class WorkflowTimeout(Exception):
    """Raised by the consumer (messaging/decision_consumer.py) when a run's
    wall-clock time exceeds WORKFLOW_TIMEOUT_SECONDS (docs/AI/GUARDRAILS.md
    Layer 4). Wraps asyncio.TimeoutError with a message specific enough to
    be useful in decision.failed's reason field, rather than the bare
    "TimeoutError" a raw asyncio.wait_for would surface."""
