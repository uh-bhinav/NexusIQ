# AI Subsystem Architecture

Engineering rules: `.claude/rules/ai-service.md`. Rationale: ADR-005, ADR-008, ADR-009.

---

## The graph

```
                          START
                            │
                     ┌──────▼──────┐
                     │   intent    │  classify, extract entities, spot gaps
                     └──────┬──────┘
                     ┌──────▼──────────┐
                     │ context_planner │  what evidence is required?
                     └──────┬──────────┘
                     ┌──────▼──────────┐
                     │   retrieval     │  per-domain hybrid retrieval
                     └──────┬──────────┘
                  ┌─────────┴─────────┐
          ┌───────▼───────┐   ┌───────▼────────┐
          │ policy_analyst│   │ risk_analyzer  │   (parallel)
          └───────┬───────┘   └───────┬────────┘
                  └─────────┬─────────┘
                     ┌──────▼──────┐
                     │  decision   │  synthesise recommendation
                     └──────┬──────┘
                     ┌──────▼──────┐
                     │  validator  │  grounding, citations, contradiction
                     └──────┬──────┘
              fail (≤2×)    │    pass
        ┌──────────────────┘└───────────────┐
        ▼                                   ▼
  back to retrieval/decision        ┌────────────────┐
  (iteration+1; >2 → escalate)      │approval_router │  DETERMINISTIC
                                    └───┬────────┬───┘  (no LLM)
                                   auto │        │ human
                                        │        ▼
                                        │   interrupt() ──resume──┐
                                        ▼                         │
                                    ┌────────────────────────────▼┐
                                    │          finalize           │
                                    └──────────────┬──────────────┘
                                                  END
```

Seven nodes. Adding an eighth requires an ADR.

## State

One `DecisionState` TypedDict is the only thing that flows between nodes.

```python
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
    retrieved_evidence: list[RetrievedChunk]
    policy_findings: list[PolicyFinding]
    risk_analysis: RiskAssessment | None
    recommendation: Recommendation | None
    validation_result: ValidationResult | None

    # control
    iteration: int
    requires_human_approval: bool
    escalation_reasons: list[str]

    # budget accounting
    total_input_tokens: int
    total_output_tokens: int
    estimated_cost_usd: float
    errors: list[NodeError]
```

Rules: nodes return only the keys they change and never mutate in place; no module-level mutable
state; no globals. Anything a later node needs must be in the state, not in a closure.

## Durability

Postgres checkpointer in the `langgraph` schema (the one documented exception to Flyway ownership,
ADR-005). Consequences: a killed service resumes from the last completed node rather than
restarting; a human interrupt can persist for hours or days; every run is inspectable after the
fact.

## Node instrumentation

Every node is wrapped by a decorator that opens an OTel span, times the call, captures tokens and
cost from the `ModelProvider`, catches and classifies errors, and emits a `decision.progress`
Kafka event.

**The AI service never writes `agent_executions`** — it emits the event; Java persists it
(ADR-004). That same event stream drives the live SSE trace.

## Concurrency

`policy_analyst` and `risk_analyzer` run in parallel — they consume the same retrieved evidence and
do not depend on each other. Their outputs merge into distinct state keys, so the merge is a plain
union with no reducer ambiguity. (Two nodes writing the same key is the classic LangGraph
footgun; the state shape avoids it by construction.)

Within `retrieval`, per-domain retrievals also run concurrently.

## Failure and termination

| Failure | Behaviour |
|---|---|
| LLM timeout / 5xx / rate limit | 2 retries, exponential backoff, then node fails |
| Schema validation failure | 1 repair retry with the error appended, then node fails |
| Node failure | Run terminates `FAILED` with a reason. **Never a fabricated result.** |
| Validator failure | Back to retrieval/decision, max `MAX_AGENT_ITERATIONS` (2), then forced escalation |
| Zero retrieval results | `INSUFFICIENT_INFORMATION`, escalate |
| Budget exceeded | Stop immediately; `FAILED` or escalate |
| Workflow timeout | Terminate cleanly with reason |

Every edge must be shown to terminate. There is no unbounded loop in the graph, and
`MAX_AGENT_ITERATIONS` is asserted in tests, not assumed.

## Boundaries

The AI service authenticates nobody and authorises nothing. It receives an already-authorised
`workspace_id` from Java and scopes every query by it anyway. It never writes decision-domain
tables. It cannot finalise a decision — only Java's deterministic gate and an authorised human can.

## Related

Agent contracts `AGENTS.md` · retrieval `RAG.md` · prompt assembly `CONTEXT_ENGINEERING.md` ·
guardrails `GUARDRAILS.md` · evaluation `EVALUATION.md` · prompts `PROMPTS.md` ·
models `MODEL_STRATEGY.md`.
