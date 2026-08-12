"""Pydantic schemas for the evaluation harness (docs/AI/EVALUATION.md).
Deliberately separate from app/models/agents.py — these describe the
*labelled expectation* for a case and the *outcome* of running it, not an
agent's structured output."""

from pydantic import BaseModel, Field


class ExpectedOutcome(BaseModel):
    decision_type: str
    required_domains: list[str] = Field(default_factory=list)
    # Document slugs (filename stem under docs/sample-enterprise/, e.g.
    # "vendor-beta-security-report") — not UUIDs, which don't exist until a
    # corpus is seeded. The harness resolves slug -> chunk/document id after
    # seeding (see corpus.py).
    relevant_document_ids: list[str] = Field(default_factory=list)
    policy_statuses: dict[str, str] = Field(default_factory=dict)
    # More than one recommendation can be defensible for a given case
    # (docs/AI/EVALUATION.md: "pretending otherwise produces a metric that
    # rewards luck") — a set of acceptable values, not one.
    recommendation: list[str]
    requires_human_approval: bool | None = None
    # Claims the recommendation/reasoning must NOT make — the direct
    # hallucination-rate check (confabulation, prompt-injection compliance,
    # stale-policy-version claims).
    must_not_claim: list[str] = Field(default_factory=list)


class EvalCase(BaseModel):
    id: str
    question: str
    category: str
    expected: ExpectedOutcome


class RetrievalCaseMetrics(BaseModel):
    recall_at_5: float
    recall_at_10: float
    precision_at_5: float
    reciprocal_rank: float
    retrieved_count: int


class GenerationCaseMetrics(BaseModel):
    groundedness: float | None = None
    citation_validity_rate: float | None = None
    hallucinated: bool
    hallucinated_claims: list[str] = Field(default_factory=list)


class DecisionCaseMetrics(BaseModel):
    recommendation_correct: bool
    actual_recommendation: str
    policy_status_accuracy: float | None = None
    escalation_correct: bool | None = None
    actual_requires_human_approval: bool
    intent_correct: bool | None = None
    actual_decision_type: str | None = None


class CaseResult(BaseModel):
    case_id: str
    category: str
    question: str
    error: str | None = None
    retrieval: RetrievalCaseMetrics | None = None
    generation: GenerationCaseMetrics | None = None
    decision: DecisionCaseMetrics | None = None
    latency_ms: int | None = None
    total_input_tokens: int = 0
    total_output_tokens: int = 0
    estimated_cost_usd: float = 0.0


class AggregateMetrics(BaseModel):
    case_count: int
    error_count: int
    recall_at_5: float
    recall_at_10: float
    precision_at_5: float
    mrr: float
    empty_result_rate: float
    groundedness: float | None = None
    citation_validity_rate: float | None = None
    hallucination_rate: float | None = None
    recommendation_accuracy: float | None = None
    intent_accuracy: float | None = None
    escalation_precision: float | None = None
    escalation_recall: float | None = None
    total_input_tokens: int = 0
    total_output_tokens: int = 0
    estimated_cost_usd: float = 0.0


class EvalRun(BaseModel):
    provider: str
    workflow_version: str
    prompt_version: str
    case_results: list[CaseResult]
    aggregate: AggregateMetrics
