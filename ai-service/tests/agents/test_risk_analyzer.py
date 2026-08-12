import uuid

import pytest

from app.agents.risk_analyzer import analyze_risk
from app.llm.mock_provider import MockProvider, MockResponse
from app.models.agents import RiskAssessment, RiskFactor
from app.models.retrieval import ContextAssembly

_CHUNK_1 = uuid.uuid4()

_CONTEXT = ContextAssembly(
    evidence_block="<retrieved_evidence>\n[E1] ...\n</retrieved_evidence>\n",
    included_chunk_ids=[_CHUNK_1],
    dropped_count=0,
)

_ASSESSMENT = RiskAssessment(
    risk_level="MEDIUM",
    factors=[
        RiskFactor(
            category="DATA",
            description="Data residency evidence is stale.",
            severity="MEDIUM",
            likelihood="POSSIBLE",
            evidence_ids=["E1"],
        )
    ],
    missing_information=["Current encryption audit"],
    confidence=0.7,
)


@pytest.mark.asyncio
async def test_analyzeRisk_resolvesLabelsToRealChunkIds():
    provider = MockProvider(queue=[MockResponse(value=_ASSESSMENT)])
    risk, _ = await analyze_risk("question", _CONTEXT, provider=provider, model="gemini-2.5-flash")
    assert risk.factors[0].evidence_ids == [str(_CHUNK_1)]


@pytest.mark.asyncio
async def test_analyzeRisk_missingInformationPreserved():
    provider = MockProvider(queue=[MockResponse(value=_ASSESSMENT)])
    risk, _ = await analyze_risk("question", _CONTEXT, provider=provider, model="gemini-2.5-flash")
    assert risk.missing_information == ["Current encryption audit"]
    assert risk.risk_level == "MEDIUM"
