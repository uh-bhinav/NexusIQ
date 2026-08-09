# NexusIQ — System Architecture

Constraints and rules derived from this: `.claude/rules/architecture.md`.
Rationale for each major choice: `docs/DECISIONS/`.

---

## 1. Component view

```
                        ┌────────────────────────┐
                        │  React + TypeScript    │
                        │  (Vite, TanStack)      │
                        └───────────┬────────────┘
                          REST + SSE│  JWT
                        ┌───────────▼────────────┐
                        │   spring-api  (Java 21)│
                        │                        │
                        │  auth · workspaces     │
                        │  documents · decisions │
                        │  approvals · audit     │
                        │  SSE fan-out           │
                        │  Flyway (schema owner) │
                        └──┬──────┬──────────┬───┘
                           │      │          │
              ┌────────────▼─┐ ┌──▼───┐ ┌────▼─────────┐
              │ PostgreSQL   │ │Redis │ │    Kafka     │
              │ + pgvector   │ │cache │ │  (KRaft)     │
              │ system of    │ └──────┘ └────┬─────────┘
              │ record       │◄──────────────┤
              └──────▲───────┘               │
        chunks/vectors│ (write)               │ events
        documents     │ (read)         ┌──────▼──────────────┐
                      └────────────────┤ ai-service (Python) │
                                       │ FastAPI + LangGraph │
                                       └──────┬──────────────┘
                                              │
                      ┌───────────────────────▼────────────────────────┐
                      │ intent → planner → retrieval → policy ∥ risk    │
                      │        → decision → validator → approval router │
                      └───────────────────────┬────────────────────────┘
                                              │
                                    ┌─────────▼─────────┐
                                    │   LLM provider    │
                                    │ (Gemini default,  │
                                    │  behind adapter)  │
                                    └───────────────────┘

        All services → OTel Collector → traces/metrics backend (local)
```

Three services. Four infrastructure components. Nothing else without an ADR.

## 2. Responsibility split

**`spring-api` (Java 21, Spring Boot)** — the transactional enterprise backend and the only
authority. Identity, JWT, RBAC, workspace membership, document metadata and blob storage, the
decision lifecycle state machine, persistence of all decision-run artifacts, the approval workflow,
the append-only audit trail, SSE fan-out, and **every Flyway migration**. It never calls an LLM.

**`ai-service` (Python 3.11+, FastAPI + LangGraph)** — the reasoning layer. Document extraction,
chunking, embedding, vector storage, hybrid retrieval, the agent graph, guardrails, evaluation.
It authenticates nobody and authorises nothing; it receives an already-authorised `workspace_id`
and scopes every query to it as defence in depth.

**`frontend/web` (React + TypeScript)** — presentation only. Enforces no security.

Why two languages: the enterprise transactional surface (Spring Security, JPA, Flyway,
Testcontainers) is Java's strength; the AI ecosystem (LangGraph, sentence-transformers, provider
SDKs) is Python's. Splitting on that line is the honest boundary — not a microservice fashion.
See ADR-004.

## 3. Data ownership

Single schema owner: **Flyway in `spring-api`**. One migration history, one source of truth.

| Data | Writer | Readers |
|---|---|---|
| users, workspaces, workspace_members | Java | Java |
| documents, knowledge_sources | Java | Java, Python (read-only) |
| document_chunks + embeddings | **Python** | Python |
| decision_requests, decision_runs, agent_executions, evidence, findings, decisions, approvals | **Java only** | Java |
| audit_events (append-only) | Java | Java |
| processed_events (idempotency) | both, per consumer group | — |
| `langgraph.*` checkpointer | Python/LangGraph (not Flyway) | Python |

The important rule: **Python never writes decision-domain tables.** It emits `decision.progress`
and `decision.completed` events; Java persists them. One writer per aggregate, and the audit trail
stays authoritative. Chunks are the deliberate exception — streaming thousands of 384-dim vectors
through Kafka to have Java write them would be pure ceremony.

## 4. Request flows

### 4.1 Document ingestion (async)

```
POST /workspaces/{id}/documents
  → authz → validate type/size/magic bytes → checksum
  → store blob (DocumentStorage abstraction)
  → INSERT documents (status=UPLOADED)          ─┐ one transaction
  → audit_event                                  ─┘
  → after commit: publish document.uploaded      → 202 Accepted

ai-service consumes document.uploaded
  → idempotency check (event_id)
  → extract text → clean → detect sections → hierarchical chunk
  → injection heuristic scan → flag suspicious chunks
  → embed (local bge-small, batched)
  → bulk INSERT document_chunks
  → publish document.processed  (or document.failed)

spring-api consumes document.processed
  → UPDATE documents SET status=READY → audit_event → SSE/poll surfaces it
```

### 4.2 Decision workflow (async)

```
POST /decisions
  → authz → validate
  → INSERT decision_requests (PENDING) + decision_runs (QUEUED) + audit_event
  → after commit: publish decision.requested   → 202 {decision_id, status: PROCESSING}

ai-service consumes decision.requested
  → idempotency check
  → LangGraph run (checkpointed in Postgres)
  → each node emits decision.progress { node, status, latency, tokens, cost }
  → terminal: decision.completed { recommendation, confidence, risk,
                                   findings[], evidence[], validation, requires_approval }

spring-api consumes progress/completed
  → persists agent_executions / evidence / findings / decisions
  → pushes SSE frames to subscribed clients
  → applies the deterministic gate → creates an approval task if required
  → status → WAITING_FOR_APPROVAL | APPROVED | FAILED
```

### 4.3 Human approval

```
Approver opens the queue → sees recommendation, confidence, risk, findings,
  evidence with citations, agent trace, missing information
POST /approvals/{id}/approve|reject   (APPROVER/ADMIN; requester ≠ approver)
  → UPDATE approvals + decisions.final_status + audit_event
  → publish approval.completed
ai-service resumes the interrupted LangGraph run → finalize node → decision.completed(final)
```

Java owns *who may approve* and the record. LangGraph owns *suspend and resume*. See ADR-006.

## 5. Synchronous vs asynchronous

Sync: everything user-facing and fast — CRUD, listing, knowledge search (`spring-api` → HTTP →
`ai-service`, sub-second).
Async: everything slow or restart-critical — ingestion and the decision workflow.

Rule: if it can exceed ~2 s or must survive a restart, it goes through Kafka. No HTTP request ever
blocks on an LLM workflow.

## 6. Eventing

Topics and the envelope contract: `.claude/rules/architecture.md`. Summary: nine topics plus a DLQ
per consumed topic; every event carries `event_id`, `schema_version`, `workspace_id`,
`correlation_id`; partition key is `workspace_id` (or `decision_id` where per-decision ordering
matters); events carry IDs and facts, never blobs or prompt bodies.

Idempotency is table-backed (`processed_events`), asserted inside the same transaction as the side
effect. Retry is bounded (3, exponential + jitter) then DLQ. Validation failures are never retried.

## 7. AI subsystem

Seven nodes, one `DecisionState`, explicit conditional edges, Postgres checkpointer.

```
intent → context_planner → retrieval → (policy_analyst ∥ risk_analyzer)
       → decision → validator ─┬─ pass ─→ approval_router ─┬─ auto ─→ finalize
                               │                            └─ human → interrupt → finalize
                               └─ fail (≤2×) ─→ back to retrieval/decision
                               └─ fail (>2×) ─→ force human review
```

`approval_router` contains **zero** LLM calls — it is a threshold gate over
`HITL_MIN_CONFIDENCE`, `HITL_ESCALATE_ON_RISK`, `HITL_MIN_EVIDENCE_COVERAGE`, and any `VIOLATED`
policy finding. That is the point: the probabilistic system is wrapped in a deterministic one.

Retrieval is hybrid: vector similarity (cosine, pgvector HNSW) → metadata filter (workspace, doc
type, version, recency) → rerank → context assembly with priority ordering (authoritative policies
→ current versions → direct evidence → supporting → historical). Every chunk carries its identity;
the model never sees an anonymous chunk.

Detail: `docs/AI/ARCHITECTURE.md`, `AGENTS.md`, `RAG.md`, `CONTEXT_ENGINEERING.md`,
`GUARDRAILS.md`, `EVALUATION.md`, `MODEL_STRATEGY.md`.

## 8. Storage

- **PostgreSQL 16 + pgvector** — relational data, permissions, metadata *and* vectors in one
  transactional store. One system instead of two, and vector search can filter on tenant and
  metadata natively. ADR-002.
- **Redis** — cache (document metadata, retrieval results keyed by workspace+query hash), rate
  limiting, SSE fan-out across API instances, idempotency fast-path. Never authoritative;
  everything in it is rebuildable. If Redis is down, requests still succeed.
- **Blob storage** — behind a `DocumentStorage` interface; `LocalStorage` for v1, S3-compatible
  later without touching business logic.

## 9. Observability

`correlation_id` is minted at the API edge and flows: HTTP header → MDC → Kafka envelope → Python
context → LangGraph node spans → LLM call spans → completion event → SSE frame.

One trace covers HTTP → publish → consume → graph run → each node → each retrieval → each LLM
call → completion. Metrics span infrastructure (latency, errors, cache hit ratio, consumer lag),
RAG (retrieval latency, similarity distribution, empty-result rate), AI (per-agent latency, failure
rate, tokens, cost, confidence distribution, validation failure rate) and business (decisions
processed, escalation rate, approval turnaround). ADR-007, `docs/OPERATIONS/OBSERVABILITY.md`.

## 10. Failure model

Bounded retries everywhere; DLQ as the terminal state; explicit timeouts on every external call;
graceful degradation per component (Redis down → serve from Postgres; Kafka down → 503 on writes,
reads unaffected; AI service down → work queues and drains; LLM down → run fails cleanly with a
reason, never a fabricated result). Full table in `.claude/rules/architecture.md`.

## 11. Security architecture

Untrusted: client input, document content, retrieved chunks, LLM output. Trusted: Java-enforced
authz, the deterministic gate, authenticated humans, server config.

Two authorization layers — global role and workspace membership — both enforced in SQL predicates,
never by post-fetch filtering. The AI service is not internet-facing and requires
`INTERNAL_SERVICE_TOKEN`. `LLM_API_KEY` exists only inside the AI service.
Details: `.claude/rules/security.md`.

## 12. Deployment

Primary and supported: **local Docker Compose**, the full stack, reproducible on a developer
machine, $0 recurring cost. Phase 13 additionally produces Kubernetes manifests verified on
`kind` as a deployment artifact and learning target — not a hosted environment. ADR-010.

## 13. Known architectural trade-offs

| Choice | Cost accepted | Why |
|---|---|---|
| Two languages | Two toolchains, two CI paths, cross-service contracts | Each language used where it is genuinely strongest (ADR-004) |
| Kafka for two flows | Real operational weight for a portfolio-scale workload | Durable async execution is a stated requirement; the flows are genuinely long-running (ADR-003) |
| pgvector over a dedicated vector DB | Less specialised ANN tuning at very large scale | One store, transactional, tenant-filterable; scale is not the constraint here (ADR-002) |
| Python writes chunks directly | Two writers in one database | The alternative is streaming vectors through Kafka, which is worse |
| Local embeddings | ~1–2 GB image, slower cold start | $0 cost, no rate limits, reproducible evaluation (ADR-009) |
| Seven agents | More orchestration than one prompt | Each node is separately evaluable and separately fixable |
