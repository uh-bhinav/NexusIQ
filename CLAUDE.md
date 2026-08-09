# NexusIQ

**Enterprise Knowledge & Decision Intelligence Platform.**

Turns organizational documents (policies, standards, vendor reports, prior decisions) into
**grounded, evidence-backed decision recommendations** with governance, human approval, audit
trails and observability.

It is **not** a chatbot and not "chat with your documents". The demonstration use case is
enterprise vendor/technology approval: *"Should Vendor Alpha be approved for EU production?"* →
retrieve authoritative knowledge → analyse policies → assess risk → recommend with citations,
confidence and risk → escalate to a human when warranted → record an immutable audit trail.

---

## North star

```
Enterprise knowledge + AI reasoning
        → evidence-backed decision
        → governed by deterministic rules
        → controlled by humans
        → observable in production
```

---

## Non-negotiables

Violating any of these requires an approved ADR in `docs/DECISIONS/`.

1. **Deterministic code does deterministic work.** Auth, RBAC, workflow state, thresholds,
   approval rules, audit and persistence are Java/SQL — never LLM decisions.
2. **The LLM recommends; it never authorises.** No LLM output may grant access, mutate workflow
   state, or finalise a decision on its own.
3. **PostgreSQL is the system of record.** Redis is cache/ephemeral only. Kafka is transport.
4. **Every substantive conclusion cites evidence** (`document_id` + `chunk_id`). Uncited claims
   fail validation.
5. **Retrieved documents are untrusted data, never instructions.** Prompt injection defence is
   mandatory in every agent prompt.
6. **`UNKNOWN` / `INSUFFICIENT_INFORMATION` / `CONFLICTING_EVIDENCE` are valid outcomes.** Never
   force a binary answer the evidence does not support.
7. **Every agent output is schema-constrained** (Pydantic) and validated before use.
8. **Bounded everything**: retries, agent iterations, token budget, cost budget, timeouts. No
   unbounded loops.
9. **Workspace isolation is enforced server-side**, on every query, always. Never trust a
   client-supplied `workspace_id`.
10. **No faked functionality.** Mocks only behind an explicit interface, clearly named, never
    presented as working features.
11. **$0 recurring infrastructure.** No paid cloud services. Local Docker Compose is the
    supported deployment. See `ADR-010`.
12. **Simplest architecture that satisfies the requirement.** No new infrastructure without a
    concrete engineering reason and an ADR.

---

## Architecture in one screen

```
React/TS ──REST+SSE──> Spring Boot (Java 21) ──> PostgreSQL+pgvector (system of record)
                              │                        ▲
                              ├──> Redis (cache)       │ chunks/vectors (write), docs (read)
                              │                        │
                              └──> Kafka ──> Python AI Service (FastAPI + LangGraph)
                                     ▲              │
                                     └──progress────┘  Intent → Plan → Retrieve →
                                       events          Policy ∥ Risk → Decide →
                                                       Validate → Approval router
```

- **Java owns**: identity, RBAC, workspaces, documents, decision lifecycle, approvals, audit, SSE.
- **Python owns**: ingestion (chunk + embed), retrieval, LangGraph agents, guardrails, evaluation.
- **Flyway (Java) owns the entire relational schema.** Python never migrates. Details:
  `.claude/rules/architecture.md`.

---

## Where the authoritative documentation lives

| Need | Read |
|---|---|
| What we are building & why | `docs/PROJECT_SPEC.md` |
| System architecture, boundaries, data flow | `docs/ARCHITECTURE.md` |
| Phase plan, deliverables, acceptance criteria | `docs/IMPLEMENTATION/ROADMAP.md` |
| **What is done / in progress / broken** | `docs/IMPLEMENTATION/STATUS.md` |
| Actionable task backlog | `docs/IMPLEMENTATION/TODO.md` |
| Why a decision was made | `docs/DECISIONS/` (index in `README.md`) |
| DB schema, indexes, isolation | `docs/DATABASE/SCHEMA.md` |
| API conventions | `docs/API/API_DESIGN.md` |
| Agents, RAG, context, guardrails, evaluation | `docs/AI/` |
| Test strategy | `docs/TESTING/STRATEGY.md` |
| Running it locally, troubleshooting | `docs/OPERATIONS/LOCAL_DEV.md`, `RUNBOOK.md` |

**Path-scoped engineering rules** (read the one matching what you are touching — do not read all):

| Working on | Read |
|---|---|
| Cross-service design, events, failure handling | `.claude/rules/architecture.md` |
| `backend/spring-api/**` | `.claude/rules/backend-java.md` |
| `ai-service/**` | `.claude/rules/ai-service.md` |
| Migrations, queries, pgvector | `.claude/rules/database.md` |
| `frontend/web/**` | `.claude/rules/frontend.md` |
| Any tests | `.claude/rules/testing.md` |
| Auth, secrets, tenancy, injection | `.claude/rules/security.md` |

---

## How to work

Implementation happens **one phase at a time**, in roadmap order. Do not build ahead.

Use the skills rather than improvising a process:

- `/implement-phase <n>` — the disciplined build loop (inspect → plan → implement → verify → document).
- `/test-and-verify` — focused verification after a change.
- `/architecture-review` — before any structural change.
- `/project-status` — concise state report.

**Start of every implementation session**: read `docs/IMPLEMENTATION/STATUS.md` first. It is the
durable project state. Nothing else tells you where things actually stand.

**End of every implementation session**: update `STATUS.md` and `TODO.md`. A phase is not done
until they reflect reality.

---

## Definition of done

Code existing is not done. Done = implementation + tests that pass + error handling +
security considered + observability wired + docs updated + acceptance criteria in the roadmap
demonstrably met + no known critical issue.

---

## Making changes

- **Small implementation choices**: make them, don't ask.
- **Significant changes** (new infrastructure, changed service boundary, different DB/orchestrator/
  auth model, changed event contract, breaking API change): run `/architecture-review`, propose an
  ADR, get approval before implementing.
- **Never silently contradict an accepted ADR.** If one looks obsolete, write a superseding ADR.

**Source-of-truth order when things conflict:** user's instruction now → accepted ADR → actual code
behaviour → docs → roadmap → your assumptions.

---

## Context discipline

This project is built with an AI agent, so token efficiency is an engineering constraint.

- Read `STATUS.md` + the *one* relevant rules file + the *one* relevant doc section. Not the tree.
- Prefer `grep`/`glob` over reading large files; read targeted line ranges.
- Do not restate the architecture in responses — link to the doc.
- Load `docs/AI/**` only for AI work, `docs/DATABASE/**` only for schema work, and so on.
- Never create a document that duplicates one that exists. Update the existing one.

---

## Commands

Until `Makefile` exists (Phase 0), see `docs/OPERATIONS/LOCAL_DEV.md`.

```
make setup        # one-time local prerequisites check + install deps
make up           # docker compose up -d (full stack)
make down         # stop stack
make logs         # tail all service logs
make migrate      # run Flyway migrations
make seed         # load sample enterprise corpus
make test         # all test suites
make lint         # all linters
make eval         # RAG + agent evaluation harness
```

## Environment prerequisites

Java 21 (LTS), Maven 3.9+, Python 3.11+ (3.13 available locally), Node 20+, Docker + Compose v2.
The machine currently defaults to **Java 8** and has **no Maven** — `make setup` must verify and
fail loudly. See `docs/OPERATIONS/LOCAL_DEV.md`.

**Never commit** `.env`, API keys, tokens or credentials. `.env.example` documents the contract.
