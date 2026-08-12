import uuid

import pytest

from app.agents.policy_analyst import analyze_policy
from app.llm.mock_provider import MockProvider, MockResponse
from app.models.agents import PolicyAnalysisOutput, PolicyFinding
from app.models.retrieval import ContextAssembly

_CHUNK_1 = uuid.uuid4()
_CHUNK_2 = uuid.uuid4()

_CONTEXT = ContextAssembly(
    evidence_block="<retrieved_evidence>\n[E1] ...\n\n[E2] ...\n</retrieved_evidence>\n",
    included_chunk_ids=[_CHUNK_1, _CHUNK_2],
    dropped_count=0,
)

_OUTPUT = PolicyAnalysisOutput(
    findings=[
        PolicyFinding(
            policy_name="Data Residency Policy",
            policy_reference="SP-102 §1",
            status="SATISFIED",
            explanation="EU/EEA storage requirement is documented.",
            evidence_ids=["E1"],
            confidence=0.9,
        ),
        PolicyFinding(
            policy_name="Encryption Standard",
            policy_reference="SP-102 §2",
            status="UNKNOWN",
            explanation="No evidence addresses encryption key rotation.",
            evidence_ids=[],
            confidence=0.5,
        ),
    ]
)


@pytest.mark.asyncio
async def test_analyzePolicy_resolvesLabelsToRealChunkIds():
    provider = MockProvider(queue=[MockResponse(value=_OUTPUT)])
    findings, _ = await analyze_policy(
        "question", _CONTEXT, provider=provider, model="gemini-2.5-flash"
    )
    assert findings[0].evidence_ids == [str(_CHUNK_1)]


@pytest.mark.asyncio
async def test_analyzePolicy_unknownStatus_allowsEmptyEvidence():
    provider = MockProvider(queue=[MockResponse(value=_OUTPUT)])
    findings, _ = await analyze_policy(
        "question", _CONTEXT, provider=provider, model="gemini-2.5-flash"
    )
    assert findings[1].status == "UNKNOWN"
    assert findings[1].evidence_ids == []


@pytest.mark.asyncio
async def test_analyzePolicy_hallucinatedLabel_dropsSilentlyNotRaises():
    hallucinated = PolicyAnalysisOutput(
        findings=[
            PolicyFinding(
                policy_name="X",
                policy_reference="X §1",
                status="VIOLATED",
                explanation="...",
                evidence_ids=["E1", "E99"],  # E99 does not exist in the context
                confidence=0.7,
            )
        ]
    )
    provider = MockProvider(queue=[MockResponse(value=hallucinated)])
    findings, _ = await analyze_policy(
        "question", _CONTEXT, provider=provider, model="gemini-2.5-flash"
    )
    assert findings[0].evidence_ids == [str(_CHUNK_1)]
