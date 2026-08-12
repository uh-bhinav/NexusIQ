"""GeminiProvider's own transient-error retry loop (.claude/rules/ai-service.md:
"LLM transient errors (timeout/5xx/rate limit): 2 retries with backoff").
This is genuinely different from mock_provider.py's queue-based repair test
(tests/llm/test_mock_provider.py) — that exercises the *schema-repair*
retry contract via the fake MockProvider; this exercises GeminiProvider's
*own* `_call_with_retries` transient-failure loop, which MockProvider does
not implement at all. Never hits the real Gemini API: `google.genai.Client`
is never constructed with real credentials, and the one HTTP-shaped call
(`self._client.aio.models.generate_content`) is replaced directly.
"""

import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from google.genai import errors
from pydantic import BaseModel

from app.llm.errors import ModelRateLimited, ModelTimeout, ModelUnavailable
from app.llm.gemini_provider import GeminiProvider


class _Schema(BaseModel):
    answer: str


def _fake_response(value: _Schema, input_tokens: int = 10, output_tokens: int = 5):
    return SimpleNamespace(
        candidates=[SimpleNamespace(finish_reason=SimpleNamespace(name="STOP"))],
        parsed=value,
        text=value.model_dump_json(),
        usage_metadata=SimpleNamespace(
            prompt_token_count=input_tokens,
            candidates_token_count=output_tokens,
            thoughts_token_count=0,
        ),
    )


def _provider_with_mocked_client() -> tuple[GeminiProvider, AsyncMock]:
    provider = GeminiProvider.__new__(GeminiProvider)  # skip __init__: no real genai.Client
    generate_content = AsyncMock()
    provider._client = SimpleNamespace(
        aio=SimpleNamespace(models=SimpleNamespace(generate_content=generate_content))
    )
    return provider, generate_content


@pytest.mark.asyncio
async def test_generateStructured_transientTimeoutThenSuccess_retriesAndReturns(monkeypatch):
    sleeps: list[float] = []
    monkeypatch.setattr(asyncio, "sleep", AsyncMock(side_effect=lambda s: sleeps.append(s)))

    provider, generate_content = _provider_with_mocked_client()
    success = _fake_response(_Schema(answer="ok"))
    generate_content.side_effect = [TimeoutError(), success]

    result = await provider.generate_structured(
        system="sys", user="q", schema=_Schema, model="gemini-2.5-flash"
    )

    assert result.value.answer == "ok"
    assert result.repaired is False
    assert generate_content.await_count == 2
    # Exactly the first backoff delay (1s) — a single transient failure
    # never burns the second retry's delay too.
    assert sleeps == [1]


@pytest.mark.asyncio
async def test_generateStructured_rateLimitedTwiceThenSuccess_usesBothBackoffDelays(monkeypatch):
    sleeps: list[float] = []
    monkeypatch.setattr(asyncio, "sleep", AsyncMock(side_effect=lambda s: sleeps.append(s)))

    provider, generate_content = _provider_with_mocked_client()
    rate_limited = errors.ClientError(code=429, response_json={"error": {"message": "quota"}})
    success = _fake_response(_Schema(answer="ok"))
    generate_content.side_effect = [rate_limited, rate_limited, success]

    result = await provider.generate_structured(
        system="sys", user="q", schema=_Schema, model="gemini-2.5-flash"
    )

    assert result.value.answer == "ok"
    assert generate_content.await_count == 3
    assert sleeps == [1, 4]


@pytest.mark.asyncio
async def test_generateStructured_persistentServerError_isBoundedThenRaises(monkeypatch):
    # The retry budget is exactly 2 (.claude/rules/ai-service.md) — a
    # persistently-failing model must not retry forever. 1 initial attempt +
    # 2 retries = 3 total calls, then the transient error propagates.
    sleeps: list[float] = []
    monkeypatch.setattr(asyncio, "sleep", AsyncMock(side_effect=lambda s: sleeps.append(s)))

    provider, generate_content = _provider_with_mocked_client()
    server_error = errors.ServerError(code=503, response_json={"error": {"message": "down"}})
    generate_content.side_effect = [server_error, server_error, server_error]

    with pytest.raises(ModelUnavailable):
        await provider.generate_structured(
            system="sys", user="q", schema=_Schema, model="gemini-2.5-flash"
        )

    assert generate_content.await_count == 3
    assert sleeps == [1, 4]


@pytest.mark.asyncio
async def test_generateStructured_rateLimitedRaisesModelRateLimited_notGenericError(monkeypatch):
    monkeypatch.setattr(asyncio, "sleep", AsyncMock())
    provider, generate_content = _provider_with_mocked_client()
    generate_content.side_effect = [
        errors.ClientError(code=429, response_json={"error": {"message": "quota"}})
    ] * 3

    with pytest.raises(ModelRateLimited):
        await provider.generate_structured(
            system="sys", user="q", schema=_Schema, model="gemini-2.5-flash"
        )


@pytest.mark.asyncio
async def test_generateStructured_realTimeout_raisesModelTimeout_notGenericError(monkeypatch):
    monkeypatch.setattr(asyncio, "sleep", AsyncMock())
    provider, generate_content = _provider_with_mocked_client()
    generate_content.side_effect = [TimeoutError()] * 3

    with pytest.raises(ModelTimeout):
        await provider.generate_structured(
            system="sys", user="q", schema=_Schema, model="gemini-2.5-flash"
        )
