# Database Schema

Rules for working with the database: `.claude/rules/database.md`. Rationale: ADR-001, ADR-002.

**This document is the design intent. Once migrations exist, `db/migration/` is the truth** — when
they disagree, fix this file.

---

## Ownership

Flyway inside `spring-api` owns every migration. Python writes only `document_chunks`; reads
`documents`, `knowledge_sources`, `workspaces`. The `langgraph` schema is created by LangGraph's
checkpointer and is explicitly outside Flyway's scope.

## Migration sequence

| Migration | Contents | Phase |
|---|---|---|
| `V1__enable_extensions.sql` | `vector`, `pgcrypto` | 1 |
| `V2__create_users.sql` | `users` | 1 |
| `V3__create_workspaces.sql` | `workspaces`, `workspace_members` | 1 |
| `V4__create_documents_and_audit.sql` | `documents`, `knowledge_sources`, `audit_events` + append-only trigger | 1 |
| `V5__create_document_chunks.sql` | `document_chunks` + HNSW index | 2 |
| `V6__create_processed_events.sql` | `processed_events` (idempotency) | 2 |
| `V7__create_decisions.sql` | `decision_requests`, `decision_runs`, `agent_executions` | 5 |
| `V8__create_evidence_and_findings.sql` | `evidence`, `findings`, `decisions` | 5 |
| `V9__create_approvals.sql` | `approvals` | 7 |

Sequence may extend; applied migrations are never edited.

---

## Entities

### `users`
`id` UUID PK · `email` CITEXT UNIQUE NOT NULL · `name` · `password_hash` · `role` VARCHAR CHECK
(`ADMIN`,`ANALYST`,`APPROVER`,`VIEWER`) · `is_active` BOOL · `created_at` · `updated_at`.

### `workspaces`
`id` · `name` NOT NULL · `slug` UNIQUE · `description` · `created_by` FK users · `created_at` ·
`updated_at`.

### `workspace_members`
`workspace_id` FK · `user_id` FK · `role` CHECK (same enum) · `joined_at`.
PK `(workspace_id, user_id)`. The **authorization join table** — every scoped query proves
membership through it.

### `documents`
`id` · `workspace_id` FK NOT NULL · `name` · `original_filename` · `document_type` CHECK
(`SECURITY_POLICY`,`COMPLIANCE_POLICY`,`PROCUREMENT_POLICY`,`ARCHITECTURE_STANDARD`,
`VENDOR_DOCUMENT`,`HISTORICAL_DECISION`,`INCIDENT_REPORT`,`OTHER`) · `version` INT NOT NULL
DEFAULT 1 · `supersedes_document_id` FK NULLABLE · `is_current` BOOL · `storage_path` ·
`content_type` · `size_bytes` · `checksum_sha256` · `status` CHECK
(`UPLOADED`,`PROCESSING`,`READY`,`FAILED`) · `failure_reason` · `chunk_count` · `uploaded_by` FK ·
`created_at` · `updated_at`.

`version` + `supersedes_document_id` + `is_current` are what let retrieval prefer the current
policy over a superseded one — a stated requirement, not decoration.

### `knowledge_sources`
`id` · `document_id` FK · `source_type` · `source_reference` · `trust_level` CHECK
(`AUTHORITATIVE`,`SUPPORTING`,`INFORMATIONAL`) · `created_at`.
Drives authoritative-source preference in context assembly.

### `document_chunks` *(written by Python)*
`id` · `document_id` FK ON DELETE CASCADE · `workspace_id` FK NOT NULL (denormalised deliberately,
so vector queries filter without a join) · `chunk_index` INT · `content` TEXT ·
`embedding vector(384)` · `token_count` · `page_number` · `section` · `subsection` ·
`heading_path` TEXT[] · `embedding_model` NOT NULL · `embedding_version` INT NOT NULL ·
`is_flagged` BOOL DEFAULT false · `flag_reason` (e.g. `PROMPT_INJECTION_SUSPECTED`) ·
`metadata` JSONB · `created_at`.

UNIQUE `(document_id, chunk_index)`.

### `processed_events`
`event_id` UUID · `consumer_group` VARCHAR · `processed_at`. PK `(event_id, consumer_group)`.
Inserted in the **same transaction** as the side effect. This is the idempotency mechanism.

### `decision_requests`
`id` · `workspace_id` FK · `requested_by` FK · `title` · `question` TEXT · `decision_type` ·
`priority` CHECK (`LOW`,`NORMAL`,`HIGH`,`URGENT`) · `status` CHECK (`PENDING`,`PROCESSING`,
`WAITING_FOR_APPROVAL`,`APPROVED`,`REJECTED`,`FAILED`) · `correlation_id` · `created_at` ·
`updated_at`.

### `decision_runs` *(immutable once terminal)*
`id` · `decision_request_id` FK · `workflow_version` · `prompt_version` · `llm_model` ·
`embedding_model` · `status` CHECK (`QUEUED`,`RUNNING`,`SUSPENDED`,`COMPLETED`,`FAILED`) ·
`confidence` NUMERIC CHECK (0–1) · `total_input_tokens` · `total_output_tokens` ·
`estimated_cost_usd` NUMERIC(10,6) · `latency_ms` · `iteration_count` · `failure_reason` ·
`started_at` · `completed_at`.

Recording `workflow_version`, `prompt_version`, `llm_model` and `embedding_model` per run is what
makes results **reproducible** and A/B comparisons meaningful.

### `agent_executions` *(immutable)*
`id` · `decision_run_id` FK · `agent_name` · `sequence_index` · `status` CHECK
(`SUCCESS`,`FAILED`,`SKIPPED`,`RETRIED`) · `model` · `input_tokens` · `output_tokens` ·
`latency_ms` · `estimated_cost_usd` · `output` JSONB · `error` · `trace_id` · `started_at` ·
`completed_at`.

### `evidence`
`id` · `decision_run_id` FK · `document_id` FK · `chunk_id` FK · `claim` TEXT · `evidence_text` ·
`relevance_score` NUMERIC · `citation_reference` (e.g. `SP-102 §4.2 p.11`) · `created_at`.

### `findings`
`id` · `decision_run_id` FK · `category` CHECK (`POLICY`,`RISK`,`GAP`,`CONFLICT`,
`PROMPT_INJECTION_ATTEMPT`) · `policy_name` · `status` CHECK (`SATISFIED`,`PARTIALLY_SATISFIED`,
`VIOLATED`,`UNKNOWN`) · `severity` CHECK (`INFO`,`LOW`,`MEDIUM`,`HIGH`,`CRITICAL`) · `title` ·
`description` · `confidence` NUMERIC CHECK (0–1) · `created_at`.

`findings_evidence(finding_id, evidence_id)` join table — a finding may cite several pieces of
evidence, and evidence may support several findings.

### `decisions`
`id` · `decision_run_id` FK UNIQUE · `recommendation` CHECK (`APPROVE`,`CONDITIONAL_APPROVAL`,
`REJECT`,`INSUFFICIENT_INFORMATION`) · `reasoning_summary` TEXT · `confidence` NUMERIC CHECK (0–1) ·
`risk_level` CHECK (`LOW`,`MEDIUM`,`HIGH`,`CRITICAL`) · `evidence_coverage` NUMERIC ·
`validation_passed` BOOL · `validation_details` JSONB · `requires_human_approval` BOOL NOT NULL ·
`escalation_reasons` TEXT[] · `required_actions` TEXT[] · `final_status` CHECK (`PENDING`,
`AUTO_APPROVED`,`HUMAN_APPROVED`,`HUMAN_REJECTED`) · `created_at`.

`requires_human_approval` and `escalation_reasons` are written by the **deterministic gate in
Java** (ADR-006), never copied from model output.

### `approvals`
`id` · `decision_id` FK · `workspace_id` FK · `assigned_role` · `approver_id` FK NULLABLE ·
`status` CHECK (`PENDING`,`APPROVED`,`REJECTED`) · `comment` · `created_at` · `resolved_at`.

### `audit_events` *(append-only)*
`id` · `workspace_id` FK NULLABLE (null for global events like login) · `actor_id` FK NULLABLE ·
`event_type` · `resource_type` · `resource_id` · `correlation_id` · `metadata` JSONB ·
`ip_address` · `occurred_at`.

Append-only enforced by a `BEFORE UPDATE OR DELETE` trigger that raises an exception. Metadata
never contains secrets or document contents.

---

## Relationships

```
users ──< workspace_members >── workspaces
workspaces ──< documents ──< document_chunks
documents ──< knowledge_sources
documents ──> documents (supersedes)
workspaces ──< decision_requests ──< decision_runs ──< agent_executions
                                          ├──< evidence >──< findings
                                          └──1 decisions ──< approvals
workspaces ──< audit_events
```

## Indexes (minimum set)

```sql
CREATE UNIQUE INDEX uq_users_email          ON users (email);
CREATE INDEX idx_documents_ws_status        ON documents (workspace_id, status);
CREATE INDEX idx_documents_ws_created       ON documents (workspace_id, created_at DESC);
CREATE INDEX idx_documents_ws_type_current  ON documents (workspace_id, document_type, is_current);
CREATE INDEX idx_chunks_document            ON document_chunks (document_id, chunk_index);
CREATE INDEX idx_chunks_workspace           ON document_chunks (workspace_id);
CREATE INDEX idx_chunks_embedding           ON document_chunks USING hnsw (embedding vector_cosine_ops)
                                               WITH (m = 16, ef_construction = 64);
CREATE INDEX idx_requests_ws_status         ON decision_requests (workspace_id, status, created_at DESC);
CREATE INDEX idx_runs_request               ON decision_runs (decision_request_id);
CREATE INDEX idx_agent_exec_run             ON agent_executions (decision_run_id, sequence_index);
CREATE INDEX idx_evidence_run               ON evidence (decision_run_id);
CREATE INDEX idx_findings_run               ON findings (decision_run_id);
CREATE INDEX idx_approvals_status           ON approvals (status, created_at DESC);
CREATE INDEX idx_audit_ws_time              ON audit_events (workspace_id, occurred_at DESC);
CREATE INDEX idx_audit_resource             ON audit_events (resource_type, resource_id);
```

Every tenant-scoped index leads with `workspace_id`. Add an index only with a query that needs it.

## Workspace isolation

Every tenant-scoped table carries `workspace_id NOT NULL`. Every query filters on it **in SQL**.
`document_chunks.workspace_id` is denormalised on purpose: vector search must not need a join to
apply the tenant predicate.

A query missing its `workspace_id` predicate is a security defect. Reject it in review.

## Vector search

Cosine distance on normalized 384-dim vectors, `<=>` operator, HNSW index. `ef_search` tuned at
query time. HNSW cannot pre-filter by tenant — Phase 3 must measure over-fetch + post-filter vs
partial indexes and record the outcome in `docs/AI/RAG.md`.

```sql
SET LOCAL hnsw.ef_search = 100;
SELECT c.id, c.content, c.section, c.page_number, d.name, d.version,
       1 - (c.embedding <=> $1::vector) AS similarity
FROM document_chunks c
JOIN documents d ON d.id = c.document_id
WHERE c.workspace_id = $2
  AND d.status = 'READY'
  AND ($3::text IS NULL OR d.document_type = $3)
ORDER BY c.embedding <=> $1::vector
LIMIT $4;
```

Never `SELECT embedding` unless the vector itself is needed.

## Immutability

`audit_events` — append-only, trigger-enforced. `decision_runs` and `agent_executions` — terminal
state written once, never edited. This is what makes the audit story credible rather than claimed.

## Transactions

One business operation, one short transaction. No HTTP, LLM or file I/O inside. Kafka publish after
commit. Idempotency marker inside the same transaction as its side effect. Default `READ COMMITTED`.
