# ADR-004: Java/Python service boundary and data ownership

**Status:** Accepted
**Date:** 2026-08-09
**Phase:** 1–2

## Context

The system needs a transactional enterprise backend (auth, RBAC, tenancy, workflow state, audit)
and an AI reasoning layer (LangGraph, embeddings, provider SDKs). Java's ecosystem is strongest at
the former; Python's is where the entire AI tooling ecosystem lives.

Both need database access. Two services writing one database is a design smell unless ownership
is explicit.

## Problem

Where does the boundary fall, and who is allowed to write what?

## Options considered

1. **Java-only.** One language, one deployable, simplest ops. But LangGraph, sentence-transformers
   and provider SDKs are Python-first; the Java equivalents would mean fighting the ecosystem on
   every AI task.
2. **Python-only.** The AI half is natural; the enterprise half (Spring Security, JPA, Flyway,
   Testcontainers, method security) would be rebuilt at considerably lower quality.
3. **Two services, split on capability** — Java owns the transactional/authoritative surface,
   Python owns reasoning and retrieval. Two toolchains, a cross-service contract, but each half
   uses the language that is genuinely best for it.
4. **Two services plus a separate embedding service / ingestion worker.** More boundaries than the
   problem has.

## Decision

Two backend services split on capability (option 3), with **table-level data ownership**:

- Flyway inside `spring-api` owns the **entire** schema — one migration owner, no exceptions
  (except LangGraph's own `langgraph` checkpointer schema, which is explicitly out of scope).
- Python writes **only** `document_chunks`; it reads `documents`/`workspaces` read-only.
- Python **never** writes decision-domain tables. It emits `decision.progress` and
  `decision.completed` events; Java persists `decision_runs`, `agent_executions`, `evidence`,
  `findings`, `decisions`, `approvals`, `audit_events`.
- Python authenticates nobody and authorises nothing. It receives an already-authorised
  `workspace_id` and still scopes every query by it, as defence in depth.

## Rationale

The split follows a real capability line, not a microservice fashion. Each service uses the stack
that makes its half straightforward.

The ownership rule is the part that matters. One writer per aggregate keeps the audit trail
authoritative and keeps decision state in the same transaction boundary as the approval workflow —
if Python could write `decisions`, the deterministic gate would no longer be the only thing that
can finalise a decision, which would undermine the entire governance story.

Chunks are the deliberate exception: streaming thousands of 384-dimension vectors through Kafka so
that Java can insert them would be ceremony with no benefit.

## Trade-offs accepted

- Two toolchains, two CI paths, two Dockerfiles, two dependency ecosystems to keep current.
- A cross-service contract (Kafka envelopes + internal HTTP) that must be kept in sync manually —
  there is no shared type system across the boundary.
- Two writers into one database, enforced by discipline and review rather than by permissions.
  (Mitigation available if it ever slips: separate DB roles with table-level grants.)
- A network hop on the synchronous knowledge-search path.

## Consequences

- The ownership table in `docs/ARCHITECTURE.md` §3 is normative. Violating it is a review reject.
- Python never runs DDL and never creates a migration.
- The AI service is not internet-facing; internal calls carry `INTERNAL_SERVICE_TOKEN`.
- `LLM_API_KEY` exists only in the AI service — never in Java, never in an event, never in a log.
- Schema changes that affect Python require coordinated deployment; note it in the phase plan.

## Revisit when

If the manual contract sync becomes a recurring source of bugs, generate shared types from a schema
registry or an OpenAPI/Avro definition rather than splitting differently.
