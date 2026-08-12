import pytest

from app.config import get_settings
from app.llm.factory import get_model_provider
from app.llm.gemini_provider import GeminiProvider
from app.llm.mock_provider import MockProvider


def test_getModelProvider_gemini_returnsGeminiProvider():
    settings = get_settings().model_copy(
        update={"llm_provider": "gemini", "llm_api_key": "fake-key"}
    )
    provider = get_model_provider(settings)
    assert isinstance(provider, GeminiProvider)


def test_getModelProvider_gemini_missingApiKey_raises():
    settings = get_settings().model_copy(update={"llm_provider": "gemini", "llm_api_key": ""})
    with pytest.raises(RuntimeError, match="LLM_API_KEY"):
        get_model_provider(settings)


def test_getModelProvider_mock_returnsMockProvider():
    settings = get_settings().model_copy(update={"llm_provider": "mock", "nexusiq_env": "local"})
    provider = get_model_provider(settings)
    assert isinstance(provider, MockProvider)


def test_getModelProvider_mock_outsideLocalOrCi_raises():
    settings = get_settings().model_copy(
        update={"llm_provider": "mock", "nexusiq_env": "production"}
    )
    with pytest.raises(RuntimeError, match="test-only"):
        get_model_provider(settings)


def test_getModelProvider_unknownProvider_raisesNotImplemented():
    settings = get_settings().model_copy(update={"llm_provider": "anthropic"})
    with pytest.raises(NotImplementedError):
        get_model_provider(settings)


@pytest.mark.asyncio
async def test_providerSwap_sameCallSite_worksViaFactory_zeroCodeChanges():
    """Acceptance criterion 5: switching LLM_PROVIDER=mock works with zero
    code changes at the call site — analyze_intent() doesn't know or care
    which concrete provider get_model_provider() handed it."""
    from app.agents.intent import analyze_intent

    settings = get_settings().model_copy(update={"llm_provider": "mock", "nexusiq_env": "local"})
    provider = get_model_provider(settings)

    result = await analyze_intent(
        "Should Vendor Alpha be approved for EU production?",
        provider=provider,
        model=settings.llm_model,
    )

    assert result.value.decision_type == "vendor_approval"
    assert result.model == f"mock-{settings.llm_model}"
