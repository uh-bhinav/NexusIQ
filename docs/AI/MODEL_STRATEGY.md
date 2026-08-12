# Model Strategy

Rationale: ADR-008 (LLM provider), ADR-009 (embeddings).

---

## Provider abstraction

All LLM access goes through `llm/provider.py::ModelProvider`. **No LangGraph node imports a vendor
SDK.** If adding a provider requires touching a node, the abstraction is wrong.

```python
class ModelProvider(Protocol):
    async def generate_structured(
        self,
        *,
        system: str,
        user: str,
        schema: type[BaseModel],
        model: str,
        temperature: float = 0.1,
        timeout_s: int = 60,
    ) -> ModelResult[T]: ...

class ModelResult(BaseModel, Generic[T]):
    value: T
    model: str
    input_tokens: int
    output_tokens: int
    latency_ms: int
    estimated_cost_usd: float
    finish_reason: str
    repaired: bool          # did a schema repair retry occur?
```

Adapters: `gemini` (default) · `anthropic` · `openai` · `mock`. Selected by `LLM_PROVIDER`.

Each adapter must: use the provider's native structured-output mode; normalise token accounting;
normalise errors into `ModelTimeout`, `ModelRateLimited`, `ModelInvalidSchema`, `ModelRefused`,
`ModelUnavailable`; and produce schema-identical output to every other adapter for the same input.
Adapter parity is tested.

## Model selection

Configuration, never hardcoded:

| Node | Model | Why |
|---|---|---|
| `intent` | `LLM_MODEL` (fast) | Classification and extraction |
| `context_planner` | `LLM_MODEL` | Structured planning |
| `policy_analyst` | `LLM_MODEL_HEAVY` | Careful reading against evidence |
| `risk_analyzer` | `LLM_MODEL` | Assessment over structured findings |
| `decision` | `LLM_MODEL_HEAVY` | Synthesis — the highest-stakes output |
| `validator` | `LLM_MODEL_HEAVY` | Adversarial checking of the above |

Defaults: `gemini-2.5-flash` / `gemini-3.6-flash`. `gemini-2.5-flash` was **verified live in Phase 4
(2026-08-11)** against the Gemini API's `models.list()` and `ai.google.dev/gemini-api/docs/pricing`:
current, no deprecation notice, and meaningfully cheaper than the newer `gemini-3.5-flash`
($0.30/$2.50 vs $1.50/$9.00 per 1M input/output tokens) — the right trade for a $0-recurring-cost
project's fast/classification tier (ADR-010).

`LLM_MODEL_HEAVY` was originally `gemini-2.5-pro` (chosen in Phase 4 on the reasoning that a
withdrawn-without-notice preview model was the risk to avoid, so a GA model was picked instead).
**That reasoning had it backwards**: `gemini-2.5-pro` itself returned `404 "no longer available to
new users"` on a live call during Phase 5 (2026-08-11) — still listed in `models.list()` and still
on the public pricing page, but rejected for this specific API key/project regardless. `models.list()`
and the pricing page are **not sufficient verification** that a model id is actually callable by a
given key; only a real `generateContent` call proves that. Re-pinned to `gemini-3.6-flash`, verified
the same way (a real call, not just a listing) on 2026-08-11 — see `llm/pricing.py` for its dated
pricing entry. Re-verify with a live call, not just a listing, if this is ever revisited.

This split is a hypothesis, not a fact. Phase 10's A/B run tests it and the outcome is recorded
with numbers.

## The `mock` provider

Fixture-driven, deterministic, offline. It exists so that unit tests, integration tests and the CI
evaluation run reproducibly and for free — not as a stand-in for real functionality.

Rules: clearly named `mock`; unreachable when `NEXUSIQ_ENV` is not `local` or `ci`; fixtures live
in `tests/fixtures/llm/`; it is **never** presented as working AI behaviour in a demo or a report.

## Cost tracking

Every call records model, input tokens, output tokens, latency and estimated cost. Costs
accumulate into `DecisionState` and are enforced against `MAX_WORKFLOW_COST_USD` and
`MAX_WORKFLOW_TOKENS`; a breach stops the run.

Pricing lives in `llm/pricing.py` with a comment stating the date it was last verified. Prices
change; a stale table produces confidently wrong cost numbers, so every figure surfaced in the UI
is labelled an **estimate**.

## Temperature

Default `0.1` everywhere. This is a decision system, not a writing assistant — variance in a
policy evaluation is a defect, not creativity. The validator runs at the same low temperature; its
job is consistency.

## Embeddings

Separate abstraction, `embeddings/provider.py::EmbeddingProvider`. Default: local
`BAAI/bge-small-en-v1.5`, 384 dims, in-process (ADR-009).

The critical rule: **`embedding_model` and `embedding_version` are stored on every chunk.**
Changing either triggers a controlled re-embedding migration. Mixing vectors from two models in
one index produces ranking that is wrong in ways no test will detect — the vectors are still valid
numbers, they just mean nothing relative to each other.

Query and passage embeddings must use the same model and the same instruction-prefix convention.

## Reranker

`BAAI/bge-reranker-base`, local, in-process, toggleable via `RERANKER_ENABLED`. Its
latency/quality trade-off is benchmarked in Phase 3 and recorded in `RAG.md` — it is adopted on
evidence, not by default.

## Switching providers

1. Set `LLM_PROVIDER` and `LLM_API_KEY`.
2. Set `LLM_MODEL` / `LLM_MODEL_HEAVY`.
3. Run `make eval` and compare against the baseline.
4. Record the outcome in `EVALUATION_BASELINE.md`.

No code changes. If any are required, that is a bug in the abstraction.

## Known risks

Free-tier quotas can change or throttle — the harness needs backoff, and the `mock` provider keeps
the system demonstrable regardless. Provider structured-output semantics differ, so adapter bugs
will masquerade as agent bugs; parity tests exist to catch that. Model IDs and pricing drift, so
both are verified at implementation time rather than trusted from a document.
