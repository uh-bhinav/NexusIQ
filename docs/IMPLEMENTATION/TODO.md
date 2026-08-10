# NexusIQ — Task Backlog

Actionable items only. Phase definitions live in `ROADMAP.md`; project state lives in `STATUS.md`.

Convention: `- [ ]` open · `- [x]` done · `- [!]` blocked · `- [~]` in progress.
Delete completed items once their phase closes — this is a working list, not a history.

---

## Now — unblock Phase 2

- [ ] Point the ai-service venv at Python 3.13 (`cd ai-service && uv venv --python 3.13`).
- [ ] Obtain a **Gemini API key** → `LLM_API_KEY` in `.env`. Not needed until Phase 4.
- [ ] Optionally add `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` to `~/.zshrc` so it's
      not needed per-command (Java 21 + Maven are installed; only the shell default is stale).

## Phase 0 — Repository & environment ✅ COMPLETE (2026-08-09)

- [x] Directory skeleton + module-scoped `CLAUDE.md` pointers
- [x] `docker-compose.yml`: postgres+pgvector, redis, kafka (KRaft), kafka-ui, otel-collector,
      kafka-init; healthchecks, named volumes, single network, pinned images
- [x] `infrastructure/docker/otel/collector-config.yaml`
- [x] `scripts/check-prereqs.sh` (infra | all)
- [x] `scripts/verify-stack.sh` — 19 acceptance checks
- [x] `Makefile` — 18 targets
- [x] `.env` created with generated local secrets; port block documented
- [x] `docs/OPERATIONS/LOCAL_DEV.md` completed with verified versions and ports
- [x] All 7 acceptance criteria verified with recorded evidence → `STATUS.md`

## Phase 1 — Java backend foundation ✅ COMPLETE (2026-08-10)

- [x] Scaffold from the live Initializr API; Spring Boot **4.1.0** confirmed against Maven
      Central (not `.RELEASE` — recorded in LOCAL_DEV.md)
- [x] Commit the Maven wrapper
- [x] Flyway V1 (extensions) → V4 (users, workspaces+members, documents+knowledge_sources,
      audit_events + append-only trigger)
- [x] JWT auth (access + refresh), BCrypt-12, role model, timing-safe login
- [x] `WorkspaceAccessService`; tenant filtering **in SQL** on every scoped query
- [x] Auth / workspace / document-metadata / audit endpoints
- [x] Global exception handler + standard error envelope
- [x] Correlation-id filter + structured JSON logging (MDC)
- [x] Actuator + OpenAPI/Swagger (springdoc 3.1.0 — the Framework-7 compatible line)
- [x] `JsonMapperBuilderCustomizer` for global snake_case (Jackson 3 needs this, not the
      classic `spring.jackson.*` YAML properties — see LOCAL_DEV.md)
- [x] `maven-failsafe-plugin` wired so `*IT` tests actually run (`mvn verify`, not `mvn test`)
- [x] 50 tests (29 unit + 21 integration) incl. the cross-tenant denial test at both the HTTP
      and repository level, and the audit append-only trigger proven against real Postgres
- [x] Verified all 9 acceptance criteria with evidence — see `STATUS.md`

## Phase 2 — Document ingestion

- [ ] Upload endpoint: magic-byte validation, size cap, checksum, UUID storage naming
- [ ] `DocumentStorage` abstraction + `LocalStorage`
- [ ] Kafka envelope, producers, idempotent consumers, `processed_events`, retry, DLQ
- [ ] AI service skeleton: FastAPI, config, async SQLAlchemy, health/ready, Kafka consumer
- [ ] Extractors (PDF/DOCX/TXT/MD) — **pick the PDF library deliberately, write the ADR**
- [ ] Hierarchical chunker with section/page metadata
- [ ] Injection heuristic scan at ingestion
- [ ] `EmbeddingProvider` + local bge-small-en-v1.5, batched
- [ ] Flyway V5: `document_chunks` + HNSW cosine index
- [ ] Verify all 9 acceptance criteria

## Phase 3 — RAG retrieval

- [ ] Vector search (cosine, workspace-scoped)
- [ ] Metadata filters incl. version preference
- [ ] Reranker stage (toggleable) + benchmark both modes
- [ ] Context assembly with priority ordering + token budget
- [ ] Search endpoints (internal + authorised public proxy)
- [ ] Redis cache with tenant in the key
- [ ] Retrieval metrics
- [ ] **Decide and document the pgvector tenant-filtering strategy**
- [ ] Verify all 8 acceptance criteria

## Phase 4 — Intent agent

- [ ] `ModelProvider` abstraction + Gemini adapter + mock adapter
- [ ] `IntentAnalysis` schema + `prompts/intent_v1.md`
- [ ] Token/cost/latency capture; `llm/pricing.py` with a dated table
- [ ] Malformed-output tests; provider-swap test
- [ ] Verify all 6 acceptance criteria

## Phase 5 — LangGraph workflow

- [ ] `DecisionState` + graph builder + Postgres checkpointer
- [ ] Nodes: context_planner, retrieval, policy_analyst ∥ risk_analyzer, decision
- [ ] All agent output schemas (with `UNKNOWN` / `INSUFFICIENT_INFORMATION`)
- [ ] `decision.requested` consumer; `decision.progress` / `.completed` producers
- [ ] Java persistence of runs, agent_executions, evidence, findings, decisions
- [ ] Budget enforcement; checkpoint resume
- [ ] Verify all 9 acceptance criteria

## Phase 6 — Validation & guardrails

- [ ] Validator node (6 checks) + `ValidationResult`
- [ ] Capped retry edge → forced escalation
- [ ] Input / retrieval / output / workflow guardrails
- [ ] `PROMPT_INJECTION_ATTEMPT` finding surfaced through the API
- [ ] Adversarial test fixtures
- [ ] Verify all 7 acceptance criteria

## Phase 7 — Human approval

- [ ] Deterministic gate in Java (zero LLM input)
- [ ] Flyway `approvals`; queue + approve/reject endpoints
- [ ] `approval.requested` / `.completed`; LangGraph interrupt + resume
- [ ] Separation of duties enforced and tested
- [ ] Verify all 8 acceptance criteria

## Phase 8 — Observability

- [ ] OTel in Java and Python; collector pipeline; Jaeger/Tempo + Prometheus + Grafana (local)
- [ ] Correlation + trace context across the Kafka boundary
- [ ] Spans per node / retrieval / LLM call with cost attributes
- [ ] The four metric groups
- [ ] `/metrics/summary` + one Grafana dashboard
- [ ] Verify all 6 acceptance criteria

## Phase 9 — Frontend

- [ ] Vite + TS + Tailwind/shadcn + TanStack Query; typed client with Zod
- [ ] 9 pages
- [ ] SSE client with reconnect / cleanup / poll fallback
- [ ] Decision detail with resolvable citations + agent timeline
- [ ] Loading / empty / error states everywhere; role guards
- [ ] Verify all 7 acceptance criteria

## Phase 10 — Testing & evaluation

- [ ] Close coverage gaps in all three services
- [ ] All 14 named failure-scenario tests
- [ ] E2E test
- [ ] Evaluation harness + ≥30 labelled cases
- [ ] Baseline report → `docs/AI/EVALUATION_BASELINE.md`
- [ ] Model A/B with quantified trade-off
- [ ] Verify all 5 acceptance criteria

## Phase 11 — CI/CD

- [ ] Workflow: lint → test → integration → build → docker → eval → security scan
- [ ] Caching, path filters, branch protection
- [ ] Verify acceptance criteria

## Phase 12 — Local deployment hardening

- [ ] Multi-stage non-root Dockerfiles ×3
- [ ] `docker-compose.prod.yml`
- [ ] `make demo` one-command bootstrap + seeded corpus
- [ ] RUNBOOK + demo script + volume backup/restore
- [ ] Verify all 5 acceptance criteria

## Phase 13 — Kubernetes (optional)

- [ ] Manifests / Helm chart
- [ ] kind bootstrap script; E2E against kind
- [ ] Verify acceptance criteria

---

## Content work (do alongside, not at the end)

- [ ] Write `docs/sample-enterprise/` — 10 synthetic documents with deliberate conflicts, an
      unresolvable `UNKNOWN`, a superseded version, and one injection attempt (needed by Phase 2)
- [ ] Write the ≥30-case evaluation dataset (needed by Phase 10; start it during Phase 5)
- [ ] Keep `README.md` demo instructions current from Phase 9 onward

## Backlog / ideas (not committed)

- [ ] Second decision type (architecture-change review) to prove the workflow generalises
- [ ] Hybrid BM25 + vector retrieval if pure vector recall proves insufficient
- [ ] Document version diffing in the UI
- [ ] Free-tier public demo — only if a genuinely $0 option exists (see ADR-010)
