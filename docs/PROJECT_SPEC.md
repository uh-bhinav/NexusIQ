# NexusIQ — Project Specification

**Enterprise Knowledge & Decision Intelligence Platform**

Status: specification frozen for v1. Changes require an ADR.

---

## 1. The problem

Large organizations hold their operative knowledge in fragmented documents: security policies,
SOPs, architecture standards, compliance requirements, vendor documentation, contracts, incident
reports and records of decisions already made.

The question is not *"how do I search these documents?"* — keyword search answers that badly, and
a RAG chatbot answers it only slightly less badly.

The real question is:

> How can an AI system retrieve the **authoritative** organizational knowledge relevant to a
> decision, reason over it, produce an **evidence-backed** recommendation, **expose its
> uncertainty**, enforce organizational policy, escalate to a human when the evidence does not
> support autonomy, and leave a **complete audit trail**?

That is a systems problem, not a prompting problem. It requires knowledge engineering, retrieval,
multi-agent reasoning, deterministic governance, event-driven backend engineering, human workflow,
and production observability working together.

## 2. What NexusIQ is

A platform that ingests an organization's documents into a versioned, permission-scoped knowledge
base, and runs governed multi-agent decision workflows over it that produce recommendations a human
can audit, challenge, and approve.

## 3. What NexusIQ is not

- Not a chatbot. There is no free-form conversational surface.
- Not "chat with your PDFs".
- Not an autonomous approver. It **recommends**; humans decide on anything consequential.
- Not a demo of calling an LLM API.

## 4. Primary use case: vendor / technology approval

**Input:** *"Should Vendor Alpha be approved for our European production environment?"*

**Knowledge base:** security policy (two versions), EU data residency policy, procurement policy,
production architecture standard, vendor security report, vendor data-processing addendum, prior
approval decisions, relevant incident reports.

**The system:**

1. Classifies the request and extracts entities (vendor, jurisdiction, environment).
2. Plans what evidence is required — which policy domains must be evaluated.
3. Retrieves scoped, ranked, cited evidence per domain.
4. Evaluates each applicable policy: `SATISFIED` / `PARTIALLY_SATISFIED` / `VIOLATED` / `UNKNOWN`.
5. Assesses risk and identifies missing information.
6. Synthesises a recommendation with confidence and cited evidence.
7. Validates: are all claims grounded, citations real, domains covered, confidence justified,
   any contradiction with a retrieved policy?
8. Applies a **deterministic** gate: high risk, low confidence, policy violation, or thin evidence
   coverage → human approval required.
9. An authorised human reviews the full trace and approves or rejects.
10. Everything is written to an append-only audit trail.

**Output shape:**

```
Recommendation:   CONDITIONAL_APPROVAL
Confidence:       0.72
Risk:             MEDIUM
Human approval:   REQUIRED (Security Architecture)

Policy findings
  ✓  Procurement policy PR-07 §2        SATISFIED
  ✓  Security certification SP-102 §4.2 SATISFIED
  ✗  EU data residency DR-11 §3.1       UNKNOWN — no regional processing statement found
  ⚠  DR requirement AS-04 §6            PARTIALLY_SATISFIED — RTO stated, RPO absent

Evidence
  SP-102 §4.2 p.11 (sim 0.87) · DR-11 §3.1 p.4 (sim 0.81) · Vendor report p.17 (sim 0.79)

Required actions
  1. Obtain written regional data-processing confirmation
  2. Security architect approval
  3. Update vendor risk register
```

Every substantive line traces to a `chunk_id` in a real document.

## 5. Core design principle

> **Deterministic software handles deterministic work. LLMs handle reasoning.**

| Deterministic (Java / SQL / config) | Probabilistic (LLM) |
|---|---|
| Authentication, RBAC, tenant isolation | Intent understanding |
| Workflow state and transitions | Deciding what evidence is needed |
| Approval thresholds and routing | Semantic retrieval relevance |
| Persistence, transactions, audit | Policy interpretation |
| Retries, idempotency, budgets | Risk reasoning |
| Citation verification | Recommendation synthesis |
| Cost and iteration limits | Ambiguity detection |

The model is a component with a schema, a budget, and a validator around it — not an authority.

## 6. Functional scope (v1)

**In scope**

- Email/password auth, JWT, refresh, four roles (`ADMIN`, `ANALYST`, `APPROVER`, `VIEWER`).
- Workspaces with membership, enforcing full tenant isolation.
- Document upload (PDF, DOCX, TXT, MD), versioning, checksum, async ingestion, status tracking.
- Hierarchical chunking with section/page metadata; local embeddings; pgvector storage.
- Hybrid retrieval: vector + metadata filter + rerank + context assembly, always cited.
- Semantic knowledge-base search UI.
- Seven-node LangGraph decision workflow with structured outputs.
- Guardrails: input, retrieval, output, workflow; prompt-injection defence.
- Deterministic human-in-the-loop gate; approval queue; approve/reject with comment.
- Append-only audit trail, queryable in the UI.
- SSE live workflow trace.
- OpenTelemetry traces + metrics across all services; token/cost/latency per agent.
- Evaluation harness: retrieval, groundedness, decision accuracy over a labelled dataset.
- Full local Docker Compose stack; CI; Kubernetes manifests verified on kind.

**Explicitly out of scope for v1**

Conversational chat · SSO/OAuth/SAML · document editing · real-time collaboration · mobile ·
i18n · fine-tuning · multi-region · paid managed cloud services · more than one demonstration
decision type (the second type is a v2 extension, and the architecture must not prevent it).

## 7. Non-functional requirements

| Requirement | Target |
|---|---|
| Non-AI API latency | p95 < 500 ms locally |
| Retrieval latency | < 1 s for top-k over the sample corpus |
| Ingestion | 20-page PDF processed < 60 s |
| Decision workflow | measured, not SLA'd: report p50/p95 |
| Cost per decision | hard cap `MAX_WORKFLOW_COST_USD`; measured and reported |
| Recurring infra cost | **$0** (ADR-010) |
| Tenant isolation | zero cross-workspace leakage; negative test per endpoint |
| Traceability | one correlation id spans HTTP → Kafka → AI → agents |
| Auditability | every security- and decision-relevant action recorded, append-only |

## 8. Success criteria

The project succeeds when a reviewer can watch this, live, on a laptop, with nothing paid:

1. Log in, create a workspace, add a member.
2. Upload the sample corpus; watch async ingestion complete.
3. Search the knowledge base and get cited results.
4. Submit *"Should Vendor Alpha be approved for EU production?"*.
5. Watch the SSE trace advance through each agent node.
6. See a recommendation with confidence, risk, policy findings and resolvable citations.
7. See that data residency came back `UNKNOWN` — and that the system said so instead of guessing.
8. See the deterministic gate escalate to human approval, and why.
9. Approve as an authorised user; see the final decision recorded.
10. Open the audit log and the trace/metrics view; see per-agent latency, tokens and cost.
11. Upload a document containing a prompt-injection attempt; watch it get flagged, not obeyed.
12. Ask a question with no supporting evidence; watch it return `INSUFFICIENT_INFORMATION`.

Items 7, 11 and 12 matter more than items 1–6. Anyone can demo the happy path.

## 9. Sample corpus

`docs/sample-enterprise/` — synthetic, committed, deliberately imperfect:

```
security/     security-policy-v1.md, security-policy-v2.md   (v2 supersedes; conflicting clause)
compliance/   eu-data-residency-DR-11.md, gdpr-policy.md
procurement/  vendor-approval-policy-PR-07.md
architecture/ production-architecture-standard-AS-04.md
vendors/      vendor-alpha-security-report.md      (contains an injection attempt)
              vendor-alpha-data-processing.md      (silent on EU region — the UNKNOWN)
              vendor-beta-security-report.md       (clean approval case)
historical/   decision-2024-011-vendor-gamma.md    (rejected, with reasons)
incidents/    incident-2024-07-vendor-outage.md
```

Never commit real proprietary documents.

## 10. Reference map

Architecture `docs/ARCHITECTURE.md` · Phases `docs/IMPLEMENTATION/ROADMAP.md` · State
`docs/IMPLEMENTATION/STATUS.md` · Decisions `docs/DECISIONS/` · Schema `docs/DATABASE/SCHEMA.md` ·
API `docs/API/API_DESIGN.md` · AI `docs/AI/` · Testing `docs/TESTING/STRATEGY.md` ·
Operations `docs/OPERATIONS/`.
