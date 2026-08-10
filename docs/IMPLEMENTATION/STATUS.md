# NexusIQ — Project Status

**The durable state of this project. Read this first in every session. Update it at the end of
every session.** If this file and the repository disagree, the repository is right — fix this file.

---

**Last updated:** 2026-08-10
**Last verified:** 2026-08-10 — `./mvnw clean verify` (backend/spring-api) → 50/50 tests passed

## Current position

| | |
|---|---|
| **Current phase** | Phase 2 — Document ingestion (**not started**) |
| **Completed phases** | Phase 0 — Repository & environment ✅ · Phase 1 — Java backend foundation ✅ |
| **Next milestone** | Phase 2 acceptance: upload a PDF → chunks + embeddings land in pgvector |

## Completed

**Bootstrap + Phase 0 (2026-08-09)** — see git history; full detail was previously recorded here
and is now in `git log` / the Phase 0 commit messages.

**Phase 1 — Java backend foundation (2026-08-10):**

- Scaffolded `backend/spring-api` from the live Spring Initializr API: **Spring Boot 4.1.0**
  (verified against Maven Central directly — the metadata-reported id `4.1.0.RELEASE` does not
  exist as a published artifact; Boot 4.x dropped the `.RELEASE` suffix), Java 21, Spring
  Framework 7.0.8, Maven wrapper committed.
- Flyway `V1`–`V4`: extensions (vector/pgcrypto/citext), `users`, `workspaces` +
  `workspace_members`, `documents` + `knowledge_sources` + `audit_events` with an append-only
  trigger (`BEFORE UPDATE OR DELETE` → `RAISE EXCEPTION`, DB-enforced, not just omitted from the
  repository interface).
- Packages: `common`, `config`, `security`, `auth`, `user`, `workspace`, `document`, `audit` —
  the ones Phase 1 actually needs; `messaging`/`streaming`/`decision`/`approval` deferred to their
  phases per "don't build ahead".
- JWT auth (access + refresh, HS384, BCrypt-12), stateless sessions, `CurrentUser` helper,
  `WorkspaceAccessService` as the single membership/role check every feature calls through.
- Endpoints: auth (register/login/refresh/me), workspaces (create/list/get/members add-remove),
  documents nested under `/workspaces/{id}/documents/...` (metadata-only in Phase 1 — every
  lookup filters `workspace_id` in SQL, never fetch-then-check), audit (list, resource history).
- `GlobalExceptionHandler` → standard error envelope; `CorrelationIdFilter` → `X-Correlation-Id` /
  MDC / `request_id`; Actuator (health/info/metrics); springdoc-openapi 3.1.0 (the Framework-7
  compatible line) at `/swagger-ui.html`.
- Timing-safe login (dummy BCrypt comparison on unknown email); self-registration defaults to
  `ANALYST`, never `ADMIN`/`APPROVER`.
- Tests: 29 unit (`*Test`, Surefire, mocked) + 21 integration (`*IT`, Failsafe,
  Testcontainers/pgvector-pg16, real HTTP through the full filter chain) = **50/50 passing**,
  covering all 9 Phase 1 acceptance criteria including the cross-tenant-denial and
  append-only-trigger cases with real evidence (see table below).

**Real bugs found and fixed during Phase 1** (all confirmed via a running test, not guessed):

1. `/api/v1/auth/me` was wrongly covered by the `/api/v1/auth/**` permitAll matcher →
   unauthenticated/garbage-token requests hit the controller as Spring Security's
   `anonymousUser` principal, and `UUID.fromString("anonymousUser")` threw, producing a **500**
   instead of a 401. Fixed by scoping permitAll to the three genuinely public auth endpoints only.
2. Spring Boot 4.1 auto-configures a **Jackson 3** (`tools.jackson.*`) `ObjectMapper`, not classic
   Jackson 2. The `spring.jackson.property-naming-strategy`/`default-property-inclusion` YAML
   properties silently had no effect on it. Fixed with an explicit `JsonMapperBuilderCustomizer`
   bean (`config/JacksonConfig.java`) — global snake_case now verified end-to-end via `AuthFlowIT`.
3. `*IT.java` integration tests never ran under `./mvnw test` (Surefire's naming convention
   excludes them by design — that's Failsafe's job) — they were silently skipped with exit 0.
   Added the `maven-failsafe-plugin` bound to `integration-test`/`verify`; `make test` now runs
   `mvn verify` so both suites execute. Documented in `.claude/rules/testing.md` as a footgun to
   watch for when adding new `*IT` classes.

Full detail on the Spring Boot 4.1 / Jackson 3 API surface (package moves, etc.):
`docs/OPERATIONS/LOCAL_DEV.md` § spring-api specifics.

**Phase 1 acceptance — all 9 met, with evidence:**

| # | Criterion | Evidence |
|---|---|---|
| 1 | Register → login → JWT; `/me` resolves user | `AuthFlowIT.registerThenLogin_...` |
| 2 | Expired/invalid/absent token → 401 standard envelope | `AuthFlowIT` (3 cases) + `JwtServiceTest` expiry/tamper cases |
| 3 | Create workspace, add member; non-members → 404 | `WorkspaceFlowIT.createWorkspace_addMember_...`, `.nonMember_getsWorkspace_returns404` |
| 4 | **Cross-tenant denial** (workspace B ↛ workspace A's document) | `WorkspaceFlowIT.userInWorkspaceB_cannotReadWorkspaceAsDocument_returns404` + `WorkspaceRepositoryIT` at the SQL level |
| 5 | VIEWER cannot create a workspace (403) | `WorkspaceFlowIT.viewer_cannotCreateAWorkspace_returns403` (VIEWER token minted directly — no public endpoint produces one) |
| 6 | Every mutation audited; audit_events is append-only | `WorkspaceFlowIT.everyMutation_writesAnAuditEvent` + `AuditEventAppendOnlyIT` (2 tests, real Postgres trigger, real Hibernate error observed) |
| 7 | Validation failure → 400 with field details | `AuthFlowIT.register_withBlankFields_returns400WithFieldDetails` |
| 8 | Swagger UI lists every endpoint with schemas | `SwaggerSmokeIT` (2 tests) |
| 9 | Every response carries `request_id`; appears in logs | `AuthFlowIT.everyResponse_carriesTheSameCorrelationId...` + MDC pattern in `application.yml` |

## In progress

Nothing.

## Not started

Phases 2–13. See `docs/IMPLEMENTATION/ROADMAP.md`.

## Blocked

| Item | Blocker | Owner |
|---|---|---|
| Phase 2 | Default Python is 3.10; need 3.11+. `uv venv --python 3.13` (already installed) unblocks this — low friction. | User |
| Phase 4+ | `LLM_API_KEY` (Gemini) not yet in `.env`. Not needed before Phase 4. | User |

Java/Maven blocker from Phase 0 is **resolved** — `brew install openjdk@21 maven` done, `make check`
passes when `JAVA_HOME` is scoped to 21 (this shell's default `JAVA_HOME` still points at Corretto
8; export it per the LOCAL_DEV.md instructions, or add it to `~/.zshrc` to make it permanent).

## Known bugs

None currently open (the three found during Phase 1 are fixed — see above).

## Technical debt

| Item | Cost | When to address |
|---|---|---|
| `otel-collector` has no container healthcheck (distroless image) | Cosmetic — probed from the host instead | Phase 8 |
| Refresh tokens are stateless JWTs with no server-side revocation list | A leaked/stolen refresh token is valid until natural expiry (7d default); acceptable for a portfolio-scale project | Revisit if a real threat model demands revocation |
| `AuditController.forResource` is not workspace-gated (no non-document resource type exists yet to check membership against generically) | Would need re-examination once decisions/approvals land in Phase 5/7 | Phase 5–7 |
| Document endpoints accept JSON metadata only, no file bytes | By design for Phase 1 — Phase 2 upgrades the same `POST` to multipart | Phase 2 |

## Decisions pending

| Question | Needed by | Note |
|---|---|---|
| PDF extraction library | Phase 2 | Compare `pypdf` / `pdfplumber` / `unstructured` on the sample corpus; record in an ADR. |
| pgvector tenant-filtering strategy | Phase 3 | Over-fetch + post-filter vs partial indexes. Measure, then record in `docs/AI/RAG.md`. |

## Test health

| Suite | State |
|---|---|
| Stack verification | ✅ 19/19 (`make verify`, Phase 0) |
| Java unit (`*Test`, Surefire) | ✅ 29/29 |
| Java integration (`*IT`, Failsafe, Testcontainers) | ✅ 21/21 |
| Python | No tests yet (Phase 2) |
| Frontend | No tests yet (Phase 9) |
| Evaluation | No dataset yet (Phase 10) |

## Environment facts (verified 2026-08-10)

| Tool | Present | Required | OK |
|---|---|---|---|
| Java (default, this shell) | 1.8.0_392 (Corretto) | **21 (LTS)** | ✗ — must scope `JAVA_HOME` per command or set it in `~/.zshrc` |
| Java 21 | 21.0.12 (Homebrew) | 21 | ✓ when `JAVA_HOME` scoped |
| Maven | 3.9.16 (Homebrew) | 3.9+ | ✓ |
| Python | 3.10.18 default; 3.13.1 via Homebrew; `uv` 0.8.4 | 3.11+ | ✗ (default) |
| Node | 22.13.0 | 20+ | ✓ |
| Docker / Compose | 27.3.1 / v2.30.3 | v2 | ✓ |
| Platform | macOS, Apple Silicon (arm64) | — | — |

## Recommended next action

Run `/implement-phase 2` — document ingestion. Start by benchmarking PDF extraction libraries on
a couple of real PDFs before committing to one (Phase 2's first open decision).
