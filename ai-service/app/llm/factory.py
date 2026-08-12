"""DI seam: the only place a caller decides which ModelProvider it gets.
Every agent takes a `ModelProvider` as a parameter — nothing calls this
factory except FastAPI routers and, from Phase 5 on, the graph builder
(.claude/rules/ai-service.md: "No LangGraph node imports a vendor SDK.")."""

from pathlib import Path

from app.config import Settings
from app.llm.gemini_provider import GeminiProvider
from app.llm.mock_provider import MockProvider
from app.llm.provider import ModelProvider

_MOCK_FIXTURES_DIR = Path(__file__).resolve().parents[2] / "tests" / "fixtures" / "llm"


def get_model_provider(settings: Settings) -> ModelProvider:
    if settings.llm_provider == "gemini":
        if not settings.llm_api_key:
            raise RuntimeError("LLM_API_KEY is required when LLM_PROVIDER=gemini")
        return GeminiProvider(settings.llm_api_key)
    if settings.llm_provider == "mock":
        if settings.nexusiq_env not in ("local", "ci"):
            raise RuntimeError(
                "the mock LLM provider is test-only and unreachable outside local/ci "
                f"(NEXUSIQ_ENV={settings.nexusiq_env!r})"
            )
        return MockProvider(_MOCK_FIXTURES_DIR)
    raise NotImplementedError(
        f"LLM_PROVIDER={settings.llm_provider!r} is not implemented (only gemini, mock)"
    )
