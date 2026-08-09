# ADR-010: Local-first, $0-recurring-cost deployment

**Status:** Accepted
**Date:** 2026-08-09
**Phase:** 0, 12, 13

## Context

NexusIQ is a portfolio and learning project built under a hard constraint stated by the project
owner: **no recurring infrastructure cost.** The stack it needs — Postgres, Redis, Kafka, an
OTel pipeline, three services — is exactly the stack that is expensive to host.

## Problem

Where does the system run, and what is the supported deployment target?

## Options considered

1. **Cloud VM + Docker Compose** (~$10–20/month). One box, everything runs, a real public URL.
   Recurring cost. Rejected by the constraint.
2. **AWS managed services** (ECS/Fargate + RDS + MSK + ElastiCache). The most "enterprise" story
   and by far the most expensive — MSK alone exceeds the entire budget. Rejected.
3. **PaaS** (Render/Railway/Fly). Easy TLS and deploys; managed Kafka is a paid add-on and free
   tiers sleep, hibernate databases, or expire. Rejected as unreliable and not actually free.
4. **Local-first: Docker Compose as the supported deployment, plus Kubernetes manifests verified
   on `kind`.** Zero cost, fully reproducible, no hosted URL.

## Decision

The **supported deployment target is local Docker Compose.** The full stack — frontend,
`spring-api`, `ai-service`, Postgres+pgvector, Redis, Kafka, kafka-ui, OTel Collector, Jaeger/Tempo,
Prometheus, Grafana — runs on a developer machine and is reproducible from a clean clone with one
command.

Kubernetes (Phase 13) produces **manifests as a deployment artefact**, verified locally on `kind`.
It is a learning target and a portfolio artefact, not a hosted environment.

No paid managed service — compute, database, Kafka, Redis, hosting, APM or model API — is part of
the architecture. The default LLM path uses a free tier (ADR-008) and embeddings are local
(ADR-009).

## Rationale

The constraint is real and non-negotiable, so the only question is what to optimise instead. The
answer is a demo so reproducible that anyone can run it: `git clone && make demo`. For a portfolio
project that is arguably stronger than a URL — a reviewer can run it, break it, and read the code
behind it, rather than clicking through a screen.

Designing around a paid target and then not paying for it produces the worst outcome: an
architecture that assumes managed Kafka and a demo that does not run. Better to design honestly
for the environment that will actually exist.

## Trade-offs accepted

- **No public demo URL.** The demo is a local run or a recorded walkthrough. This is a genuine
  portfolio cost, accepted deliberately.
- No production experience with real cloud operations — no managed-service tuning, no cloud
  networking, no real load. The Kubernetes work partially compensates; it does not replace it.
- The full stack is heavy for a laptop (Kafka + Postgres + observability + a 1–2 GB AI image).
  Footprint must be measured in Phase 12 and must fit 16 GB.
- Anyone evaluating the project needs Docker and a few minutes, rather than a browser.
- Free-tier LLM quotas can change and could break the demo path; the `mock` provider keeps the
  system demonstrable regardless.

## Consequences

- Every architectural proposal is checked against cost. **A paid service is an automatic reject**
  in `/architecture-review`.
- `make demo` must bring up the stack, migrate, seed the corpus and create a demo user in one
  command, with a documented time-to-ready.
- Local memory footprint is a tracked non-functional requirement.
- A recorded end-to-end demo video substitutes for a live URL.
- Kubernetes manifests must be verifiable on `kind`, with secrets from `Secret` objects and never
  literals — so they would be usable on a real cluster without rework.
- If a public demo is ever wanted, that is a separate investigation into genuinely free options,
  not a redesign — the Compose stack is already the deployable unit.

## Revisit when

A genuinely free hosting option covering Postgres + Kafka + two services appears, or the project
owner's budget changes. Neither changes the architecture — only the deployment target.
