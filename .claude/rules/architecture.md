# Rules: Architecture

Read when changing service boundaries, events, cross-service data flow, or failure handling.
Full narrative: `docs/ARCHITECTURE.md`. Rationale: `docs/DECISIONS/`.

## Service boundaries

Three deployable services. Do not add a fourth without an ADR.

| Service | Owns | Never does |
|---|---|---|
| `spring-api` (Java 21) | Identity, JWT, RBAC, workspaces, documents (metadata + blobs), decision lifecycle, approvals, audit, SSE fan-out, **all Flyway migrations** | Call an LLM. Embed text. Run agents. |
| `ai-service` (Python 3.11+) | Ingestion (extract/chunk/embed), retrieval, LangGraph agents, guardrails, evaluation | Authenticate users. Authorise anything. Own workflow *authority*. Run migrations. |
| `frontend/web` (React/TS) | Presentation only | Enforce security. Hold secrets. Filter for authorisation. |

Infrastructure: PostgreSQL+pgvector, Redis, Kafka, OTel Collector. Nothing else without an ADR.

## Data ownership

Flyway (in `spring-api`) owns the **entire** relational schema. There is exactly one migration owner.

| Table group | Written by | Read by |
|---|---|---|
| `users`, `workspaces`, `workspace_members` | Java | Java |
| `documents`, `knowledge_sources` | Java | Java, **Python (read-only)** |
| `document_chunks` (incl. `embedding`) | **Python** | Python |
| `decision_requests`, `decision_runs`, `agent_executions`, `evidence`, `findings`, `decisions`, `approvals`, `audit_events` | **Java only** | Java |
| schema `langgraph.*` (checkpointer) | Python / LangGraph, **not Flyway-managed** | Python |

**Python never writes decision-domain tables.** It emits Kafka events; Java persists them. This
keeps one writer per aggregate and keeps the audit trail authoritative. Bulk vector writes go
direct to Postgres because streaming them through Kafka would be absurd.

## Synchronous vs asynchronous

| Interaction | Mode | Why |
|---|---|---|
| Frontend → API | Sync REST | User-facing, p95 < 500 ms for non-AI endpoints |
| Decision progress → Frontend | SSE | Live workflow trace |
| Document upload → ingestion | **Async (Kafka)** | Seconds-to-minutes of work |
| Decision request → AI workflow | **Async (Kafka)** | Minutes of work; must survive restarts |
| API → AI service for *search only* | Sync HTTP | Sub-second, user is waiting |

Rule: **anything that can exceed ~2 s or must survive a restart goes through Kafka.** Never block
an HTTP request on an LLM workflow.

## Event contracts

Topics:

```
document.uploaded        document.processed        document.failed
decision.requested       decision.progress         decision.completed     decision.failed
approval.requested       approval.completed
<topic>.dlq              (one DLQ per consumed topic)
```

Every event envelope:

```json
{
  "event_id": "uuid",              // idempotency key
  "event_type": "DECISION_REQUESTED",
  "schema_version": 1,
  "occurred_at": "RFC3339",
  "workspace_id": "uuid",
  "correlation_id": "uuid",        // propagated end-to-end
  "causation_id": "uuid|null",
  "payload": { }
}
```

Rules:
- **Additive changes only** within a `schema_version`. Removing/renaming a field = version bump +
  a consumer that handles both versions during transition.
- Partition key is `workspace_id` for tenant-ordered processing, or `decision_id` where per-decision
  ordering matters. State the key in the ADR/doc when adding a topic.
- Events carry **IDs and facts, not blobs.** No document text, no embeddings, no prompt bodies.

## Idempotency

Every consumer must be idempotent. Mechanism: a `processed_events(event_id, consumer_group,
processed_at)` table; insert-if-absent inside the same transaction as the side effect. A duplicate
`DECISION_REQUESTED` must **not** create a second `decision_run`.

Redis may front this as a fast-path check, but Postgres is the authority.

## Failure handling

- Bounded retry: 3 attempts, exponential backoff (1s, 4s, 16s) with jitter → then DLQ.
- Never infinite-retry. Never retry a validation/schema failure (it will fail identically).
- DLQ messages retain the original envelope + `failure_reason` + `attempt_count`.
- Failed decision runs terminate in status `FAILED` with a human-readable reason surfaced in the UI.
- Every external dependency (LLM, DB, Redis, Kafka) needs an explicit timeout. No unbounded waits.

Design every feature against: DB down, Redis down, Kafka duplicate, Kafka unavailable, LLM timeout,
LLM invalid JSON, zero retrieval results, contradictory retrieval, injected document, missing
permission, interrupted workflow, looping agent, exceeded budget. Handle the ones that apply;
say so in the PR/summary when one is deliberately out of scope.

## Degradation

| Down | Behaviour |
|---|---|
| Redis | Serve from Postgres, log cache-miss metric. **Never fail the request.** |
| Kafka | Reject new decision requests with `503 SERVICE_UNAVAILABLE`; reads stay up |
| AI service | Existing decisions readable; new ones queue in Kafka and drain on recovery |
| LLM provider | Run fails cleanly to `FAILED` with reason; never silently fabricate a result |

## Observability requirements

`correlation_id` is created at the API edge and propagated: HTTP header `X-Correlation-Id` →
MDC → Kafka envelope → Python context → agent spans → response body `request_id`.

One trace must span: HTTP request → Kafka publish → Kafka consume → LangGraph run → each agent
node → each retrieval → each LLM call → completion event → SSE emission.

Every new component must emit: latency, error count, and (if it calls an LLM) tokens + estimated
cost. A feature without telemetry is not done.

## Security boundaries

Trust zones:
- **Untrusted**: frontend input, uploaded documents, retrieved chunks, LLM output.
- **Trusted**: Java-enforced authz, deterministic policy gate, authenticated humans, config.

The AI service is **not** internet-facing and does not authenticate end users. It trusts a
signed internal call from `spring-api` (`INTERNAL_SERVICE_TOKEN`) and always receives an explicit
`workspace_id` that Java has already authorised. It still scopes every query by that
`workspace_id` — defence in depth.
