# Architecture Decision Records

Every significant architectural decision lives here, with the reasoning that produced it and the
trade-off that was accepted. This is the answer to *"why is it built this way?"* — in an interview,
in a code review, or in six months when the reasoning has evaporated.

## Index

| ADR | Decision | Status | Date |
|---|---|---|---|
| [001](ADR-001-postgresql-system-of-record.md) | PostgreSQL is the system of record | Accepted | 2026-08-09 |
| [002](ADR-002-pgvector-over-vector-db.md) | pgvector instead of a dedicated vector database | Accepted | 2026-08-09 |
| [003](ADR-003-kafka-async-workflows.md) | Kafka for asynchronous workflows | Accepted | 2026-08-09 |
| [004](ADR-004-java-python-service-boundary.md) | Java/Python service boundary and data ownership | Accepted | 2026-08-09 |
| [005](ADR-005-langgraph-orchestration.md) | LangGraph for agent orchestration | Accepted | 2026-08-09 |
| [006](ADR-006-human-in-the-loop-policy.md) | Deterministic gate + LangGraph interrupt for HITL | Accepted | 2026-08-09 |
| [007](ADR-007-opentelemetry-observability.md) | OpenTelemetry as the observability standard | Accepted | 2026-08-09 |
| [008](ADR-008-llm-provider-strategy.md) | Gemini as default LLM behind a provider abstraction | Accepted | 2026-08-09 |
| [009](ADR-009-local-embeddings.md) | Local bge-small-en-v1.5 embeddings | Accepted | 2026-08-09 |
| [010](ADR-010-local-first-zero-cost-deployment.md) | Local-first, $0-recurring-cost deployment | Accepted | 2026-08-09 |
| [011](ADR-011-document-extraction-libraries.md) | `pdfplumber` for PDF, `python-docx` for DOCX | Accepted | 2026-08-10 |

## When to write an ADR

**Required:** new infrastructure component · new service · changed service boundary · changed
persistence model · changed event contract semantics · changed auth model · different AI
orchestration framework · a new agent in the graph · introducing Kubernetes · any new runtime
dependency that is not trivially replaceable · reversing an existing ADR.

**Not required:** naming, file layout inside a module, an endpoint that follows existing
conventions, adding a test, refactoring inside a class, choosing a utility library with a
one-line surface.

Use `/architecture-review` to produce the analysis, then write the ADR.

## Rules

- ADRs are **immutable once accepted**. To change a decision, write a new ADR and mark the old one
  `Superseded by ADR-nnn`.
- Never silently contradict an accepted ADR. If the code and an ADR disagree, one of them is a bug.
- Status: `Proposed` → `Accepted` → `Superseded` / `Deprecated`.
- Number sequentially. Filename `ADR-{nnn}-{kebab-slug}.md`. Add the row to the index above.
- Record the trade-off **that was actually accepted**, not a list of advantages. An ADR with no
  downside section is marketing, not engineering.

## Template

```markdown
# ADR-nnn: <Decision>

**Status:** Proposed | Accepted | Superseded by ADR-nnn
**Date:** YYYY-MM-DD
**Phase:** <roadmap phase this affects>

## Context
What is true about the system that makes this decision necessary.

## Problem
The specific question being answered, stated without naming a technology.

## Options considered
1. **Option A** — description. Pros / cons.
2. **Option B** — ...
3. **Do nothing / defer** — what breaks if we don't decide now.

## Decision
The chosen option, stated in one sentence.

## Rationale
Why this option beat the others, for *this* system's actual constraints.

## Trade-offs accepted
What we are knowingly giving up. Be honest and specific.

## Consequences
What must now be true, built, or maintained as a result. Include what this forecloses.

## Revisit when
The condition that should make us reconsider.
```
