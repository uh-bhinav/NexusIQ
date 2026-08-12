import pytest

from app.agents.context_planner import plan_context
from app.llm.mock_provider import MockProvider, MockResponse
from app.models.agents import ContextPlan, IntentAnalysis, RetrievalTask

_INTENT = IntentAnalysis(
    decision_type="vendor_approval",
    entities=["Vendor Alpha"],
    jurisdiction="EU",
    environment="production",
    required_domains=["security", "data_residency"],
    missing_information=[],
    confidence=0.9,
)

_PLAN = ContextPlan(
    tasks=[
        RetrievalTask(
            domain="security",
            query="EU data residency requirements",
            document_types=["SECURITY_POLICY"],
            rationale="required domain",
            priority="CRITICAL",
        )
    ],
    historical_lookup=False,
)


@pytest.mark.asyncio
async def test_planContext_returnsTasksMatchingRequiredDomains():
    provider = MockProvider(queue=[MockResponse(value=_PLAN)])
    result = await plan_context(
        "Should Vendor Alpha be approved for EU production?",
        _INTENT,
        provider=provider,
        model="gemini-2.5-flash",
    )
    assert len(result.value.tasks) == 1
    assert result.value.tasks[0].domain == "security"


@pytest.mark.asyncio
async def test_planContext_passesQuestionAndIntentToProvider():
    provider = MockProvider(queue=[MockResponse(value=_PLAN)])
    await plan_context(
        "Should Vendor Alpha be approved for EU production?",
        _INTENT,
        provider=provider,
        model="gemini-2.5-flash",
    )
    assert "Vendor Alpha" in provider.calls[0]["user"]
    assert "vendor_approval" in provider.calls[0]["user"]
