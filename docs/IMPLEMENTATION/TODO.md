# NexusIQ — Task Backlog

Actionable items only. Phase definitions live in `ROADMAP.md`; project state lives in `STATUS.md`.

Convention: `- [ ]` open · `- [x]` done · `- [!]` blocked · `- [~]` in progress.
Delete completed items once their phase closes — this is a working list, not a history.

---

## Now — start Phase 11 (CI/CD)

Phase 10 is substantially complete — see `## Phase 10 — Testing & evaluation ✅` below and STATUS.md
for the full history. Two items remain, explicitly deferred (blocked on real Gemini quota, user
instruction 2026-08-12 to proceed to Phase 11/12 rather than wait):

- [!] **Retry the real-Gemini baseline once the free-tier daily quota resets.** Already asked the
      user and got sign-off for a small representative subset (one case per category, 8/9
      categories) rather than the full 30. Attempted it — all 8 cases hit `429 RESOURCE_EXHAUSTED`
      immediately, quota already exhausted from this session's earlier live-verification calls.
      Harness's own error handling confirmed correct (clean per-case errors, no crash). Retry
      command: `make eval PROVIDER=gemini
      CASE=EVAL-001,EVAL-005,EVAL-009,EVAL-013,EVAL-019,EVAL-021,EVAL-024,EVAL-027`. Then write
      `docs/AI/EVALUATION_BASELINE.md` (scoped honestly as 8/30 categories unless the full 30 are
      run later).
- [!] A/B comparison of at least two model configurations with accuracy/latency/cost recorded (same
      quota consideration as above — roughly doubles the real-LLM-call cost).

Phase 11 orientation: read `ROADMAP.md`'s Phase 11 section (already done this session) — GitHub
Actions pipeline (lint → unit → integration → build → Docker build → eval(mock) → security scan),
path-filtered jobs, dependency caching, branch protection. `.github/workflows/` currently doesn't
exist at all — from-scratch build, not a refinement.

- [ ] `.github/workflows/ci.yml` (or split per-service workflows): lint job (ruff+mypy, oxlint+tsc,
      — Java has no separate lint step configured, confirm), unit test job (Surefire, `uv run
      pytest -m "not integration"` if such a marker exists or the fast subset, Vitest), integration
      test job (Failsafe + Testcontainers — needs Docker-in-Docker/services in the runner), build
      job, Docker build job (needs Phase 12's Dockerfiles — may need to sequence after or build a
      minimal Dockerfile now and harden in Phase 12), evaluation job (mock provider only, free/
      deterministic), security scan job (`pip-audit`, `npm audit`, OWASP dependency-check for Maven,
      Trivy for images).
- [ ] Branch protection requiring the pipeline to pass before merge.
- [ ] Verify acceptance criteria: green on a clean push; a deliberately broken commit fails the
      right job (test this for real, don't assume); Docker images build for all three services;
      total runtime < 15 min; secret scanning enabled; no secret ever printed in a log (check
      workflow logs directly, don't just assume `env:` handling is safe).

### Backlog (low-priority, not phase-blocking)

- [ ] Optionally add `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` to `~/.zshrc` so it's
      not needed per-command (Java 21 + Maven are installed; only the shell default is stale).
- [ ] `GlobalExceptionHandler` returns `500` instead of `405` for a wrong HTTP verb on a real
      route (see STATUS.md technical debt) — low priority, fix opportunistically.
- [ ] Confidence calibration (intent + Phase 5/6's agents) is under-calibrated for a
      confidence-gated approval gate (see STATUS.md technical debt) — revisit during Phase 10's
      evaluation pass, not before.
- [ ] Local dev Kafka broker has accumulated harmless DLQ noise from live-verification + Python
      test runs against the shared local broker (see STATUS.md known bugs) — no action needed,
      clears on `docker compose down -v`.
- [ ] `handle_approval_message`'s resume-failure path (ai-service) has no automatic retry — see
      STATUS.md technical debt; no action needed unless observed for real.
- [ ] `git log` now has organizational-not-strictly-bisectable intermediate commits from the
      Phase 2-9 git catch-up (see STATUS.md's Phase 9 entry) — not worth rewriting history to fix;
      noted so a future `git bisect` session isn't surprised if an intermediate commit doesn't build.

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

## Phase 2 — Document ingestion ✅ COMPLETE (2026-08-10)

- [x] Upload endpoint: magic-byte validation, size cap, checksum, UUID storage naming
- [x] `DocumentStorage` abstraction + `LocalStorage`
- [x] Kafka envelope, producers, idempotent consumers, `processed_events`, retry, DLQ
- [x] AI service skeleton: FastAPI, config, async SQLAlchemy, health/ready, Kafka consumer
- [x] Extractors (PDF/DOCX/TXT/MD) — ADR-011 (`pdfplumber` for PDF, benchmarked against `pypdf`)
- [x] Hierarchical chunker with section/page metadata + paragraph-level overlap
- [x] Injection heuristic scan at ingestion
- [x] `EmbeddingProvider` + local bge-small-en-v1.5, batched, normalized for cosine
- [x] Flyway V5: `document_chunks` + HNSW cosine index; V6: `processed_events`
- [x] Verified all 9 acceptance criteria — see `STATUS.md` (live run + 108 automated tests)

## Phase 3 — RAG retrieval ✅ COMPLETE (2026-08-10)

- [x] Vector search (cosine, workspace-scoped)
- [x] Metadata filters incl. version preference
- [x] Reranker stage (toggleable) + benchmark both modes
- [x] Context assembly with priority ordering + token budget
- [x] Search endpoints (internal + authorised public proxy)
- [x] Redis cache with tenant in the key
- [x] Retrieval metrics
- [x] **Decide and document the pgvector tenant-filtering strategy**
- [x] Verify all 8 acceptance criteria — see `STATUS.md`

## Phase 4 — Intent agent ✅ COMPLETE (2026-08-11)

- [x] `ModelProvider` abstraction + Gemini adapter + mock adapter
- [x] `IntentAnalysis` schema + `prompts/intent_v1.md`
- [x] Token/cost/latency capture; `llm/pricing.py` with a dated table
- [x] Malformed-output tests; provider-swap test
- [x] Verify all 6 acceptance criteria — see `STATUS.md`

## Phase 5 — LangGraph workflow ✅ COMPLETE (2026-08-11)

- [x] `DecisionState` + graph builder + Postgres checkpointer
- [x] Nodes: context_planner, retrieval, policy_analyst ∥ risk_analyzer, decision
- [x] All agent output schemas (with `UNKNOWN` / `INSUFFICIENT_INFORMATION`)
- [x] `decision.requested` consumer; `decision.progress` / `.completed` producers
- [x] Java persistence of runs, agent_executions, evidence, findings, decisions
- [x] Budget enforcement; checkpoint resume
- [x] Verify all 9 acceptance criteria — see `STATUS.md`

## Phase 6 — Validation & guardrails ✅ COMPLETE (2026-08-11)

- [x] Validator node (6 checks) + `ValidationResult`
- [x] Capped retry edge → forced escalation
- [x] Input / retrieval / output / workflow guardrails
- [x] `PROMPT_INJECTION_ATTEMPT` finding surfaced through the API
- [x] Adversarial test fixtures
- [x] Verify all 7 acceptance criteria — see `STATUS.md`

## Phase 7 — Human approval ✅ COMPLETE (2026-08-11)

- [x] Deterministic gate in Java (zero LLM input)
- [x] Flyway `approvals`; queue + approve/reject endpoints
- [x] `.completed`; LangGraph interrupt + resume (`approval.requested` deliberately not a Kafka
      topic — see STATUS.md for why)
- [x] Separation of duties enforced and tested
- [x] Verify all 8 acceptance criteria — see `STATUS.md`

## Phase 8 — Observability ✅ COMPLETE (code + tests + infra, 2026-08-11) — 1 live-run gap, see "Now"

- [x] OTel in Java and Python; collector pipeline; Jaeger + Prometheus + Grafana (local) — SDK-only
      in Java (not the `-javaagent:`), a deliberate deviation documented in STATUS.md
- [x] Correlation + trace context across the Kafka boundary (`traceparent` on `EventEnvelope`,
      mirroring the existing `correlation_id` pattern)
- [x] Spans per node / retrieval / LLM call with cost attributes
- [x] The four metric groups (Business: Java/Micrometer; AI + RAG: Python/OTel; Infrastructure:
      OTel Collector contrib receivers — zero app instrumentation, a deliberate deviation
      documented in STATUS.md)
- [x] `/metrics/summary` + one Grafana dashboard (4 rows, 24 panels; Infrastructure row confirmed
      live with real data, Business/AI rows need a live decision run — see "Now")
- [~] Verify all 6 acceptance criteria — AC4/AC5 fully evidenced; AC1/AC2/AC3/AC6 mechanism-proven
      by unit tests but not yet observed against a real live decision run (blocked on `.env`
      access this session)

## Phase 9 — Frontend ✅ COMPLETE (2026-08-11 to 2026-08-12)

- [x] Vite + TS + Tailwind/shadcn + TanStack Query; typed client with Zod
- [x] 9 pages — all done (Login, Dashboard, Knowledge Base, Decision Requests, Decision Detail,
      Approval Queue, Audit Log, System Metrics, Document Detail)
- [x] SSE client with reconnect / cleanup / poll fallback — done, live-verified against real
      spring-api, and the reconnect/backoff/terminal-close/cleanup logic itself directly unit-tested
      with a controllable `EventSource` (`src/lib/sse-client.ts` + `sse-client.test.ts`)
- [x] Decision detail with resolvable citations + agent timeline — citations are real links to
      `/w/{workspaceId}/documents/{documentId}?chunk={chunkId}`, resolving to the exact chunk.
      Unresolvable citations (e.g. a since-deleted document) surface an explicit AsyncState error on
      Document Detail, never a silent failure. Known, documented limitation: no
      auto-pagination-to-find if the cited chunk isn't on the first page of results.
- [x] Loading / empty / error states everywhere — done for all 9 pages (`AsyncState` component)
- [x] Role guards — `RequireAuth` on every authenticated route. Button-level visibility
      (Approval Queue, member management) follows the actual server-side authorization boundary,
      which is the *workspace-level* role, not the global one — a real bug (`ApprovalQueuePage`
      originally checked the global role) was found and fixed via live verification.
- [x] Verify all 7 acceptance criteria — **all met**, all with real-browser evidence against the
      live stack, not just RTL/MSW: AC1 (spec §8 steps 1-6 and 8-12 performable from the UI alone;
      step 7 is legitimately Phase 10 work), AC2 (SSE live updates + a directly-tested reconnect
      path), AC3 (citations resolve to the exact chunk, unresolvable ones warn explicitly), AC4
      (VIEWER sees no approve buttons, server 403 proven by Phase 7), AC5 (44/44 Vitest passing,
      every page's four states), AC6 (`tsc -b`/`vite build` clean, zero `any`), AC7 (no mock data
      anywhere — confirmed by grep, not just claimed).

## Phase 10 — Testing & evaluation ✅ SUBSTANTIALLY COMPLETE (2026-08-12)

- [x] Closed coverage gaps in all three services (audited, not assumed — see STATUS.md's Phase 10
      entry for the full ranked list and what was fixed vs. deliberately left)
- [x] All 14 named failure-scenario tests
- [x] E2E test (`tests/e2e/test_full_spine.py`)
- [x] Evaluation harness + 30 labelled cases
- [!] Baseline report → `docs/AI/EVALUATION_BASELINE.md` — **blocked on Gemini quota**, see "Now"
- [!] Model A/B with quantified trade-off — **blocked on Gemini quota**, see "Now"
- [~] Verify all 5 acceptance criteria — 3/5 fully met; the 2 requiring real-provider numbers are
      the two blocked items above

## Phase 11 — CI/CD (starting now — see "Now" above for the live task list)

- [ ] Workflow: lint → test → integration → build → docker → eval → security scan
- [ ] Caching, path filters, branch protection
- [ ] Verify acceptance criteria

## Phase 12 — Local deployment hardening

- [ ] Multi-stage non-root Dockerfiles ×3
- [ ] `docker-compose.prod.yml`
- [ ] `make demo` one-command bootstrap + seeded corpus
- [ ] RUNBOOK + demo script + volume backup/restore
- [ ] Verify all 5 acceptance criteria

## Phase 13 — Kubernetes — OUT OF SCOPE, do not start (user instruction, 2026-08-12)

The roadmap itself flags this as optional and only worth doing "if 0–12 are genuinely done." The
user has explicitly said Phase 12 is where this project wraps. Left here unstarted, not deleted, so
a future session doesn't wonder whether it was forgotten rather than declined.

---

## Content work (do alongside, not at the end)

- [x] `docs/sample-enterprise/` Phase 2 starter set — 4 documents (PDF/DOCX/TXT/MD), one per
      format, the MD containing a real injection attempt (2026-08-10)
- [x] Grew to the full 10-document set with deliberate conflicts, an unresolvable `UNKNOWN`, and a
      superseded version pair (`docs/sample-enterprise/`, Phase 10, 2026-08-12)
- [x] Wrote the 30-case evaluation dataset (`ai-service/app/evaluation/datasets/cases.json`, Phase
      10, 2026-08-12)
- [ ] Keep `README.md` demo instructions current from Phase 9 onward — revisit once Phase 12's
      `make demo` exists, since the current instructions predate it

## Backlog / ideas (not committed)

- [ ] Second decision type (architecture-change review) to prove the workflow generalises
- [ ] Hybrid BM25 + vector retrieval if pure vector recall proves insufficient
- [ ] Document version diffing in the UI
- [ ] Free-tier public demo — only if a genuinely $0 option exists (see ADR-010)
