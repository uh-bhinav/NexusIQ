# NexusIQ — Task Backlog

Actionable items only. Phase definitions live in `ROADMAP.md`; project state lives in `STATUS.md`.

Convention: `- [ ]` open · `- [x]` done · `- [!]` blocked · `- [~]` in progress.
Delete completed items once their phase closes — this is a working list, not a history.

---

## Now — continue Phase 9

- [x] ~~Build the SSE endpoint~~ — done: `streaming` package (`SseEmitterRegistry`,
      `DecisionStreamController`/`Service`), a scoped `JwtService` stream token (EventSource can't
      set headers), wired into all 4 places a decision's status changes. Live-verified with a real
      `curl -N` SSE session against real spring-api. See STATUS.md's Phase 9 entry.
- [x] ~~Add the chunk-fetch endpoint~~ — done: Python `GET /internal/documents/{documentId}/chunks`
      (workspace-scoped, paginated, 5 new tests) + Java `DocumentChunkService`/`DocumentController`
      proxy (`GET /workspaces/{id}/documents/{documentId}/chunks`), mirroring the existing
      `KnowledgeService`→`/internal/search` pattern rather than giving Java direct
      `document_chunks` access. No `EvidenceResponse` changes needed — it already carried
      `document_id`/`chunk_id`. AC3 met. Live-verified end-to-end against real spring-api +
      ai-service (register → workspace → upload → async-ingest to `READY` → fetch chunks), which
      caught a real bug: `schemas.ts` used `.nullable()` but the backend's `ALL_NON_NULL` Jackson
      config omits null fields entirely rather than sending `null`, so a chunk with no
      `section`/`subsection`/`page_number` failed Zod parsing. Fixed project-wide
      (`.nullable()` → `.nullish()` across `schemas.ts`, plus 6 call sites that did unsafe
      `!== null` checks that would've let `undefined` through to a crash). See STATUS.md's Phase 9
      entry for the full writeup — this was a systemic, previously-undetected defect across nearly
      every page, not something scoped to just the chunk endpoint.
- [x] ~~Build the last remaining Phase 9 page: Document detail~~ — done:
      `DocumentDetailPage.tsx` (metadata + paginated chunk list + `?chunk=` scroll-to-highlight),
      3 tests. Evidence citations on `DecisionDetailPage` are now real links to
      `/w/{workspaceId}/documents/{documentId}?chunk={chunkId}`. All 9 required pages now exist.
- [x] ~~Apply `RequireRole` to the approvals/metrics routes~~ — turned out to be the wrong fix:
      `GET /approvals` and `GET .../metrics/summary` are both member-visible with no server-side
      role restriction (confirmed by reading `ApprovalController`/`MetricsController` — neither has
      `@PreAuthorize`, only membership via `WorkspaceAccessService`); only the approve/reject
      *actions* are role-gated. `ApprovalQueuePage` already hides the buttons (not the page) for a
      non-APPROVER/ADMIN, matching the real API and satisfying AC4 as written — a route-level guard
      would have been a regression, blocking VIEWERs from a page they're allowed to see.
- [x] ~~Chrome extension browser click-through~~ — done: walked login → Dashboard → Knowledge Base
      search → submit Decision Request → live SSE agent timeline → outcome with real citation →
      citation click-through to exact chunk → Approval Queue → System Metrics → Audit Log, zero
      console errors. Caught and fixed a real bug: `DecisionService.create()` never wrote an audit
      event (`ApprovalService` did this correctly for approve/reject, creation was missing it) —
      fixed with `AuditService.record(...)` + `DecisionServiceTest` case.
- [x] ~~Build the missing Document Upload UI~~ — done: added to `KnowledgeBasePage.tsx` (upload
      form + document list, 4s poll for async ingestion status). Live-verified uploading the real
      sample corpus's injection file through the UI — the heuristic scanner correctly flagged it
      (spec §8 step 11 confirmed end-to-end).
- [x] ~~Build minimal workspace member management~~ — done: `MembersSection.tsx` on
      `DashboardPage.tsx`, gated on workspace-level `role === 'ADMIN'` (matches
      `WorkspaceService.addMember`'s server-side check exactly). Live-verified adding a real second
      user as a member through the UI. `.claude/rules/frontend.md`'s required-pages list doesn't
      mention a members page — flagged as a doc inconsistency against spec §8, not silently
      resolved.
- [x] ~~Demonstrate `INSUFFICIENT_INFORMATION` (spec §8 step 12) live~~ — done, with explicit user
      approval to spend real Gemini API calls (mock provider returns one fixed canned response
      regardless of input, so it can't demonstrate this). Submitted a genuinely out-of-scope
      question; real `intent` agent classified it correctly and short-circuited to
      `INSUFFICIENT_INFORMATION` with `evidence: []`. **This is what surfaced a second real crash
      bug**: `DecisionOutcome.evidence_coverage` was a required Zod field but the Java `BigDecimal`
      backing it is genuinely null on this fast path (`ALL_NON_NULL` omits it) — Decision Detail
      rendered completely blank, no error shown anywhere. Fixed, then swept every other
      `BigDecimal`-backed decision DTO field for the same risk and fixed 5 more
      (`DecisionRun`/`AgentExecution.estimated_cost_usd`, `Evidence.relevance_score`,
      `Finding`/`DecisionOutcome.confidence`) plus render-side `!= null` guards. See STATUS.md's
      Phase 9 entry for the full trace.
- [x] ~~Exercise a real LLM failure path~~ — unplanned, but happened: a follow-up real-Gemini call
      hit the actual free-tier quota (`429 RESOURCE_EXHAUSTED`) mid-workflow. Run terminated cleanly
      to `FAILED` with a human-readable `failure_reason`, exactly per
      `.claude/rules/architecture.md`'s degradation table. Confirms that requirement against a real
      failure, not a simulated one. Quota now exhausted — stopped spending further real API calls,
      `ai-service` switched back to `LLM_PROVIDER=mock`.
- [x] ~~Escalation-to-human-approval and an actual APPROVER clicking Approve (spec §8 steps 8–9)~~
      — done, without spending further Gemini quota: `approval_router_node`'s six triggers are a
      deterministic gate over LLM node output, so temporarily lowering the mock
      `tests/fixtures/llm/Recommendation.json` fixture's `confidence` below
      `HITL_MIN_CONFIDENCE=0.75` reproduces a genuine escalation under `LLM_PROVIDER=mock` (the
      mock provider re-reads fixtures from disk on every call, no restart needed). Confirmed safe
      first (only one inclusive-membership pytest assertion references this fixture), restored it
      immediately after and reran the affected suites (50/50 passed). Caught a 4th real bug in the
      process: `ApprovalQueuePage.tsx` gated the Approve/Reject buttons on the *global* role, but
      `ApprovalService` authorizes on the *workspace-level* one — every existing test passed
      because its fixtures always set both identically. Fixed to match `MembersSection.tsx`'s
      correct pattern, added 2 regression tests. Live-verified the full loop: requester correctly
      blocked from approving their own request (separation of duties), a second APPROVER user
      correctly could, Decision Detail shows `HUMAN_APPROVED`. Step 7 (data residency `UNKNOWN`)
      remains legitimately Phase 10 work per `docs/sample-enterprise/README.md` — not a Phase 9 gap.
      **All 12 spec §8 steps are now demonstrated end-to-end through the browser, except step 7.**
- [!] **Large, separate discovery — needs the user's decision, not mine**: `git status` shows the
      entire `ai-service/` and `frontend/web/` directories, plus most of the newer
      `backend/spring-api` packages, as untracked. Only Phase 0/1 ever got committed. See
      STATUS.md's Phase 9 entry and the end-of-session message for details — `CLAUDE.md` requires
      explicit permission before committing, so this is flagged, not acted on.
- [x] ~~Fix the pagination `sort=` bug~~ — done: `config/SnakeCaseSortPageableResolver`
      (`HandlerMethodArgumentResolver` wrapping Spring Data's default one, converting every
      `Sort.Order` property from snake_case to camelCase before it reaches a repository) +
      `config/WebConfig` (`WebMvcConfigurer.addArgumentResolvers`, registers it ahead of the
      built-in resolver). 4 new unit tests on the conversion logic
      (`SnakeCaseSortPageableResolverTest`) + 1 new integration test
      (`WorkspaceFlowIT.listDocuments_withSnakeCaseSortParam_matchesTheDocumentedApiConvention`,
      proving `?sort=created_at,desc` returns correctly-ordered documents against a real Postgres
      instead of 500ing). Live-reverified against the running API across three different endpoints/
      entities (`decisions?sort=created_at,desc`, `documents?sort=created_at,asc`,
      `audit?sort=occurred_at,desc`) — all correctly ordered, no errors. `./mvnw verify`: 63 unit +
      34 integration, 0 failures.
- [x] ~~Phase 8 live-verification gap~~ — done: full live run performed, all 6 acceptance criteria
      met with real evidence, 4 real bugs found and fixed along the way. See STATUS.md's Phase 8
      entry.
- [x] ~~Confirm exact Prometheus metric names~~ — done, confirmed live; dashboard queries tightened
      to exact names.
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
- [ ] Re-verify Phase 7's live paths against real Gemini once the `gemini-2.5-flash` daily free-tier
      quota resets — this session's live verification used the mock provider (quota was already
      exhausted from Phase 5/6 testing); the pipeline mechanics are proven, a real-model run would
      additionally reconfirm genuine model-driven low-confidence escalation.
- [ ] `handle_approval_message`'s resume-failure path (ai-service) has no automatic retry — see
      STATUS.md technical debt; no action needed unless observed for real.

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

## Phase 9 — Frontend (started 2026-08-11, in progress)

- [x] Vite + TS + Tailwind/shadcn + TanStack Query; typed client with Zod
- [x] 9 pages — all done (Login, Dashboard, Knowledge Base, Decision Requests, Decision Detail,
      Approval Queue, Audit Log, System Metrics, Document Detail)
- [x] SSE client with reconnect / cleanup / poll fallback — done, live-verified against real
      spring-api (`src/lib/sse-client.ts`, `use-decision-stream.ts`)
- [x] Decision detail with resolvable citations + agent timeline — page built, agent timeline done;
      citations are real links to `/w/{workspaceId}/documents/{documentId}?chunk={chunkId}`,
      resolving to the exact chunk via the new chunk-fetch endpoint. Known, documented limitation:
      no auto-pagination-to-find if the cited chunk isn't on the first page of results.
- [x] Loading / empty / error states everywhere — done for all 9 pages (`AsyncState` component)
- [x] Role guards — `RequireAuth` applied to every authenticated route; `RequireRole` built but
      deliberately *not* applied to approvals/metrics routes — neither is actually role-restricted
      server-side (only the approve/reject actions are), so a route guard there would be a
      regression, not a fix. `ApprovalQueuePage` hides the action buttons per-role instead, which is
      what AC4 actually asks for.
- [~] Verify all 7 acceptance criteria — AC2 (SSE live updates), AC3 (citations resolve to exact
      chunk), AC4 (VIEWER sees no approve buttons, server 403 already proven by Phase 7), AC6
      (build/tsc clean), AC7 (no mock data) all live-verified through the actual browser against the
      real stack. AC1 (entire demo performable from UI alone) is met for spec §8 steps 1–6, 10–12;
      steps 8–9 (escalation + human approve) are mechanism-verified via direct API calls but not
      re-walked through the browser with a genuinely escalating real-LLM decision — blocked on
      Gemini free-tier quota exhaustion this session, not missing functionality. AC5 (RTL/MSW
      populated/empty/error/primary-action per page) met — 38/38 passing.

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

- [x] `docs/sample-enterprise/` Phase 2 starter set — 4 documents (PDF/DOCX/TXT/MD), one per
      format, the MD containing a real injection attempt (2026-08-10)
- [ ] Grow to the full ≥10-document set with deliberate conflicts, an unresolvable `UNKNOWN`, and
      a superseded version pair (needed by Phase 5 for conflict/version resolution, Phase 10 for
      the evaluation dataset)
- [ ] Write the ≥30-case evaluation dataset (needed by Phase 10; start it during Phase 5)
- [ ] Keep `README.md` demo instructions current from Phase 9 onward

## Backlog / ideas (not committed)

- [ ] Second decision type (architecture-change review) to prove the workflow generalises
- [ ] Hybrid BM25 + vector retrieval if pure vector recall proves insufficient
- [ ] Document version diffing in the UI
- [ ] Free-tier public demo — only if a genuinely $0 option exists (see ADR-010)
