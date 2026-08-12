import uuid

import pytest

from app.agents.validator import validate_recommendation
from app.llm.mock_provider import MockProvider, MockResponse
from app.models.agents import (
    LLMValidationOutput,
    PolicyFinding,
    Recommendation,
    RiskAssessment,
    RiskFactor,
    ValidationCheck,
)

_CHUNK_1 = str(uuid.uuid4())
_CHUNK_2 = str(uuid.uuid4())

_RISK = RiskAssessment(
    risk_level="LOW",
    factors=[
        RiskFactor(
            category="DATA",
            description="...",
            severity="LOW",
            likelihood="UNLIKELY",
            evidence_ids=[_CHUNK_2],
        )
    ],
    missing_information=[],
    confidence=0.85,
)

_PASSING_LLM_OUTPUT = LLMValidationOutput(
    evidence_grounding=ValidationCheck(
        check="EVIDENCE_GROUNDING", passed=True, details="ok", offending_claims=[]
    ),
    contradiction=ValidationCheck(
        check="CONTRADICTION", passed=True, details="ok", offending_claims=[]
    ),
    hallucination=ValidationCheck(
        check="HALLUCINATION", passed=True, details="ok", offending_claims=[]
    ),
    confidence_justification=ValidationCheck(
        check="CONFIDENCE_JUSTIFICATION", passed=True, details="ok", offending_claims=[]
    ),
)


def _findings(
    status: str = "SATISFIED", evidence_ids: list[str] | None = None
) -> list[PolicyFinding]:
    return [
        PolicyFinding(
            policy_name="Data Residency Policy",
            policy_reference="SP-102 §1",
            status=status,  # type: ignore[arg-type]
            explanation="...",
            evidence_ids=evidence_ids if evidence_ids is not None else [_CHUNK_1],
            confidence=0.9,
        )
    ]


def _recommendation(
    rec: str = "APPROVE", key_evidence_ids: list[str] | None = None
) -> Recommendation:
    return Recommendation(
        recommendation=rec,  # type: ignore[arg-type]
        reasoning_summary="...",
        confidence=0.8,
        key_evidence_ids=key_evidence_ids or [],
        required_actions=[],
        conditions=[],
        unresolved_questions=[],
    )


@pytest.mark.asyncio
async def test_validateRecommendation_allCitationsValid_citationCheckPasses():
    provider = MockProvider(queue=[MockResponse(value=_PASSING_LLM_OUTPUT)])
    checks, _, _ = await validate_recommendation(
        "question",
        _findings(),
        _RISK,
        _recommendation(),
        "<retrieved_evidence></retrieved_evidence>",
        required_domains=["security"],
        planned_domains=["security"],
        retrieved_chunk_ids={_CHUNK_1, _CHUNK_2},
        provider=provider,
        model="gemini-3.6-flash",
    )
    citation_check = next(c for c in checks if c.check == "CITATION_VALIDITY")
    assert citation_check.passed


@pytest.mark.asyncio
async def test_validateRecommendation_hallucinatedCitation_citationCheckFails():
    hallucinated_id = str(uuid.uuid4())
    provider = MockProvider(queue=[MockResponse(value=_PASSING_LLM_OUTPUT)])
    checks, _, _ = await validate_recommendation(
        "question",
        _findings(evidence_ids=[hallucinated_id]),
        _RISK,
        _recommendation(),
        "<retrieved_evidence></retrieved_evidence>",
        required_domains=["security"],
        planned_domains=["security"],
        retrieved_chunk_ids={_CHUNK_1, _CHUNK_2},
        provider=provider,
        model="gemini-3.6-flash",
    )
    citation_check = next(c for c in checks if c.check == "CITATION_VALIDITY")
    assert not citation_check.passed
    assert hallucinated_id in citation_check.offending_claims


@pytest.mark.asyncio
async def test_validateRecommendation_missingRequiredDomain_completenessCheckFails():
    provider = MockProvider(queue=[MockResponse(value=_PASSING_LLM_OUTPUT)])
    checks, _, _ = await validate_recommendation(
        "question",
        _findings(),
        _RISK,
        _recommendation(),
        "<retrieved_evidence></retrieved_evidence>",
        required_domains=["security", "data_residency"],
        planned_domains=["security"],
        retrieved_chunk_ids={_CHUNK_1, _CHUNK_2},
        provider=provider,
        model="gemini-3.6-flash",
    )
    completeness_check = next(c for c in checks if c.check == "COMPLETENESS")
    assert not completeness_check.passed
    assert completeness_check.offending_claims == ["data_residency"]


@pytest.mark.asyncio
async def test_validateRecommendation_violatedFindingWithApprove_deterministicPreCheckWins():
    """The deterministic pre-check must win over the LLM's own opinion —
    docs/AI/GUARDRAILS.md: "Deterministic checks run first and are decisive."
    """
    llm_says_fine = LLMValidationOutput(
        evidence_grounding=ValidationCheck(
            check="EVIDENCE_GROUNDING", passed=True, details="ok", offending_claims=[]
        ),
        contradiction=ValidationCheck(
            check="CONTRADICTION", passed=True, details="looks fine to me", offending_claims=[]
        ),
        hallucination=ValidationCheck(
            check="HALLUCINATION", passed=True, details="ok", offending_claims=[]
        ),
        confidence_justification=ValidationCheck(
            check="CONFIDENCE_JUSTIFICATION", passed=True, details="ok", offending_claims=[]
        ),
    )
    provider = MockProvider(queue=[MockResponse(value=llm_says_fine)])
    checks, _, _ = await validate_recommendation(
        "question",
        _findings(status="VIOLATED"),
        _RISK,
        _recommendation(rec="APPROVE"),
        "<retrieved_evidence></retrieved_evidence>",
        required_domains=["security"],
        planned_domains=["security"],
        retrieved_chunk_ids={_CHUNK_1, _CHUNK_2},
        provider=provider,
        model="gemini-3.6-flash",
    )
    contradiction_check = next(c for c in checks if c.check == "CONTRADICTION")
    assert not contradiction_check.passed
    assert "Data Residency Policy" in contradiction_check.offending_claims


@pytest.mark.asyncio
async def test_validateRecommendation_violatedFindingWithReject_contradictionCheckNotForced():
    provider = MockProvider(queue=[MockResponse(value=_PASSING_LLM_OUTPUT)])
    checks, _, _ = await validate_recommendation(
        "question",
        _findings(status="VIOLATED"),
        _RISK,
        _recommendation(rec="REJECT"),
        "<retrieved_evidence></retrieved_evidence>",
        required_domains=["security"],
        planned_domains=["security"],
        retrieved_chunk_ids={_CHUNK_1, _CHUNK_2},
        provider=provider,
        model="gemini-3.6-flash",
    )
    contradiction_check = next(c for c in checks if c.check == "CONTRADICTION")
    assert contradiction_check.passed


@pytest.mark.asyncio
async def test_validateRecommendation_evidenceCoverage_computedFromFindingsAndFactorsWithEvidence():
    provider = MockProvider(queue=[MockResponse(value=_PASSING_LLM_OUTPUT)])
    _, coverage, _ = await validate_recommendation(
        "question",
        _findings(),
        _RISK,
        _recommendation(),
        "<retrieved_evidence></retrieved_evidence>",
        required_domains=["security"],
        planned_domains=["security"],
        retrieved_chunk_ids={_CHUNK_1, _CHUNK_2},
        provider=provider,
        model="gemini-3.6-flash",
    )
    # 1 finding with evidence + 1 risk factor with evidence, both covered = 1.0
    assert coverage == 1.0


@pytest.mark.asyncio
async def test_validateRecommendation_findingWithoutEvidence_lowersCoverage():
    findings = [
        PolicyFinding(
            policy_name="Procurement Checklist",
            policy_reference="PC-1",
            status="UNKNOWN",
            explanation="No evidence retrieved.",
            evidence_ids=[],
            confidence=0.5,
        )
    ]
    provider = MockProvider(queue=[MockResponse(value=_PASSING_LLM_OUTPUT)])
    _, coverage, _ = await validate_recommendation(
        "question",
        findings,
        _RISK,
        _recommendation(),
        "<retrieved_evidence></retrieved_evidence>",
        required_domains=["security"],
        planned_domains=["security"],
        retrieved_chunk_ids={_CHUNK_1, _CHUNK_2},
        provider=provider,
        model="gemini-3.6-flash",
    )
    # 1 finding without evidence + 1 risk factor with evidence = 1/2
    assert coverage == 0.5
