"""Validator (docs/AI/AGENTS.md #7): tries to break the recommendation
before a human sees it. Deterministic checks run in this module directly —
CITATION_VALIDITY and COMPLETENESS never go near the model, and CONTRADICTION
has a deterministic pre-check that overrides the LLM's own opinion when it
fires (docs/AI/GUARDRAILS.md: "Deterministic checks run first and are
decisive — the validator model is a second opinion, never the only one.").
`recommended_action` is NOT assembled here — see models/agents.py's
ValidationResult docstring for why that stays in graph/nodes.py.
"""

from collections.abc import Sequence

from app.llm.provider import ModelProvider, ModelResult
from app.models.agents import (
    LLMValidationOutput,
    PolicyFinding,
    Recommendation,
    RiskAssessment,
    ValidationCheck,
)
from app.prompts.compose import compose_prompt


def _system_prompt() -> str:
    return compose_prompt("validator_v1.md")


def _check_citation_validity(
    findings: list[PolicyFinding],
    risk: RiskAssessment,
    recommendation: Recommendation,
    retrieved_chunk_ids: set[str],
) -> ValidationCheck:
    cited: set[str] = set(recommendation.key_evidence_ids)
    for finding in findings:
        cited.update(finding.evidence_ids)
    for factor in risk.factors:
        cited.update(factor.evidence_ids)

    invalid = sorted(cited - retrieved_chunk_ids)
    return ValidationCheck(
        check="CITATION_VALIDITY",
        passed=not invalid,
        details=(
            "Every cited evidence id exists in the retrieved set."
            if not invalid
            else f"{len(invalid)} cited id(s) do not exist in the retrieved set."
        ),
        offending_claims=invalid,
    )


def _check_completeness(
    required_domains: Sequence[str], planned_domains: list[str]
) -> ValidationCheck:
    planned = set(planned_domains)
    missing = sorted(d for d in required_domains if d not in planned)
    return ValidationCheck(
        check="COMPLETENESS",
        passed=not missing,
        details=(
            "Every required domain from intent was queried."
            if not missing
            else f"{len(missing)} required domain(s) were never queried by the context plan."
        ),
        offending_claims=missing,
    )


def _contradiction_pre_check(
    findings: list[PolicyFinding], recommendation: Recommendation
) -> tuple[bool, list[str]]:
    """Deterministic half of CONTRADICTION: a VIOLATED policy finding
    alongside an APPROVE/CONDITIONAL_APPROVAL recommendation is an
    unambiguous structural contradiction (docs/AI/GUARDRAILS.md Layer 3
    point 5) — also doubles as the "unsafe recommendation" output guardrail
    from ROADMAP.md's Phase 6 deliverables. Returns (violated, offending)."""
    if recommendation.recommendation not in ("APPROVE", "CONDITIONAL_APPROVAL"):
        return False, []
    violated = [f.policy_name for f in findings if f.status == "VIOLATED"]
    return bool(violated), violated


def _evidence_coverage(findings: list[PolicyFinding], risk: RiskAssessment) -> float:
    """Deterministic proxy for GUARDRAILS.md's "substantive claims with >=1
    valid citation / total substantive claims": findings and risk factors
    are the system's own structured claims, each already required to carry
    evidence_ids — counting those directly is more reliable than asking an
    LLM to estimate coverage over free prose."""
    claims = list(findings) + list(risk.factors)
    if not claims:
        return 1.0
    covered = sum(1 for c in claims if c.evidence_ids)
    return covered / len(claims)


async def validate_recommendation(
    question: str,
    findings: list[PolicyFinding],
    risk: RiskAssessment,
    recommendation: Recommendation,
    evidence_block: str,
    *,
    required_domains: Sequence[str],
    planned_domains: list[str],
    retrieved_chunk_ids: set[str],
    provider: ModelProvider,
    model: str,
) -> tuple[list[ValidationCheck], float, ModelResult[LLMValidationOutput]]:
    citation_check = _check_citation_validity(findings, risk, recommendation, retrieved_chunk_ids)
    completeness_check = _check_completeness(required_domains, planned_domains)
    contradiction_violated, contradiction_offending = _contradiction_pre_check(
        findings, recommendation
    )
    evidence_coverage = _evidence_coverage(findings, risk)

    system = _system_prompt()
    user = (
        f"<user_question>\n{question}\n</user_question>\n\n"
        f"<policy_findings>\n{[f.model_dump() for f in findings]}\n</policy_findings>\n\n"
        f"<risk_assessment>\n{risk.model_dump()}\n</risk_assessment>\n\n"
        f"<recommendation>\n{recommendation.model_dump()}\n</recommendation>\n\n"
        f"{evidence_block}"
    )
    result = await provider.generate_structured(
        system=system, user=user, schema=LLMValidationOutput, model=model, temperature=0.1
    )

    contradiction_check = result.value.contradiction
    if contradiction_violated:
        # Deterministic pre-check is decisive: overrides the LLM's own
        # opinion rather than merely informing it, per GUARDRAILS.md.
        contradiction_check = ValidationCheck(
            check="CONTRADICTION",
            passed=False,
            details=(
                f"{contradiction_check.details} Additionally, a VIOLATED policy finding exists "
                f"alongside an {recommendation.recommendation} recommendation — structurally "
                "unsafe regardless of the model's own assessment."
            ),
            offending_claims=list(
                dict.fromkeys(contradiction_check.offending_claims + contradiction_offending)
            ),
        )

    checks = [
        citation_check,
        completeness_check,
        contradiction_check,
        result.value.evidence_grounding,
        result.value.hallucination,
        result.value.confidence_justification,
    ]
    return checks, evidence_coverage, result
