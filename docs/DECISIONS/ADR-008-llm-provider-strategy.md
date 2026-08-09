# ADR-008: Gemini as default LLM behind a provider abstraction

**Status:** Accepted
**Date:** 2026-08-09
**Phase:** 4

## Context

Seven agent nodes make LLM calls, several per decision run, across development, testing,
evaluation and demos. The project operates under a strict $0-recurring-cost constraint (ADR-010),
which extends to model usage wherever possible.

Providers differ meaningfully in how structured output, tool use, safety filtering and token
accounting work. Those differences leak into application code if nothing prevents it.

## Problem

Which model provider is the default, and how is the system kept from being welded to it?

## Options considered

1. **Gemini, behind a `ModelProvider` abstraction.** A usable free tier keeps development and
   demos at zero cost; native structured output; competitive quality on structured reasoning.
2. **Anthropic or OpenAI as default.** Strong structured-output and tool-use behaviour; both
   require paid keys from the first call, which conflicts with the cost constraint.
3. **Local models via Ollama.** Truly zero marginal cost and fully offline, but noticeably weaker
   multi-step structured reasoning, plus a large container in an already heavy stack. A seven-node
   graph with a weak model produces a demo that argues against itself.
4. **Hardcode one provider's SDK directly in the nodes.** Less code today, and it makes every
   later provider change a rewrite of the agent layer.

## Decision

Gemini is the default runtime provider for development, evaluation and demos. **All** LLM access
goes through `llm/provider.py::ModelProvider`. Adapters: `gemini` (default), `anthropic`, `openai`,
`mock`. Selected by `LLM_PROVIDER`; no LangGraph node ever imports a vendor SDK.

## Rationale

The free tier is what makes a genuinely $0 project possible without accepting the quality drop of
option 3 — and quality matters here because the demo's credibility rests on the model correctly
returning `UNKNOWN` for the data-residency question rather than confabulating.

The abstraction is not speculative generality: it earns its place immediately. The `mock` adapter
makes every test and the entire CI evaluation run deterministic and offline, which would otherwise
require mocking a vendor SDK's internals. Provider portability is a bonus on top of that.

Node-level model selection is configuration (`LLM_MODEL` for cheap classification nodes,
`LLM_MODEL_HEAVY` for synthesis and validation), so the cost/quality trade-off is tunable without
code changes and measurable via the A/B comparison in Phase 10.

## Trade-offs accepted

- The abstraction must expose a lowest-common-denominator interface; provider-specific features
  (extended thinking, native caching, provider-side tool orchestration) are not directly reachable
  without extending it deliberately.
- Free-tier rate limits will throttle evaluation runs; the harness needs backoff and patience.
- Free-tier terms and quotas can change without notice — a real dependency risk for a demo.
- Structured-output semantics differ per provider; each adapter must normalise them, and adapter
  bugs will look like agent bugs.

## Consequences

- `ModelProvider` returns validated Pydantic models, records `model`, `input_tokens`,
  `output_tokens`, `latency_ms`, `estimated_cost_usd` on every call, and normalises errors into
  common types (timeout, rate-limited, invalid-schema, refused).
- The `mock` adapter is fixture-driven, clearly named, and unreachable when
  `NEXUSIQ_ENV` is not `local` or `ci`.
- Adding a provider means adding an adapter and nothing else. If it requires touching a node, the
  abstraction is wrong.
- `LLM_API_KEY` lives only in the AI service — never in Java, an event, a log, or the frontend.
- Pricing tables are versioned and dated; costs are labelled estimates.
- Adapter parity is tested: the same prompt through `mock` and `gemini` must produce
  schema-identical output.

## Revisit when

Free-tier limits block evaluation, quality proves insufficient on the labelled dataset, or a paid
key becomes available — any of which is an adapter swap and a config change, not a redesign.
