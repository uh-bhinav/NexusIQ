# NexusIQ — Project Status

**The durable state of this project. Read this first in every session. Update it at the end of
every session.** If this file and the repository disagree, the repository is right — fix this file.

---

**Last updated:** 2026-08-09
**Last verified:** 2026-08-09 — `./scripts/verify-stack.sh` → 19/19 checks passed

## Current position

| | |
|---|---|
| **Current phase** | Phase 1 — Java backend foundation (**not started**) |
| **Completed phases** | Phase 0 — Repository & environment ✅ (all 7 acceptance criteria verified) |
| **Next milestone** | Phase 1 acceptance: register → login → workspace → member, with cross-tenant denial proven |

## Completed

**Bootstrap (2026-08-09)** — no application code:
`.claude/` (settings, 7 rules files, 4 skills) · `CLAUDE.md` · docs system (spec, architecture,
roadmap, status, todo, ADRs 001–010, schema, API design, 8 AI docs, testing, operations) ·
`.gitignore` · `.env.example`.

**Phase 0 (2026-08-09)** — verified working local stack:

- Directory skeleton; module-scoped `CLAUDE.md` pointers in `backend/spring-api`, `ai-service`,
  `frontend/web` that import the matching rules file.
- `docker-compose.yml` — postgres+pgvector, redis, kafka (KRaft), kafka-ui, otel-collector,
  `kafka-init`; healthchecks, named volumes, one network, pinned images.
- `infrastructure/docker/otel/collector-config.yaml` — OTLP in, debug out (Phase 8 replaces).
- `Makefile` (18 targets; unbuilt phases print a clear message rather than failing).
- `scripts/check-prereqs.sh` (`infra` | `all` modes) and `scripts/verify-stack.sh` (19 checks).
- `.env` generated with real local secrets; port block documented in `.env.example`.

**Phase 0 acceptance — all 7 met, with evidence:**

| # | Criterion | Result |
|---|---|---|
| 1 | `make up` → all healthy, none restarting | ✅ 5/5 (otel-collector: running, see note) |
| 2 | psql connects; pgvector works | ✅ PG **16.14**, pgvector **0.8.6**, `<=>` round trip verified |
| 3 | Kafka reachable internally + from host; kafka-ui lists broker | ✅ produce/consume round trip; cluster ONLINE |
| 4 | Redis `PING` | ✅ PONG + set/get round trip |
| 5 | OTel collector accepts an OTLP span | ✅ HTTP 200 and span observed in the pipeline |
| 6 | `down` → `up` with data intact | ✅ Postgres row and Kafka topic both survived |
| 7 | `check-prereqs.sh` fails on a missing prerequisite | ✅ `infra` exit 0, `all` exit 1 (java 8, no maven, py 3.10) |

**Measured infrastructure footprint:** ~870 MiB idle (kafka 420, kafka-ui 377, otel 39,
postgres 29, redis 5). Services and the AI image are added later; re-measure in Phase 12.

## In progress

Nothing.

## Not started

Phases 1–13. See `docs/IMPLEMENTATION/ROADMAP.md`.

## Blocked

| Item | Blocker | Owner |
|---|---|---|
| **Phase 1** | **Java 8 is the default JDK and Maven is not installed.** Java 21 + Maven 3.9+ required. `make check` reproduces this. | User — `docs/OPERATIONS/LOCAL_DEV.md` §Prerequisites |
| Phase 2 | Default Python is 3.10; 3.13.1 is installed and `uv` can pin it per-venv, so this is low-friction | User |
| Phase 4+ | `LLM_API_KEY` (Gemini) not yet in `.env`. Phases 0–3 do not need it. | User |

Phase 0 was **not** blocked by the toolchain — it needs only Docker. Phase 1 is where Java matters.

## Known bugs

None.

## Technical debt

| Item | Cost | When to address |
|---|---|---|
| `otel-collector` has no container healthcheck (distroless image: no shell, so every probe fails regardless of state) | `docker compose ps` shows no health for it; verified from the host instead | Phase 8 — the real backends may ship a probe-able image |
| `debug` exporter at `verbosity: detailed` is noisy | Log volume during development | Phase 8 replaces it with Jaeger/Tempo + Prometheus |
| Host ports shifted off the defaults (5434/6380/29093/8091/4327-4328/13134) because another local stack (RedLine) holds 5433/6379/29092/8090/4317 | Anyone cloning must read the port table | None — documented and configurable |

## Decisions pending

| Question | Needed by | Note |
|---|---|---|
| Exact Spring Boot version | Phase 1 | Resolve from `start.spring.io` at scaffold time. **Do not pin from memory** — the original brief cited a version that could not be verified. |
| PDF extraction library | Phase 2 | Compare `pypdf` / `pdfplumber` / `unstructured` on the sample corpus; record in an ADR. |
| pgvector tenant-filtering strategy | Phase 3 | Over-fetch + post-filter vs partial indexes. Measure, then record in `docs/AI/RAG.md`. |

## Test health

| Suite | State |
|---|---|
| Stack verification | ✅ 19/19 (`make verify`) |
| Java | No tests yet (Phase 1) |
| Python | No tests yet (Phase 2) |
| Frontend | No tests yet (Phase 9) |
| Evaluation | No dataset yet (Phase 10) |

## Environment facts (verified 2026-08-09)

| Tool | Present | Required | OK |
|---|---|---|---|
| Java | 1.8.0_392 (Corretto, default); JDK 23 via Homebrew | **21 (LTS)** | ✗ |
| Maven | not installed | 3.9+ | ✗ |
| Python | 3.10.18 (default); 3.13.1 via Homebrew; `uv` 0.8.4 | 3.11+ | ✗ (default) |
| Node | 22.13.0 | 20+ | ✓ |
| Docker | 27.3.1 | 24+ | ✓ |
| Docker Compose | v2.30.3 | v2 | ✓ |
| Disk free | 317 G | ~15 G | ✓ |
| Platform | macOS, Apple Silicon (arm64) | — | — |

## Recommended next action

Install Java 21 + Maven (`brew install openjdk@21 maven`, then set `JAVA_HOME`), confirm with
`make check`, then run `/implement-phase 1`.

---

### How to maintain this file

Update at the end of every session. Keep it under two screens. Record what is **verified**, not
what was attempted. No chat transcripts, no narrative history — the git log covers that.
