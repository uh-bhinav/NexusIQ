# ADR-001: PostgreSQL is the system of record

**Status:** Accepted
**Date:** 2026-08-09
**Phase:** 0–1

## Context

NexusIQ holds identity, tenant membership, document metadata, decision workflow state, evidence,
findings, approvals and an audit trail. Several of these are legally/organizationally
consequential: an audit trail that loses events, or an approval that is recorded twice, destroys
the credibility of the entire system. Multiple services (Java, Python) touch the data.

## Problem

Which store holds the authoritative truth, and what guarantees must it provide?

## Options considered

1. **PostgreSQL as the single system of record.** ACID transactions, foreign keys, constraints,
   mature tooling, one migration history. Requires everything consequential to funnel through it.
2. **Polyglot persistence** — Postgres for relational, a document store for decision runs, a vector
   DB for embeddings, Redis for workflow state. Each component "best of breed"; no cross-store
   transactions, no referential integrity, and reconciliation logic to write and debug.
3. **Event-sourced Kafka as truth**, with Postgres as a read projection. Excellent audit story;
   drastically higher complexity, eventual consistency in the read path, and hard to query for a
   UI that must show a decision and its evidence in one request.

## Decision

PostgreSQL is the single system of record. Redis is a cache and ephemeral coordinator that holds
nothing unrecoverable. Kafka is durable transport, not the truth. All consequential state changes
happen in a Postgres transaction.

## Rationale

The invariants this system needs are exactly the ones a relational database was built to enforce:
a decision belongs to a workspace, evidence belongs to a run, an approval references a real
decision, an audit event is never mutated, `confidence` is between 0 and 1. Enforcing those in
application code across two languages is strictly worse than a `CHECK` constraint.

Option 2 trades those guarantees for scale characteristics this system does not need — the corpus
is thousands of chunks, not billions. Option 3 buys an audit property we can get far more cheaply
with an append-only table plus a DB trigger.

One store also means one migration history, one backup, one connection story, and one place to
answer "what actually happened".

## Trade-offs accepted

- Postgres becomes a single point of failure for writes. Accepted: it is a local single-node
  deployment (ADR-010), and availability is not a stated requirement.
- We give up the ability to scale any one data type independently.
- Two services write to one database, which requires explicit table-level ownership discipline
  (ADR-004) rather than physical enforcement.

## Consequences

- Flyway, inside `spring-api`, owns the entire schema. Exactly one migration owner.
- `audit_events` is append-only, enforced by a DB trigger, not by convention.
- Redis outage must degrade to a Postgres read, never to an error.
- Kafka consumers write their idempotency marker in the same transaction as their side effect.
- Any proposal to add a datastore must show that Postgres genuinely cannot do the job.

## Revisit when

The corpus exceeds tens of millions of chunks, or write throughput on the decision path becomes a
measured bottleneck. Neither is plausible at this project's scale.
