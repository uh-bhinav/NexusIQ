# API Design

Conventions and cross-cutting contracts. **Swagger/OpenAPI at `/swagger-ui.html` is the executable
reference** for endpoint schemas — this document does not duplicate it.

Implementation rules: `.claude/rules/backend-java.md`.

---

## Conventions

- Base path `/api/v1`. Version in the path. v1 is never broken silently — additive changes only.
- Plural resource nouns, kebab-case paths, `snake_case` JSON (Jackson configured globally; never
  mixed).
- `POST` → `201` + `Location`; `DELETE` → `204`; long-running work → `202` + resource id + status.
- All list endpoints paginate. There is no unbounded collection endpoint.
- Times are RFC 3339 UTC. IDs are UUIDs.

## Authentication

Bearer JWT. Access token 1 h, refresh 7 d.

```
POST /api/v1/auth/register     → 201 {user}
POST /api/v1/auth/login        → 200 {access_token, refresh_token, expires_in, user}
POST /api/v1/auth/refresh      → 200 {access_token, ...}
GET  /api/v1/auth/me           → 200 {user, workspaces[]}
```

`login` and `register` are rate-limited and give identical responses/timing for unknown-email and
wrong-password.

## Authorization

Two layers, both server-side: global role (`ADMIN`/`ANALYST`/`APPROVER`/`VIEWER`) and workspace
membership. Resources outside the caller's workspaces return **`404`, not `403`** — existence is
not disclosed across tenants.

Client-supplied `workspace_id` is resolved and authorised before use, never trusted.

## Endpoints

```
# Workspaces
POST   /workspaces                          create
GET    /workspaces                          list (caller's memberships only)
GET    /workspaces/{id}
POST   /workspaces/{id}/members             add member (ADMIN)
GET    /workspaces/{id}/members
DELETE /workspaces/{id}/members/{userId}    (ADMIN)

# Documents
# Nested under the workspace path for every operation, not just create/list —
# so a lookup by document id always filters on workspace_id in SQL rather than
# fetching the document globally and checking membership afterward
# (.claude/rules/security.md, .claude/rules/database.md). Phase 1 ships metadata
# CRUD only (JSON body, no file content); Phase 2 upgrades POST to a real
# multipart upload with the same URL shape.
POST   /workspaces/{id}/documents               metadata create (Phase 1) → multipart upload (Phase 2 ✅)
GET    /workspaces/{id}/documents               paginated, filter by status/type
GET    /workspaces/{id}/documents/{documentId}
GET    /workspaces/{id}/documents/{documentId}/chunks   paginated (citation resolution) — Phase 3, not Phase 2:
                                                          Phase 2's 9 acceptance criteria (roadmap) are all
                                                          about the ingestion pipeline itself; chunk retrieval
                                                          belongs with RAG search
DELETE /workspaces/{id}/documents/{documentId}

# Knowledge search
POST   /workspaces/{id}/knowledge/search    {query, filters, top_k} → cited results

# Decisions
POST   /decisions                           {workspace_id, title, question, priority} → 202
GET    /decisions                           paginated, filter by status/workspace
GET    /decisions/{id}                      full result: recommendation, findings, evidence, run
GET    /decisions/{id}/run                  agent executions, tokens, cost, latency
GET    /decisions/{id}/stream               SSE live progress

# Approvals (all under /workspaces/{workspaceId}/, like decisions above)
GET    /approvals?status=PENDING            queue, any workspace member may view
POST   /approvals/{id}/approve              {notes}          — APPROVER/ADMIN only, 403 if self-requested
POST   /approvals/{id}/reject               {reason}         — reason required; same restrictions
                                             409 if the approval is already resolved

# Audit
GET    /audit                               paginated, filter by workspace/actor/type/date
GET    /audit/resource/{type}/{id}          full history for one resource

# Metrics
GET    /metrics/summary                     dashboard aggregates
GET    /actuator/health | /actuator/prometheus
```

## Request/response patterns

Pagination:
```
GET /decisions?page=0&size=20&sort=created_at,desc

{ "content": [...], "page": 0, "size": 20,
  "total_elements": 137, "total_pages": 7 }
```

Async submission:
```
POST /decisions → 202
{ "decision_id": "uuid", "status": "PROCESSING", "request_id": "uuid" }
```
The client then subscribes to the SSE stream or polls `GET /decisions/{id}`.

## Errors

One envelope, always:

```json
{
  "timestamp": "2026-08-09T14:32:11Z",
  "status": 400,
  "error": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "path": "/api/v1/decisions",
  "request_id": "0f9c...",
  "details": [{ "field": "question", "issue": "must not be blank" }]
}
```

`VALIDATION_ERROR` 400 · `UNAUTHORIZED` 401 · `FORBIDDEN` 403 · `NOT_FOUND` 404 · `CONFLICT` 409 ·
`PAYLOAD_TOO_LARGE` 413 · `UNSUPPORTED_MEDIA_TYPE` 415 · `RATE_LIMITED` 429 · `INTERNAL_ERROR` 500 ·
`SERVICE_UNAVAILABLE` 503 (Kafka or AI service unavailable).

Never leak stack traces, SQL, internal paths, or whether a resource exists in another tenant.

## Idempotency

Mutating endpoints that a client may retry accept `Idempotency-Key`. The same key with the same
body returns the original response rather than acting twice. Required on `POST /decisions` and
document upload.

## Correlation

Every request carries or is assigned `X-Correlation-Id`; it is echoed in the response header and as
`request_id` in every body and error. It propagates into Kafka envelopes, the AI service, agent
spans and logs — one id to follow a decision across the whole system.

## SSE

```
GET /api/v1/decisions/{id}/stream     Accept: text/event-stream
```

Events: `decision.status`, `agent.started`, `agent.completed`, `validation.completed`,
`approval.required`, `decision.completed`, `decision.failed`, plus periodic `heartbeat`.

```
event: agent.completed
data: {"node":"policy_analyst","status":"SUCCESS","latency_ms":2140,
       "input_tokens":3820,"output_tokens":410,"estimated_cost_usd":0.0021}
```

Server closes on a terminal event. Clients must reconnect with backoff and reconcile via
`GET /decisions/{id}` — never assume the stream was complete. A poll fallback is required.

Auth: the JWT is required; if `EventSource` cannot set headers, use a short-lived single-use stream
token issued by the API — **never** put the access token in a query string that lands in logs.

## Internal API (AI service — not public)

Not internet-facing; requires `INTERNAL_SERVICE_TOKEN`. Always receives an already-authorised
`workspace_id` and still scopes every query by it.

```
POST /internal/search              retrieval with filters
POST /internal/agents/intent       isolated agent testing
GET  /health | /ready
```

## Versioning

Additive changes (new optional field, new endpoint) go into v1. Removals, renames, type changes and
semantic changes require `/api/v2` and an ADR. The frontend validates responses with Zod, so
contract drift surfaces as a clear error rather than a silent `undefined`.
