# Agents

Seven nodes, one responsibility each. Every output is a validated Pydantic model. Every
substantive claim carries `evidence_ids`.

Graph and state: `ARCHITECTURE.md`. Adding an agent requires an ADR.

---

## 1. `intent` — Intent Analyzer

**Job:** understand the request. Nothing else.

```python
class IntentAnalysis(BaseModel):
    decision_type: Literal["vendor_approval", "technology_approval", "policy_question", "unsupported"]
    entities: list[str]
    jurisdiction: str | None
    environment: Literal["production", "staging", "development", "unspecified"]
    required_domains: list[Literal["security", "data_residency", "procurement",
                                   "architecture", "compliance", "operational_risk"]]
    missing_information: list[str]
    confidence: float = Field(ge=0, le=1)
```

Rules: never invent a jurisdiction or environment the question does not state — that is what
`missing_information` and `unspecified` are for. `unsupported` is a valid answer and terminates
the run early.

## 2. `context_planner` — Context Planner

**Job:** decide what evidence must be retrieved, so retrieval is targeted rather than indiscriminate.

```python
class RetrievalTask(BaseModel):
    domain: str
    query: str                       # rewritten for retrieval, not the raw question
    document_types: list[str]
    rationale: str
    priority: Literal["CRITICAL", "IMPORTANT", "SUPPORTING"]

class ContextPlan(BaseModel):
    tasks: list[RetrievalTask] = Field(min_length=1, max_length=8)
    historical_lookup: bool          # search prior decisions?
```

Rules: one task per required domain; queries are rewritten for retrieval (the user's phrasing is
rarely the best query); capped at 8 to bound cost.

## 3. `retrieval` — Retrieval Agent

**Job:** execute the plan. **No LLM call** — this is deterministic code plus embeddings.

Per task: embed the rewritten query → vector search scoped to `workspace_id` → metadata filter →
rerank → threshold. Tasks run concurrently. Results are deduplicated by `chunk_id`, keeping the
highest score, and tagged with their originating domain.

Output: `list[RetrievedChunk]`, each carrying `chunk_id`, `document_id`, `document_name`,
`document_type`, `document_version`, `is_current`, `section`, `page`, `content`,
`similarity_score`, `rerank_score`, `trust_level`, `is_flagged`. Details: `RAG.md`.

**The model never sees an anonymous chunk.** Every chunk is citable by construction.

## 4. `policy_analyst` — Policy Analyst *(parallel with 5)*

**Job:** evaluate the subject against each applicable policy. One finding per policy.

```python
class PolicyFinding(BaseModel):
    policy_name: str
    policy_reference: str            # "SP-102 §4.2"
    status: Literal["SATISFIED", "PARTIALLY_SATISFIED", "VIOLATED", "UNKNOWN"]
    explanation: str
    evidence_ids: list[str]          # must be non-empty unless status == UNKNOWN
    confidence: float = Field(ge=0, le=1)
```

Rules that matter more than the rest of this document:

- **`UNKNOWN` when the evidence does not say.** Absence of evidence is not evidence of compliance,
  and it is not a violation either. The EU-residency case in the sample corpus exists specifically
  to test this.
- Every non-`UNKNOWN` status must cite evidence. A status with no `evidence_ids` fails validation.
- When two versions of a policy conflict, prefer the current one **and say so** in the explanation.
- Never infer a policy requirement that is not in the retrieved text.

## 5. `risk_analyzer` — Risk Analyzer *(parallel with 4)*

**Job:** assess risk from the evidence and the gaps.

```python
class RiskFactor(BaseModel):
    category: Literal["SECURITY", "COMPLIANCE", "OPERATIONAL", "DATA", "VENDOR", "ARCHITECTURAL"]
    description: str
    severity: Literal["INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL"]
    likelihood: Literal["UNLIKELY", "POSSIBLE", "LIKELY"]
    evidence_ids: list[str]

class RiskAssessment(BaseModel):
    risk_level: Literal["LOW", "MEDIUM", "HIGH", "CRITICAL"]
    factors: list[RiskFactor]
    missing_information: list[str]
    confidence: float = Field(ge=0, le=1)
```

Rules: missing critical information raises risk — it never lowers it. Uncited risk factors are
speculation and are rejected.

## 6. `decision` — Decision Agent

**Job:** synthesise findings and risk into one recommendation. It has **no** retrieval access; it
reasons only over what nodes 4 and 5 produced.

```python
class Recommendation(BaseModel):
    recommendation: Literal["APPROVE", "CONDITIONAL_APPROVAL", "REJECT", "INSUFFICIENT_INFORMATION"]
    reasoning_summary: str
    confidence: float = Field(ge=0, le=1)
    key_evidence_ids: list[str]
    required_actions: list[str]
    conditions: list[str]
    unresolved_questions: list[str]
```

Rules: any `VIOLATED` finding forbids a plain `APPROVE`. A `CONDITIONAL_APPROVAL` must list
concrete conditions. If critical domains are `UNKNOWN`, the honest answer is
`INSUFFICIENT_INFORMATION` — and it is a *good* outcome, not a failure. Confidence must reflect
evidence coverage, not fluency.

## 7. `validator` — Validation Agent

**Job:** try to break the recommendation before a human sees it. Part deterministic, part LLM.

```python
class ValidationCheck(BaseModel):
    check: Literal["EVIDENCE_GROUNDING", "CITATION_VALIDITY", "CONTRADICTION",
                   "COMPLETENESS", "CONFIDENCE_JUSTIFICATION", "HALLUCINATION"]
    passed: bool
    details: str
    offending_claims: list[str]

class ValidationResult(BaseModel):
    passed: bool
    checks: list[ValidationCheck]
    evidence_coverage: float = Field(ge=0, le=1)
    recommended_action: Literal["ACCEPT", "RETRY", "ESCALATE"]
```

- `CITATION_VALIDITY` is **deterministic**: every cited id must exist in the retrieved set. A
  hallucinated id is caught by a set membership test, not by asking a model.
- `EVIDENCE_GROUNDING`, `CONTRADICTION` and `HALLUCINATION` are LLM checks against the evidence.
- `COMPLETENESS` is deterministic: were all `required_domains` from the intent actually evaluated?
- Failure → retry (max 2) → forced escalation. Details: `GUARDRAILS.md`.

## 8. `approval_router` — **not an agent**

Pure deterministic routing. **Zero LLM calls.** Java's gate (ADR-006) is authoritative; the router
mirrors it so the graph knows whether to interrupt. Escalates on: any `VIOLATED` finding, risk ≥
`HITL_ESCALATE_ON_RISK`, confidence < `HITL_MIN_CONFIDENCE`, coverage <
`HITL_MIN_EVIDENCE_COVERAGE`, validator escalation, or a `PROMPT_INJECTION_ATTEMPT` finding.

---

## Cross-cutting rules

1. One job per agent. "And also" means it is two agents — or deterministic code.
2. Every output is a Pydantic model, validated before use.
3. Every substantive claim carries `evidence_ids`.
4. Honest enums everywhere: `UNKNOWN`, `INSUFFICIENT_INFORMATION`, `unsupported`.
5. Every prompt carries the standing injection clause (`PROMPTS.md`).
6. Every call records model, tokens, latency, cost.
7. Every agent is independently testable with a constructed state and fixture output — and has
   tests for malformed output, not just the happy path.
