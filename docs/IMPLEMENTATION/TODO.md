# NexusIQ — Task Backlog

Actionable items only. Phase definitions live in `ROADMAP.md`; project state lives in `STATUS.md`.

Convention: `- [ ]` open · `- [x]` done · `- [!]` blocked · `- [~]` in progress.
Delete completed items once their phase closes — this is a working list, not a history.

---

## Now — Phase 12 complete; small loose ends + the deferred Phase 10 items remain

Phase 10 is substantially complete and Phase 11 (CI/CD) is functionally complete (fully green real
CI run) — see `## Phase 10 — Testing & evaluation ✅` and the Phase 11 entry below, and STATUS.md
for the full history. Two Phase 10 items remain, explicitly deferred (blocked on real Gemini quota,
user instruction 2026-08-12 to proceed to Phase 11/12 rather than wait):

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

Phase 11 progress (2026-08-12):

- [x] Fixed a real pre-existing gap found while starting this: `frontend/web/package.json` had no
      `test` script at all, despite the Makefile's `test` target calling `npm test` — `make test`
      had silently been unable to run the frontend suite. Added `"test": "vitest run"`.
      **Committed together with the Phase 12 work below.**
- [x] `Dockerfile` for all three services (none existed before) — multi-stage, non-root user each.
      `frontend/web/Dockerfile` uses `nginx:alpine` + `nginx.conf.template` (envsubst) for SPA
      routing + `/api/` reverse proxy. `.dockerignore` per service. New `API_PROXY_TARGET` runtime
      var documented in `.env.example`. spring-api (468MB) and frontend (93MB) build cleanly and
      repeatedly; ai-service builds cleanly (verified twice, including a full `docker run`/
      `docker history` inspection) but is currently ~8.4GB — see STATUS.md's Phase 11 entry for what
      was tried (a `[tool.uv.sources]` CPU-only torch pin, which didn't work — a real uv 0.8.4
      behavior, not a mistake) and why it's deliberately deferred to Phase 12 ("slim" is Phase 12's
      own acceptance criterion). **Committed together with the Phase 12 work below.**
- [x] `.github/workflows/ci.yml` built, committed, pushed with sign-off, and verified fully green
      on real GitHub Actions — run
      [31592814077](https://github.com/uh-bhinav/NexusIQ/actions/runs/31592814077), all 13 job
      instances passed. Took 4 pushes; each one fixed a real bug the pipeline itself surfaced (not
      guessed): (1) `ai-service-test` — Kafka topics never existed because the job only ran Flyway
      migrations, not a full spring-api boot, and Java (not the broker) owns Kafka topic
      provisioning; fixed by booting spring-api and polling `/actuator/health` first. (2)
      `aquasecurity/trivy-action@0.29.0` was missing the `v` prefix its tags actually use. (3) While
      bumping all ten action pins to their current major version in the same commit,
      `astral-sh/setup-uv@v9` turned out to be the one repo of the ten with no bare major alias;
      pinned to the exact `v9.0.0` instead. See STATUS.md's Phase 11 entry for the full trace,
      including before/after timings once the GHA build cache warmed up (`docker-build(ai-service)`
      24 min → 3m35s). **Committed across `8e9ba2d`, `f41ba48`, `e23603e`, `9830d11`.**
- [ ] Branch protection requiring the pipeline to pass before merge (now that the final check names
      are known from a fully green run).
- [ ] Verify the one remaining acceptance criterion not yet demonstrated: a deliberately-broken
      commit fails the right job.

Phase 12 progress (2026-08-12):

- [x] `HEALTHCHECK` for all three Dockerfiles (spring-api: `wget` vs `/actuator/health`; ai-service:
      Python stdlib `urllib.request` vs `/ready`, since `python:slim` has neither `curl` nor `wget`
      by default; frontend: `wget` vs `/`).
- [x] `docker-compose.prod.yml` — overlay file (always used with `-f docker-compose.yml -f
      docker-compose.prod.yml`, never standalone): resource limits on every infra service, plus new
      `spring-api`/`ai-service`/`frontend` services built from their own Dockerfiles, sharing a new
      `document_storage` volume. `.env.example`'s existing defaults turned out to already be
      container-network-ready (confirmed by reading the file, not assumed) — only one genuinely new
      var needed, `FRONTEND_PORT`.
- [x] `scripts/seed.sh` — idempotent corpus upload via the real spring-api REST API (register-or-
      login, reuse-workspace-by-name, skip-already-uploaded-documents-by-name). Prints demo login
      (local-only, not a real secret). `scripts/backup.sh`/`restore.sh` — `pg_dump`/`psql` via
      `docker compose exec`, `restore.sh` requires typing `yes` first.
- [x] `Makefile`'s `migrate`/`seed`/`demo`/`backup`/`restore` targets do the real thing now instead
      of the Phase 0–2 placeholder stubs. `migrate` calls `check-prereqs.sh all` first rather than
      silently auto-exporting `JAVA_HOME` — this machine's Java-8-default should fail loudly here
      like everywhere else, not get a quiet one-off workaround.
- [x] Made one more real, time-boxed attempt at the `ai-service` image-size problem (torch pulling
      in ~3GB of unused CUDA packages) with a newer `uv` (0.12.3, isolated in `/tmp`, system install
      untouched) — confirmed via `uv lock -v --refresh` that the pinned CPU-only index is never even
      queried, reproducibly across two uv versions. A real, well-characterized uv behavior with this
      exact transitive-dependency scenario, not fixable by a version bump. Reverted cleanly again
      (confirmed via `git diff` — zero change). Not one of Phase 12's 5 numbered acceptance
      criteria; left open rather than pursued further.
- [x] **Docker Desktop's environment failure resolved itself before this session** (most likely the
      user acted in its own UI between sessions). Confirmed healthy (`docker info`) at the start of
      this session's work.
- [x] **Live `make demo` verification — done, all 5 acceptance criteria met with evidence.** Found
      and fixed 4 real bugs no syntax check could have caught: (1) `ai-service` crash-looped —
      `exec .../uvicorn: no such file or directory` — build stage `WORKDIR /build` baked a
      `#!/build/.venv/bin/python3` shebang into venv scripts that didn't exist once copied to
      `/app`; fixed by matching `WORKDIR /app` in both stages. (2) `frontend` reported `unhealthy`
      forever — `wget http://localhost/` hit `::1` (container `/etc/hosts` prefers IPv6) but nginx
      only listens on `0.0.0.0:80`; fixed by targeting `127.0.0.1` in the healthcheck instead. (3)
      `ai-service` crash-looped again, differently — `ImportError: no pq wrapper available` from
      bare `psycopg` (pulled in by `langgraph-checkpoint-postgres`), which needs `libpq`'s shared
      library; `python:slim` has neither; fixed with `apt-get install libpq5` (few hundred KB). (4)
      `scripts/seed.sh` failed at `declare -A`: macOS ships bash 3.2 by default (no associative-array
      support); rewrote the `dir → document_type` lookup as a portable `case` statement. Also fixed
      `seed.sh` silently letting `.env`'s stored `API_PORT` clobber an explicitly-exported override,
      and removed `demo`'s redundant (and host-Java-21-requiring, thus broken on this machine) call
      to `migrate` — spring-api already self-migrates on container boot
      (`spring.flyway.enabled=true`, confirmed via its own logs). **Fixes made but not yet
      committed** — see STATUS.md's Phase 12 entry for full detail and evidence (AC1 bootstrap time,
      AC2 ~2.73GiB footprint, AC3 restart/no-data-loss via a documents-count check, AC4 idempotent
      seeding, AC5 RUNBOOK coverage already sufficient — 15+ real symptom sections).
- [ ] Commit the 4 files changed during live verification: `ai-service/Dockerfile`,
      `frontend/web/Dockerfile`, `scripts/seed.sh`, `Makefile`.

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

## Phase 11 — CI/CD ✅ FUNCTIONALLY COMPLETE (2026-08-12)

- [x] Workflow: lint → test → integration → build → docker → eval → security scan
- [x] Caching, path filters — done. Branch protection — not yet (see "Now" above)
- [~] Verify acceptance criteria — green on a clean push ✓, Docker images build for all 3 ✓, no
      secret printed in a log ✓, total runtime reasonable per-job ✓; "a deliberately broken commit
      fails the right job" not yet tested (see "Now" above)

## Phase 12 — Local deployment hardening ✅ (see "Now" above for the live-verification trace)

- [x] Multi-stage non-root Dockerfiles ×3
- [x] `docker-compose.prod.yml`
- [x] `make demo` one-command bootstrap + seeded corpus
- [x] RUNBOOK + demo script + volume backup/restore
- [x] Verify all 5 acceptance criteria — done live, with evidence (see "Now" above)

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
