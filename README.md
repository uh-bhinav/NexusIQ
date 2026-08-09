# NexusIQ

**Enterprise Knowledge & Decision Intelligence Platform**

NexusIQ turns an organization's documents — security policies, compliance standards, architecture
requirements, vendor reports, prior decisions — into **grounded, evidence-backed decision
recommendations** with governance controls, human approval, a complete audit trail, and production
observability.

It is not a chatbot. It is not "chat with your PDFs". It is a system for answering questions like:

> *"Should Vendor Alpha be approved for our European production environment?"*

with a recommendation that cites its evidence, states its confidence, admits what it does not know,
and escalates to a human when the evidence does not support acting alone.

> **Status: pre-implementation.** The architecture, roadmap and engineering rules are complete;
> application code starts at Phase 0. See [`docs/IMPLEMENTATION/STATUS.md`](docs/IMPLEMENTATION/STATUS.md).

---

## What it does

```
Question ─→ intent ─→ plan evidence needs ─→ retrieve (RAG, cited)
             ─→ policy analysis ∥ risk analysis
             ─→ recommendation ─→ validation ─→ deterministic gate
             ─→ human approval (when warranted) ─→ audit record
```

Example output:

```
Recommendation:   CONDITIONAL_APPROVAL      Confidence: 0.72    Risk: MEDIUM
Human approval:   REQUIRED (Security Architecture)

✓  Procurement policy PR-07 §2         SATISFIED
✓  Security certification SP-102 §4.2  SATISFIED
✗  EU data residency DR-11 §3.1        UNKNOWN — no regional processing statement found
⚠  DR requirement AS-04 §6             PARTIALLY_SATISFIED — RTO stated, RPO absent

Evidence: SP-102 §4.2 p.11 · DR-11 §3.1 p.4 · Vendor report p.17
```

Every line traces back to a specific chunk of a specific document.

## Why it is built this way

**Deterministic software handles deterministic work. LLMs handle reasoning.** Authentication,
authorization, workflow state, approval thresholds, citation verification, budgets and the audit
trail are ordinary code and SQL. The model produces schema-constrained, evidence-cited analysis —
and is then validated, budgeted, and gated by systems it cannot influence.

The model recommends. It never authorises.

## Stack

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot, Spring Security, JPA, Flyway |
| AI service | Python 3.11+, FastAPI, LangGraph, Pydantic |
| Data | PostgreSQL + pgvector (system of record and vector store) |
| Cache | Redis |
| Events | Apache Kafka (KRaft) |
| Embeddings | `BAAI/bge-small-en-v1.5`, local, in-process |
| LLM | Gemini by default, behind a provider abstraction |
| Frontend | React, TypeScript, Vite, Tailwind/shadcn |
| Observability | OpenTelemetry → Jaeger/Tempo + Prometheus + Grafana |
| Runtime | Docker Compose (supported target), Kubernetes manifests for `kind` |

Runs entirely on a laptop at **$0 recurring cost** — see
[ADR-010](docs/DECISIONS/ADR-010-local-first-zero-cost-deployment.md).

## Quickstart

```bash
cp .env.example .env
make setup      # verify prerequisites, install dependencies
make up         # start the stack
make migrate    # apply migrations
make seed       # load the sample enterprise corpus
```

Prerequisites and current-machine setup notes:
[`docs/OPERATIONS/LOCAL_DEV.md`](docs/OPERATIONS/LOCAL_DEV.md).

## Documentation

| | |
|---|---|
| What and why | [`docs/PROJECT_SPEC.md`](docs/PROJECT_SPEC.md) |
| Architecture | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) |
| Roadmap (14 phases) | [`docs/IMPLEMENTATION/ROADMAP.md`](docs/IMPLEMENTATION/ROADMAP.md) |
| Current status | [`docs/IMPLEMENTATION/STATUS.md`](docs/IMPLEMENTATION/STATUS.md) |
| Task backlog | [`docs/IMPLEMENTATION/TODO.md`](docs/IMPLEMENTATION/TODO.md) |
| Decisions and rationale | [`docs/DECISIONS/`](docs/DECISIONS/README.md) |
| AI subsystem | [`docs/AI/`](docs/AI/ARCHITECTURE.md) |
| Database schema | [`docs/DATABASE/SCHEMA.md`](docs/DATABASE/SCHEMA.md) |
| API design | [`docs/API/API_DESIGN.md`](docs/API/API_DESIGN.md) |
| Testing | [`docs/TESTING/STRATEGY.md`](docs/TESTING/STRATEGY.md) |
| Operations | [`docs/OPERATIONS/`](docs/OPERATIONS/LOCAL_DEV.md) |

Engineering rules that constrain implementation live in [`.claude/rules/`](.claude/rules/), and the
always-loaded agent context is [`CLAUDE.md`](CLAUDE.md).

## The interesting parts

- **Grounding is enforced, not requested.** Citations are verified by set membership against the
  retrieved chunks. A hallucinated reference cannot survive.
- **`UNKNOWN` is a first-class answer.** The sample corpus deliberately omits an EU data-residency
  statement; a system that answers confidently there has failed.
- **Documents are treated as hostile input.** The corpus contains a prompt-injection attempt; it is
  flagged and reported as a finding, not obeyed.
- **The escalation gate is outside the model.** Thresholds live in Java, so a manipulated
  recommendation still cannot approve itself.
- **Cost and latency are attributed per agent**, per run, and surfaced.
- **Quality is measured**, not asserted: ≥30 labelled cases, retrieval and groundedness metrics,
  and a committed baseline.

## License

Not yet chosen.
