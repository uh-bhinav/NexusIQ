# Testing Strategy

**The mandatory rules — what must be tested, with what tools, and the 14 required failure-scenario
tests — live in `.claude/rules/testing.md`.** This document covers the strategy behind them and
does not repeat them.

---

## What we are actually trying to prove

Three claims, in priority order:

1. **Nothing leaks across tenants.** This is the failure that would end the credibility of the
   system. Every tenant-scoped endpoint gets a negative test where a user from workspace B is
   denied workspace A's resource. Non-negotiable.
2. **The AI does not fabricate.** Ungrounded claims, invented citations and confident answers over
   absent evidence are the failure modes this whole architecture exists to prevent. They are tested
   adversarially, not incidentally.
3. **Distributed work is exactly-once in effect.** Duplicate events, restarts and retries must not
   produce two decision runs, two approvals, or two audit entries.

Ordinary correctness testing sits underneath these. Anyone can test the happy path; these three are
what the test suite is for.

## Why this shape

| Layer | Rationale |
|---|---|
| Heavy unit testing of deterministic logic | The policy gate, chunker, context builder, validators and mappers are where correctness is cheap to establish and expensive to get wrong |
| Real Postgres via Testcontainers, never H2 | pgvector, `CHECK` constraints, triggers, `CITEXT` and index behaviour do not exist in H2. Tests that pass on H2 and fail on Postgres are worse than no tests |
| Kafka in Testcontainers | Idempotency and DLQ behaviour cannot be tested against a mock broker |
| `mock` LLM provider everywhere in CI | Determinism and $0 cost. A test whose result depends on a model's mood is not a test |
| A separate evaluation suite | AI quality is statistical, not binary. It needs metrics and baselines, not assertions — see `docs/AI/EVALUATION.md` |
| Few E2E tests | Expensive and brittle. One that covers the full spine is worth more than twenty shallow ones |

## The evaluation suite is not the test suite

Unit and integration tests answer *"is it correct?"* — binary, blocking, fast.
Evaluation answers *"how good is it?"* — statistical, comparative, tracked over time.

Conflating them produces either flaky CI or meaningless quality claims. They run separately, gate
differently, and report differently.

## Test data

Synthetic only, committed, deliberately imperfect. `docs/sample-enterprise/` contains satisfied
requirements, genuine violations, ambiguity, a superseded policy version that conflicts with its
successor, an unanswerable question (EU data residency), and one prompt-injection attempt.

The corpus is designed so that a system which merely retrieves and summarises will visibly fail on
it. That is the point.

Never commit real proprietary documents.

## What we deliberately do not test

- Framework behaviour (Spring's DI, Pydantic's validation) — trust the library.
- Getters, mappers without logic, generated code.
- Exact LLM wording. Assert structure, constraints and grounding; never a specific sentence.
- UI pixels. Assert what a user can see and do.

## Coverage

Coverage is a signal, not a target. A meaningful branch covered beats a percentage. No coverage
gate that can be satisfied by testing getters.

What is actually checked before a phase closes: every acceptance criterion demonstrated with
evidence, every relevant failure scenario tested, and the negative security tests present.

## Honesty rules

Never weaken an assertion, add a sleep, skip a test, or mock the thing under test to produce green.
If a test is genuinely wrong, fix or delete it deliberately and say so in the summary.

Report real numbers, including failures. A phase reported complete with failing tests is a
falsified status, and `STATUS.md` becomes worthless the first time it happens.

## Running

`/test-and-verify` picks the focused set for a given change. `make test` runs everything. CI runs
everything plus the evaluation harness on the `mock` provider.
