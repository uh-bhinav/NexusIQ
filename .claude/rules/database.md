# Rules: Database

Scope: migrations, schema, queries, pgvector. Schema reference: `docs/DATABASE/SCHEMA.md`.

## Authority

- **PostgreSQL is the system of record.** Redis holds nothing that cannot be rebuilt from Postgres.
- **Flyway, inside `spring-api`, owns every migration.** The Python service never runs DDL and
  never creates a migration. The one exception is LangGraph's own `langgraph` schema, created by
  its checkpointer at startup and explicitly out of Flyway's scope.

## Migrations

- Location: `backend/spring-api/src/main/resources/db/migration/`.
- Naming: `V{n}__{snake_case_description}.sql`, sequential, never reused.
- **Immutable once committed.** Applied migrations are never edited — write a new one.
- Every migration must be forward-only and safe to run on a non-empty database.
- Destructive changes (drop column/table, narrow a type) need an ADR and a two-step deploy
  (stop writing → migrate → stop reading).
- `V1` installs extensions: `CREATE EXTENSION IF NOT EXISTS vector;` and `pgcrypto` (for `gen_random_uuid()`).
- Every migration gets a Testcontainers test that runs it against a clean DB.

## Naming

`snake_case` throughout. Tables plural (`decision_runs`). PK `id UUID DEFAULT gen_random_uuid()`.
FK `{singular_table}_id`. Timestamps `created_at`/`updated_at` as `TIMESTAMPTZ NOT NULL`.
Booleans `is_`/`requires_`/`has_`. Enums stored as `VARCHAR` + `CHECK` constraint (not PG enum
types — they are painful to alter). Indexes `idx_{table}_{cols}`, uniques `uq_{table}_{cols}`.

## Workspace isolation

- **Every** tenant-scoped table has `workspace_id UUID NOT NULL REFERENCES workspaces(id)`.
- **Every** index on such a table leads with `workspace_id`.
- **Every** query filters on `workspace_id` in SQL. Not in Java. Not in Python. Not in React.
- Tables that are tenant-scoped: documents, document_chunks, knowledge_sources, decision_requests,
  decision_runs, agent_executions, evidence, findings, decisions, approvals, audit_events.
- A query missing its `workspace_id` predicate is a **security defect**, not a performance issue.
  Code review must reject it.

## Constraints over convention

Enforce in the schema what must always be true: `NOT NULL`, `CHECK` on enum columns and on
`confidence BETWEEN 0 AND 1`, `UNIQUE` where identity demands it, FKs with deliberate
`ON DELETE` semantics. Do not rely on application code for invariants the DB can hold.

## Append-only tables

`audit_events` is append-only: no `UPDATE`, no `DELETE` from application code. Enforce with a
DB trigger (`RAISE EXCEPTION`) plus a repository exposing only insert/read.

`decision_runs` and `agent_executions` are immutable once completed — write terminal state once.

## pgvector

- Column: `embedding vector(384)` — matches `BAAI/bge-small-en-v1.5`. Changing the model changes
  the dimension and requires a migration + full re-embed. See ADR-009.
- Store `embedding_model` and `embedding_version` on every chunk row.
- Distance: **cosine** (`<=>`), with normalized vectors. Be consistent — mixing operators silently
  wrecks ranking.
- Index: HNSW.
  ```sql
  CREATE INDEX idx_document_chunks_embedding
    ON document_chunks USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
  ```
  Build it **after** bulk load where practical. Tune `hnsw.ef_search` at query time.
- HNSW cannot pre-filter by tenant, so filtered vector search must combine a `workspace_id`
  predicate with an over-fetch (`LIMIT k*4`) then post-filter, or use partial indexes if tenant
  count stays small. Measure; document what you chose in `docs/AI/RAG.md`.
- Never `SELECT embedding` unless you need the vector — it is 1.5 KB of wasted I/O per row.

## Indexing

Index for the queries that actually exist. Required from day one:
- `documents(workspace_id, status)`, `documents(workspace_id, created_at DESC)`
- `document_chunks(document_id, chunk_index)`
- `decision_requests(workspace_id, status, created_at DESC)`
- `approvals(status, created_at DESC)` for the approval queue
- `audit_events(workspace_id, occurred_at DESC)`, `audit_events(resource_type, resource_id)`
- `processed_events(event_id, consumer_group)` UNIQUE — idempotency

Add an index only with a query to justify it. Remove ones nothing uses.

## Query rules

- No `SELECT *` in application queries.
- No N+1: use `JOIN FETCH` / `@EntityGraph`, and assert query counts in tests for hot paths.
- All list endpoints paginate. No unbounded result sets, ever.
- Bulk chunk insert uses batched multi-row inserts, not per-row round trips.
- Anything slower than 100 ms locally gets an `EXPLAIN ANALYZE` before it is accepted.

## Transactions

- One business operation = one transaction. Short.
- No HTTP, LLM, or file I/O inside a transaction.
- Kafka publish happens **after commit** (see `.claude/rules/architecture.md`).
- Default isolation (`READ COMMITTED`) unless a documented reason says otherwise.

## Local data

`docker compose down -v` destroys the volume. Anything that must survive is a migration or a seed
script (`scripts/seed.sh`), never manual `psql` in someone's terminal.
