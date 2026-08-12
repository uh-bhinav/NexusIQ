"""Structured outputs for agent nodes (docs/AI/AGENTS.md). One Pydantic
model per agent — never hand-parsed prose, per .claude/rules/ai-service.md."""

from typing import Literal

from pydantic import BaseModel, Field


class IntentAnalysis(BaseModel):
    decision_type: Literal[
        "vendor_approval", "technology_approval", "policy_question", "unsupported"
    ]
    entities: list[str]
    jurisdiction: str | None = None
    environment: Literal["production", "staging", "development", "unspecified"]
    required_domains: list[
        Literal[
            "security",
            "data_residency",
            "procurement",
            "architecture",
            "compliance",
            "operational_risk",
        ]
    ]
    missing_information: list[str]
    confidence: float = Field(ge=0, le=1)


class RetrievalTask(BaseModel):
    domain: str
    query: str
    document_types: list[str]
    rationale: str
    priority: Literal["CRITICAL", "IMPORTANT", "SUPPORTING"]


class ContextPlan(BaseModel):
    tasks: list[RetrievalTask] = Field(min_length=1, max_length=8)
    historical_lookup: bool


class PolicyFinding(BaseModel):
    policy_name: str
    policy_reference: str
    status: Literal["SATISFIED", "PARTIALLY_SATISFIED", "VIOLATED", "UNKNOWN"]
    explanation: str
    evidence_ids: list[str]
    confidence: float = Field(ge=0, le=1)


class PolicyAnalysisOutput(BaseModel):
    """Structured-output envelope: docs/AI/AGENTS.md specifies PolicyFinding
    per-policy, but the policy_analyst node makes one LLM call producing
    several findings — mirrors ContextPlan wrapping RetrievalTask."""

    findings: list[PolicyFinding]


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


class Recommendation(BaseModel):
    recommendation: Literal["APPROVE", "CONDITIONAL_APPROVAL", "REJECT", "INSUFFICIENT_INFORMATION"]
    reasoning_summary: str
    confidence: float = Field(ge=0, le=1)
    key_evidence_ids: list[str]
    required_actions: list[str]
    conditions: list[str]
    unresolved_questions: list[str]


class ValidationCheck(BaseModel):
    """docs/AI/AGENTS.md #7 exactly. `check` names one of the six named
    validation checks — see agents/validator.py for which are deterministic
    (CITATION_VALIDITY, COMPLETENESS; CONTRADICTION has both a deterministic
    pre-check and an LLM judgment) vs LLM-only (EVIDENCE_GROUNDING,
    HALLUCINATION, CONFIDENCE_JUSTIFICATION)."""

    check: Literal[
        "EVIDENCE_GROUNDING",
        "CITATION_VALIDITY",
        "CONTRADICTION",
        "COMPLETENESS",
        "CONFIDENCE_JUSTIFICATION",
        "HALLUCINATION",
    ]
    passed: bool
    details: str
    offending_claims: list[str]


class ValidationResult(BaseModel):
    passed: bool
    checks: list[ValidationCheck]
    evidence_coverage: float = Field(ge=0, le=1)
    # Deliberately NOT trusted from the LLM — CLAUDE.md non-negotiable #1
    # ("deterministic code does deterministic work"). This field controls
    # graph routing (retry vs escalate vs accept), so graph/nodes.py's
    # validator_node computes it in Python from `passed` and the current
    # iteration count; the LLM call (LLMValidationOutput) never sees or
    # produces this field.
    recommended_action: Literal["ACCEPT", "RETRY", "ESCALATE"]


class LLMValidationOutput(BaseModel):
    """What the validator's LLM call actually judges: the four checks that
    require reading comprehension, not set membership or list traversal.
    CITATION_VALIDITY and COMPLETENESS are computed deterministically in
    agents/validator.py and never asked of the model — a model cannot be
    trusted to correctly enumerate set membership against a list it's also
    being asked to reason over in the same call, and there's no need to ask
    it to when Python can just check directly."""

    evidence_grounding: ValidationCheck
    contradiction: ValidationCheck
    hallucination: ValidationCheck
    confidence_justification: ValidationCheck


class InjectionFinding(BaseModel):
    """A `PROMPT_INJECTION_ATTEMPT` finding (docs/AI/AGENTS.md, GUARDRAILS.md
    "Prompt injection" layer). Raised deterministically, not by asking an
    LLM to self-report — see graph/nodes.py's validator_node and intent_node
    for the two places this gets constructed (retrieved evidence already
    flagged at ingestion; the user's own question matching the same
    heuristic scan)."""

    title: str
    description: str
    confidence: float = Field(ge=0, le=1)
    evidence_ids: list[str]
