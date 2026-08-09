# NexusIQ — Implementation Roadmap

Fourteen phases, built strictly in order. Each phase is only complete when **every** acceptance
criterion is demonstrated with evidence, tests pass, and `STATUS.md` is updated.

Use `/implement-phase <n>`. Do not build ahead. Do not skip verification.

**Estimates assume part-time solo work with an AI agent and are planning aids, not commitments.**

| # | Phase | Est. | Gate |
|---|---|---|---|
| 0 | Repository & environment | 1–2 d | `docker compose up` → all healthy |
| 1 | Java backend foundation | 4–6 d | register → login → workspace → member |
| 2 | Document ingestion | 4–6 d | PDF → chunks + embeddings in pgvector |
| 3 | RAG retrieval | 3–5 d | cited, ranked, tenant-scoped results |
| 4 | First agent (intent) | 2–3 d | structured intent, schema-validated |
| 5 | LangGraph workflow | 5–7 d | end-to-end recommendation with evidence |
| 6 | Validation & guardrails | 3–5 d | ungrounded claims rejected; injection ignored |
| 7 | Human approval | 3–4 d | escalation → approve/reject → final + audit |
| 8 | Observability | 3–4 d | one trace spans all services; cost per agent |
| 9 | Frontend | 7–10 d | full demo runnable from the UI |
| 10 | Testing & evaluation | 4–6 d | ≥30-case eval suite with baseline numbers |
| 11 | CI/CD | 2–3 d | green pipeline on push |
| 12 | Local deployment hardening | 2–3 d | clean-machine reproducible in one command |
| 13 | Kubernetes (optional) | 3–4 d | stack running on kind |

---

## Phase 0 — Repository & environment

**Objective.** A reproducible local stack and repository skeleton. No application logic.

**Depends on.** Nothing.

**Deliverables.**
- Directory skeleton per `docs/ARCHITECTURE.md` (`backend/spring-api`, `ai-service`,
  `frontend/web`, `infrastructure/{docker,compose,k8s}`, `scripts`, `.github/workflows`).
- `docker-compose.yml`: `postgres` (16 + pgvector), `redis`, `kafka` (KRaft, single node),
  `kafka-ui`, `otel-collector`, with healthchecks, named volumes, one network.
  Service containers for `spring-api`/`ai-service`/`frontend` are added by their own phases.
- `Makefile`: `setup up down logs ps migrate seed test lint clean`.
- `scripts/check-prereqs.sh` — verifies Java 21, Maven 3.9+, Python 3.11+, Node 20+, Docker
  Compose v2; **fails loudly with install instructions**. (This machine currently defaults to
  Java 8 and has no Maven — the script must catch that.)
- `.env` created from `.env.example`; `docs/OPERATIONS/LOCAL_DEV.md` completed.
- Root `README.md` with the 3-command quickstart.

**Acceptance criteria.**
1. `make up` on a clean checkout → all containers `healthy`, none restarting.
2. `psql` connects; `CREATE EXTENSION vector;` succeeds; version recorded in LOCAL_DEV.md.
3. Kafka reachable from host and container network; kafka-ui lists the broker.
4. Redis `PING` → `PONG`.
5. OTel collector accepts an OTLP test span without error.
6. `make down` then `make up` → stack returns healthy with data intact.
7. `check-prereqs.sh` fails correctly on a missing prerequisite.

**Tests.** Manual verification with recorded command output. No unit tests yet.

**Risks.** Kafka KRaft single-node config on Apple Silicon; pgvector image tag choice; host
port conflicts with other local stacks.

---

## Phase 1 — Java backend foundation

**Objective.** Authenticated, authorised, multi-tenant CRUD backend. No AI, no Kafka yet.

**Depends on.** Phase 0.

**Deliverables.**
- Spring Boot project (version confirmed from `start.spring.io` at scaffold time, **not** from
  memory), Java 21, Maven wrapper committed.
- Flyway `V1`–`V4`: extensions, `users`, `workspaces`+`workspace_members`, `documents`,
  `audit_events` (+ append-only trigger).
- Spring Security: JWT access/refresh, BCrypt, method security, four roles.
- `WorkspaceAccessService`; every workspace-scoped query filters on `workspace_id` in SQL.
- Endpoints: auth (register/login/refresh/me), workspaces (create/list/get/members),
  documents (metadata create/list/get/delete — no upload processing yet), audit (list).
- Global exception handler with the standard error envelope; correlation-id filter; structured
  JSON logging; Actuator health/metrics/prometheus; OpenAPI/Swagger UI.

**Acceptance criteria.**
1. Register → login → JWT returned; `/me` resolves the user.
2. Expired/invalid/absent token → `401` with the standard error envelope.
3. Create workspace, add a member with a role; non-members get `404` on that workspace.
4. **A user in workspace B cannot read workspace A's document metadata (`404`, not `403`).**
5. `VIEWER` cannot create a workspace (`403`).
6. Every mutation writes an `audit_events` row; `UPDATE`/`DELETE` on it raises a DB error.
7. Validation failure returns `400` with per-field `details`.
8. Swagger UI lists every endpoint with schemas.
9. Every response carries `request_id`; it appears in the logs.

**Tests.** Unit tests for every service with a branch; `@DataJpaTest` + Testcontainers for custom
queries and every migration; `@SpringBootTest` per controller — happy path, validation failure,
unauthenticated, cross-tenant denial.

**Risks.** Spring Boot version drift vs. tutorials; JWT refresh-rotation edge cases; getting
tenant filtering into SQL rather than post-fetch (this is the one to be strict about).

---

## Phase 2 — Document ingestion

**Objective.** Upload a real document and end up with embedded, queryable chunks.

**Depends on.** Phase 1.

**Deliverables.**
- Java: multipart upload with magic-byte type validation, size limit, checksum, UUID storage
  names, `DocumentStorage` abstraction (`LocalStorage` impl), versioning.
- Kafka wiring both sides: envelope record, producer, idempotent consumers, `processed_events`
  table, retry policy, DLQ topics. Topics `document.uploaded` / `.processed` / `.failed`.
- Python `ai-service` skeleton: FastAPI, config via pydantic-settings, SQLAlchemy async,
  health/ready, Kafka consumer, OTel bootstrap.
- Ingestion pipeline: extract (PDF/DOCX/TXT/MD) → clean → detect sections → **hierarchical
  chunking** with section/page metadata → injection heuristic scan → embed
  (`BAAI/bge-small-en-v1.5`, local, batched) → bulk insert `document_chunks`.
- Flyway `V5`: `document_chunks` with `embedding vector(384)`, `embedding_model`,
  `embedding_version`, HNSW cosine index.

**Acceptance criteria.**
1. Upload a 20-page PDF → `202` → status transitions `UPLOADED → PROCESSING → READY` within 60 s.
2. `document_chunks` populated with non-null embeddings, correct dimension, section and page set.
3. Chunks carry the embedding model name and version.
4. A raw `SELECT` with a `<=>` cosine query returns sensible neighbours.
5. Duplicate `document.uploaded` (same `event_id`) → chunks written exactly once.
6. Corrupt/unsupported file → `document.failed`, status `FAILED`, reason visible via API.
7. A poison message hits the DLQ after 3 attempts and is visible in kafka-ui.
8. A file whose extension lies about its content is rejected.
9. Uploading a `.md` containing an injection string → chunk flagged.

**Tests.** Chunker unit tests (section boundaries, overlap, metadata); extractor tests per format;
embedding determinism test; Testcontainers Kafka duplicate-delivery test; ingestion integration
test against Testcontainers Postgres with pgvector.

**Risks.** PDF extraction quality (choose the library deliberately and record it); model download
size and cold start in Docker; chunk boundaries destroying policy-section semantics — this
directly caps retrieval quality, so spend the time here.

---

## Phase 3 — RAG retrieval

**Objective.** Given a question, return ranked, cited, tenant-scoped evidence.

**Depends on.** Phase 2.

**Deliverables.**
- Vector search with cosine similarity over pgvector, always filtered by `workspace_id`.
- Metadata filtering: document type, version (prefer current), date, policy category.
- Reranking stage (`BAAI/bge-reranker-base`, toggleable via `RERANKER_ENABLED`).
- Context assembly with priority ordering and a token budget.
- `POST /internal/search` on the AI service; `GET /api/v1/workspaces/{id}/knowledge/search` on the
  API, proxied and authorised.
- Redis caching keyed by `workspace_id + normalized query + filters`.
- Retrieval metrics: latency, result count, similarity distribution, empty-result rate.

**Acceptance criteria.**
1. A question about data residency returns the residency policy chunks in the top 3.
2. Every result carries `chunk_id`, `document_id`, document name, section, page, similarity.
3. **Workspace B's chunks never appear in a workspace A query** — asserted with seeded data.
4. Results below `RETRIEVAL_MIN_SIMILARITY` are excluded; an all-below query returns empty
   rather than noise.
5. When two policy versions match, the current version ranks above the superseded one.
6. Retrieval completes < 1 s on the sample corpus.
7. Second identical query is served from cache (metric proves it).
8. Reranking measurably changes ordering; both modes benchmarked and recorded.

**Tests.** Seeded-corpus ranking tests; tenant-isolation negative test; empty-result test; cache
key includes tenant (test that it does); latency assertion.

**Risks.** HNSW cannot pre-filter by tenant — decide over-fetch vs partial index, measure, and
record the choice in `docs/AI/RAG.md`. Reranker adds latency; measure before adopting.

---

## Phase 4 — First agent (intent analyzer)

**Objective.** One agent, done properly, before any orchestration exists.

**Depends on.** Phase 3.

**Deliverables.**
- `ModelProvider` abstraction + `gemini` adapter + `mock` adapter (fixtures, test-only).
- Structured output via the provider's schema mode; `IntentAnalysis` Pydantic model.
- `prompts/intent_v1.md` with the standing prompt-injection clause.
- Token/cost/latency capture per call; `llm/pricing.py` with a dated pricing table.
- `POST /internal/agents/intent` for isolated testing.

**Acceptance criteria.**
1. *"Should Vendor Alpha be approved for EU production?"* → `decision_type=vendor_approval`,
   entities `["Vendor Alpha"]`, jurisdiction `EU`, environment `production`, required domains
   include security, data residency, procurement.
2. A vague question populates `missing_information` instead of inventing specifics.
3. Output always validates against the schema; an invalid response triggers exactly one repair
   retry, then fails cleanly.
4. Tokens, cost, latency and model name recorded for every call.
5. Switching `LLM_PROVIDER=mock` works with **zero** code changes.
6. A question containing *"ignore your instructions"* is classified normally, not obeyed.

**Tests.** Fixture-driven agent tests; malformed-output tests (invalid JSON, missing field, bad
enum); provider-swap test; cost calculation test.

**Risks.** Provider structured-output APIs differ — the abstraction must not leak Gemini's shape.

---

## Phase 5 — LangGraph multi-agent workflow

**Objective.** The full reasoning pipeline producing an evidence-backed recommendation.

**Depends on.** Phase 4.

**Deliverables.**
- `DecisionState` TypedDict; graph builder; Postgres checkpointer (`langgraph` schema).
- Nodes: `context_planner`, `retrieval`, `policy_analyst`, `risk_analyzer`, `decision`.
  Policy and risk run in parallel.
- Schemas: `ContextPlan`, `PolicyFinding` (with `UNKNOWN`), `RiskAssessment`, `Recommendation`
  (with `INSUFFICIENT_INFORMATION`), all carrying `evidence_ids`.
- Kafka: consume `decision.requested`; emit `decision.progress` per node and
  `decision.completed`/`failed`.
- Java: persist `decision_runs`, `agent_executions`, `evidence`, `findings`, `decisions` from
  those events; `GET /decisions/{id}` returns the full assembled result.
- Versioned prompts; `workflow_version` stamped on every run.

**Acceptance criteria.**
1. Submitting the vendor question produces a complete recommendation with confidence, risk,
   per-policy findings and citations, end to end through Kafka.
2. **Every finding and the recommendation carry `evidence_ids` that resolve to retrieved chunks.**
3. The EU-residency question returns `UNKNOWN` for that policy — the corpus deliberately omits it.
4. A question with no supporting evidence returns `INSUFFICIENT_INFORMATION`.
5. `agent_executions` has one row per node with latency, tokens, cost.
6. Policy and risk demonstrably run in parallel (span overlap in the trace).
7. Exceeding `MAX_WORKFLOW_COST_USD` or `MAX_WORKFLOW_TOKENS` stops the run.
8. Killing the AI service mid-run and restarting resumes from the checkpoint rather than
   restarting from zero.
9. Total run cost recorded and under budget for the sample question.

**Tests.** Per-node isolated tests with constructed state; graph routing tests (which edge, given
what); termination proof; parallel-branch test; budget-exceeded test; checkpoint resume test;
full flow with the `mock` provider.

**Risks.** This is the largest phase — build node by node, verifying each in isolation before
wiring the next edge. Parallel node state merging is the classic LangGraph footgun.

---

## Phase 6 — Validation & guardrails

**Objective.** Make ungrounded output structurally unable to ship.

**Depends on.** Phase 5.

**Deliverables.**
- `validator` node: evidence grounding, citation validity (IDs exist in the retrieved set),
  domain completeness, contradiction against retrieved policy, confidence justification,
  unsupported-fact detection. Output `ValidationResult` with per-check status.
- Retry edge back to retrieval/decision, capped at `MAX_AGENT_ITERATIONS`, then forced escalation.
- Input guardrails: malformed request, unsupported decision type, injection heuristics, oversize.
- Retrieval guardrails: tenant scope, minimum relevance, authoritative-source preference.
- Output guardrails: schema, citations, contradictions, unsafe recommendation.
- Workflow guardrails: iteration cap, timeout, token budget, cost budget.
- `PROMPT_INJECTION_ATTEMPT` finding category, surfaced through the API.

**Acceptance criteria.**
1. A fabricated citation id is caught; the run does not finalise with it.
2. A recommendation contradicting a retrieved `VIOLATED` finding is rejected by the validator.
3. Validation failure retries **at most twice**, then escalates — proven by a forced-failure test.
4. The injected vendor report does not influence the recommendation and **does** raise a
   `PROMPT_INJECTION_ATTEMPT` finding.
5. Evidence coverage below `HITL_MIN_EVIDENCE_COVERAGE` forces human review.
6. Workflow timeout terminates the run cleanly with a reason.
7. `validation_failure_rate` is emitted as a metric.

**Tests.** Adversarial fixtures: hallucinated citation, contradictory recommendation, empty
evidence with high confidence, injected document, infinite-retry attempt. Each is a named test.

**Risks.** An over-strict validator escalates everything and the demo looks broken; an over-lax one
defeats the purpose. Tune against the evaluation set, not against vibes.

---

## Phase 7 — Human approval

**Objective.** Close the loop with an authorised human.

**Depends on.** Phase 6.

**Deliverables.**
- Deterministic gate in Java: `VIOLATED` finding ∨ risk ≥ `HITL_ESCALATE_ON_RISK` ∨
  confidence < `HITL_MIN_CONFIDENCE` ∨ coverage < threshold ∨ validation escalation → approval
  required. Zero LLM involvement.
- Flyway: `approvals`; endpoints `GET /approvals`, `POST /approvals/{id}/approve|reject`.
- `approval.requested` / `approval.completed`; LangGraph `interrupt()` and resume on completion.
- Separation of duties: requester cannot approve; only `APPROVER`/`ADMIN` may act.
- Full audit trail of the approval action.

**Acceptance criteria.**
1. A low-confidence decision lands in the approval queue with the reason stated.
2. A high-confidence, fully-satisfied decision finalises **without** human approval.
3. Approving sets `final_status`, resumes the graph, and finalises the decision.
4. Rejecting records the rejection and reasoning; status is terminal.
5. **The requester cannot approve their own decision (`403`).**
6. A `VIEWER`/`ANALYST` cannot act on the queue (`403`).
7. Every approval action appears in the audit log with actor and timestamp.
8. A duplicate `approval.completed` does not double-resume the run.

**Tests.** Gate unit tests across the threshold matrix; separation-of-duties test; resume test;
duplicate-event test; end-to-end escalate → approve → finalise.

**Risks.** Resume semantics after a service restart; making sure the gate is genuinely
deterministic and not quietly reading a model-produced boolean.

---

## Phase 8 — Observability

**Objective.** One trace, end to end, with real cost and latency attribution.

**Depends on.** Phase 7.

**Deliverables.**
- OTel Java agent + SDK; OTel Python SDK; collector pipeline; local Jaeger/Tempo + Prometheus +
  Grafana in Compose (all free, local).
- Correlation-id propagation: HTTP → MDC → Kafka envelope → Python context → node spans.
- Custom spans per agent node, per retrieval, per LLM call (with model, tokens, cost attributes).
- Metrics: the four groups in `docs/ARCHITECTURE.md` §9.
- `GET /api/v1/metrics/summary` powering the dashboard.
- One Grafana dashboard: decisions, escalation rate, agent latency, cost, retrieval quality.

**Acceptance criteria.**
1. A single trace shows HTTP → Kafka → AI service → each agent node → each LLM call.
2. The same `correlation_id` appears in Java logs, Python logs and the trace.
3. Per-agent token and cost attribution is visible and sums to the run total.
4. Cache hit ratio, consumer lag and DB latency are all visible.
5. A forced failure surfaces as an error span with the reason.
6. The Grafana dashboard renders real data from a real run.

**Tests.** Trace-continuity integration test (assert one trace id across services); metric
existence assertions; log-correlation test.

**Risks.** Context propagation across the Kafka boundary is the standard failure point — the
envelope must carry the trace context explicitly.

---

## Phase 9 — Frontend

**Objective.** Make the whole system demonstrable from a browser.

**Depends on.** Phase 8 (Phases 1–3 unblock partial work if needed).

**Deliverables.**
- React + TS + Vite + Tailwind/shadcn + TanStack Query; typed API client with Zod validation.
- Pages: Login · Dashboard · Knowledge Base · Document detail · Decision Requests ·
  Decision Detail · Approval Queue · Audit Log · System Metrics.
- SSE live workflow trace with reconnect, terminal-close, cleanup and poll fallback.
- Decision detail: recommendation, confidence, risk, findings, evidence with clickable citations,
  agent timeline, tokens, cost, latency, validation result, approval state, audit history.
- Loading / empty / error / populated states everywhere. Role-based route guards.

**Acceptance criteria.**
1. The entire demo (spec §8, steps 1–12) is performable from the UI alone.
2. The SSE trace updates live as agents complete; reconnect works after a network drop.
3. Every citation resolves to the source document and chunk; unresolvable ones warn explicitly.
4. A `VIEWER` sees no approve buttons **and** a direct API call from them still fails server-side.
5. Every page renders correctly with zero data (empty state) and with a failed request (error).
6. `npm run build` and `tsc --noEmit` are clean; no `any`.
7. No mock data anywhere in the app.

**Tests.** Vitest + RTL + MSW per feature: populated, empty, error, primary action.

**Risks.** Scope creep into visual design — explicitly out of scope. SSE lifecycle leaks.

---

## Phase 10 — Testing & evaluation

**Objective.** Prove quality with numbers, not assertions.

**Depends on.** Phase 9.

**Deliverables.**
- Coverage gaps closed across all three services.
- The 14 failure-scenario tests in `.claude/rules/testing.md`, each named and passing.
- E2E test: upload → ingest → decide → validate → escalate → approve → audit.
- Evaluation harness + **≥30 labelled cases** in `ai-service/evaluation/datasets/`, covering
  clean approval, conditional approval, rejection, unknown, conflicting versions, no evidence,
  injection, out-of-scope question.
- Metrics: recall@5/@10, precision@5, MRR, groundedness, citation validity, hallucination rate,
  decision accuracy, escalation precision/recall.
- Baseline report committed at `docs/AI/EVALUATION_BASELINE.md`.
- A/B comparison of at least two model configurations (e.g. flash vs pro on the synthesis node)
  with accuracy/latency/cost recorded.

**Acceptance criteria.**
1. `make test` runs everything and passes.
2. `make eval` produces a metrics report; numbers are committed as the baseline.
3. All 14 failure scenarios have passing named tests.
4. The A/B comparison yields a stated, quantified trade-off.
5. No test is skipped or weakened to achieve green.

**Risks.** Writing an eval set that only confirms what already works. Deliberately include cases
the system currently gets wrong, and record them.

---

## Phase 11 — CI/CD

**Objective.** Every push is verified automatically.

**Depends on.** Phase 10.

**Deliverables.** GitHub Actions: lint → unit → integration (Testcontainers) → build → Docker build
→ evaluation (mock provider, deterministic) → security scan (`pip-audit`, `npm audit`,
dependency-check, Trivy). Path-filtered jobs, dependency caching, branch protection.

**Acceptance criteria.** Green on a clean push; a deliberately broken commit fails the right job;
Docker images build for all three services; total runtime < 15 min; secret scanning enabled;
no secret is ever printed in a log.

**Risks.** Testcontainers in CI (Docker-in-Docker) and runtime cost — parallelise and cache.

---

## Phase 12 — Local deployment hardening

**Objective.** Anyone can run the whole thing, cleanly, from a fresh clone. **$0. No cloud.**

**Depends on.** Phase 11.

**Deliverables.**
- Production-style multi-stage Dockerfiles (non-root, slim, healthchecked) for all three services.
- `docker-compose.yml` (dev) + `docker-compose.prod.yml` (built images, resource limits).
- One-command bootstrap: `make demo` → stack up → migrate → seed corpus → demo user ready.
- `docs/OPERATIONS/RUNBOOK.md` and a demo script.
- Backup/restore script for the Postgres volume.

**Acceptance criteria.**
1. Fresh clone on a clean machine → `make demo` → full demo runnable, documented time-to-ready.
2. Full stack memory footprint measured and recorded; runs on 16 GB.
3. Every container restarts cleanly; no data loss across `down`/`up`.
4. `docs/sample-enterprise/` seeds automatically and reproducibly.
5. RUNBOOK covers the five most likely failures with fixes.

**Risks.** Embedding-model download on first run — pre-bake it into the image or cache the volume,
and document which.

---

## Phase 13 — Kubernetes (optional)

**Objective.** Kubernetes as a deployment artifact and learning target. **Not** a hosted
environment, not a paid one.

**Depends on.** Phase 12.

**Deliverables.** Manifests (Deployment, Service, ConfigMap, Secret, Ingress, PVC, HPA where
sensible), or a Helm chart, for all services + infrastructure; verified on `kind`; readiness and
liveness probes; resource requests/limits; a `kind` bootstrap script.

**Acceptance criteria.** `kind` cluster runs the full stack; the E2E demo passes against it;
rolling update works; a killed pod recovers; secrets come from `Secret`, never a manifest literal.

**Risks.** Stateful services on kind; effort is easy to sink here for little marginal value —
Phases 0–12 are what matter. Ship this only if 0–12 are genuinely done.

---

## Cross-cutting rules

Every phase ends with: acceptance criteria demonstrated with evidence · tests written and passing ·
`.env.example` updated if config changed · `STATUS.md` and `TODO.md` updated · an ADR written for
any significant decision made along the way.

**A phase is not complete because the code exists.** See "Definition of done" in `CLAUDE.md`.
