import uuid

import pytest

from app.agents.decision import synthesize_decision
from app.llm.mock_provider import MockProvider, MockResponse
from app.models.agents import PolicyFinding, Recommendation, RiskAssessment, RiskFactor

_CHUNK_1 = str(uuid.uuid4())
_CHUNK_2 = str(uuid.uuid4())

_FINDINGS = [
    PolicyFinding(
        policy_name="Data Residency Policy",
        policy_reference="SP-102 §1",
        status="SATISFIED",
        explanation="...",
        evidence_ids=[_CHUNK_1],
        confidence=0.9,
    )
]
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


@pytest.mark.asyncio
async def test_synthesizeDecision_keepsVerbatimKnownIds():
    recommendation = Recommendation(
        recommendation="APPROVE",
        reasoning_summary="...",
        confidence=0.8,
        key_evidence_ids=[_CHUNK_1, _CHUNK_2],
        required_actions=[],
        conditions=[],
        unresolved_questions=[],
    )
    provider = MockProvider(queue=[MockResponse(value=recommendation)])
    resolved, _ = await synthesize_decision(
        "question", _FINDINGS, _RISK, provider=provider, model="gemini-2.5-pro"
    )
    assert set(resolved.key_evidence_ids) == {_CHUNK_1, _CHUNK_2}


@pytest.mark.asyncio
async def test_synthesizeDecision_dropsIdsNotPresentInFindingsOrRisk():
    hallucinated_id = str(uuid.uuid4())
    recommendation = Recommendation(
        recommendation="APPROVE",
        reasoning_summary="...",
        confidence=0.8,
        key_evidence_ids=[_CHUNK_1, hallucinated_id],
        required_actions=[],
        conditions=[],
        unresolved_questions=[],
    )
    provider = MockProvider(queue=[MockResponse(value=recommendation)])
    resolved, _ = await synthesize_decision(
        "question", _FINDINGS, _RISK, provider=provider, model="gemini-2.5-pro"
    )
    assert resolved.key_evidence_ids == [_CHUNK_1]


@pytest.mark.asyncio
async def test_synthesizeDecision_violatedFinding_recommendsRejectOrConditional():
    violated_finding = PolicyFinding(
        policy_name="Encryption Standard",
        policy_reference="SP-102 §2",
        status="VIOLATED",
        explanation="Keys not rotated.",
        evidence_ids=[_CHUNK_1],
        confidence=0.9,
    )
    recommendation = Recommendation(
        recommendation="REJECT",
        reasoning_summary="Encryption policy is violated.",
        confidence=0.85,
        key_evidence_ids=[_CHUNK_1],
        required_actions=["Rotate encryption keys"],
        conditions=[],
        unresolved_questions=[],
    )
    provider = MockProvider(queue=[MockResponse(value=recommendation)])
    resolved, _ = await synthesize_decision(
        "question", [violated_finding], _RISK, provider=provider, model="gemini-2.5-pro"
    )
    assert resolved.recommendation in ("REJECT", "CONDITIONAL_APPROVAL")
