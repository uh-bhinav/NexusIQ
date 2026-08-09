# ADR-003: Kafka for asynchronous workflows

**Status:** Accepted
**Date:** 2026-08-09
**Phase:** 2

## Context

Two operations are far too slow for a synchronous HTTP request: document ingestion (extract →
chunk → embed a multi-page PDF) and the decision workflow (seven agent nodes, several LLM round
trips, potentially minutes). Both must survive a service restart — a decision run that vanishes
because a container recycled is not an enterprise system.

## Problem

How is long-running work decoupled from the request path, durably, with retries and failure
visibility?

## Options considered

1. **Kafka.** Durable, replayable log; consumer groups; offset management; DLQ pattern; natural
   fit for the progress-event stream that also drives SSE. Operationally heavy for its
   own sake — a broker to run and understand.
2. **Spring `@Async` / an in-process executor.** Zero infrastructure. Work is lost on restart, no
   backpressure, no retry semantics, no cross-service delivery to a Python consumer.
3. **Database-backed job queue** (a `jobs` table + polling worker). No new infrastructure, durable,
   easy to inspect. But polling latency, hand-rolled retry/DLQ/visibility-timeout logic, and it
   turns the primary datastore into a queue.
4. **Redis Streams / RabbitMQ.** Lighter than Kafka. Redis is already present — but ADR-001 says
   Redis holds nothing unrecoverable, and making it a durable work queue contradicts that.

## Decision

Apache Kafka (KRaft mode, single broker locally) carries all asynchronous cross-service work:
document ingestion and the decision workflow, plus the progress event stream.

## Rationale

Option 2 fails the durability requirement outright. Option 3 works and is genuinely defensible at
this scale — the honest reason it loses is that the retry/DLQ/ordering machinery would be
hand-written, and the event log is also what feeds the live SSE trace and the audit-adjacent event
history. Kafka gives all three (durability, retry/DLQ, replayable progress stream) with one
component rather than three hand-rolled ones.

Option 4 would require redefining Redis's role in the system.

There is a second, stated reason: event-driven architecture is an explicit competency this project
exists to demonstrate. That is not sufficient justification on its own — but combined with two
genuinely long-running, restart-critical flows, it tips a close call.

## Trade-offs accepted

- Real operational weight: a broker to run, topics to manage, consumer lag to watch, offsets to
  reason about — for a workload a `jobs` table could carry.
- ~1 GB of memory in the local stack.
- Eventual consistency in the ingestion and decision paths; the UI must handle "not ready yet".
- Kafka being down means new decision requests are rejected (`503`).

## Consequences

- Nine topics plus a DLQ per consumed topic; envelope contract in `.claude/rules/architecture.md`.
- **Every consumer must be idempotent** — table-backed via `processed_events`, asserted in the same
  transaction as the side effect. A duplicate `DECISION_REQUESTED` must not produce two runs.
- Bounded retry (3, exponential + jitter) then DLQ. Validation failures are never retried.
- Events are published **after commit**, never inside the transaction.
- Events carry IDs and facts only — never document text, embeddings, or prompt bodies.
- Trace context must be propagated explicitly through the envelope (ADR-007).

## Revisit when

If the operational cost visibly outweighs the benefit during Phase 12, a Postgres-backed queue
remains a legitimate fallback — the event envelope abstraction would make that swap contained.
