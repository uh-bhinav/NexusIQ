from pathlib import Path

import pytest
from pydantic import BaseModel

from app.llm.errors import ModelInvalidSchema, ModelTimeout, ModelUnavailable
from app.llm.mock_provider import MockProvider, MockResponse
from app.models.agents import IntentAnalysis

FIXTURES_DIR = Path(__file__).resolve().parents[1] / "fixtures" / "llm"


class _Toy(BaseModel):
    greeting: str
    count: int


@pytest.mark.asyncio
async def test_fixtureLookup_matchingFile_returnsValidatedValue():
    provider = MockProvider(FIXTURES_DIR)
    result = await provider.generate_structured(
        system="sys", user="user", schema=IntentAnalysis, model="gemini-2.5-flash"
    )
    assert result.value.decision_type == "vendor_approval"
    assert result.model == "mock-gemini-2.5-flash"
    assert result.estimated_cost_usd == 0.0
    assert result.repaired is False


@pytest.mark.asyncio
async def test_fixtureLookup_noMatchingFile_raisesModelUnavailable():
    provider = MockProvider(FIXTURES_DIR)
    with pytest.raises(ModelUnavailable):
        await provider.generate_structured(system="s", user="u", schema=_Toy, model="m")


@pytest.mark.asyncio
async def test_queue_singleValidResponse_returnsItUnrepaired():
    provider = MockProvider(queue=[MockResponse(value=_Toy(greeting="hi", count=1))])
    result = await provider.generate_structured(system="s", user="u", schema=_Toy, model="m")
    assert result.value == _Toy(greeting="hi", count=1)
    assert result.repaired is False


@pytest.mark.asyncio
async def test_queue_invalidThenValid_repairsExactlyOnce():
    provider = MockProvider(
        queue=[
            MockResponse(raw_invalid='{"greeting": "hi"}'),  # missing required "count"
            MockResponse(value=_Toy(greeting="hi", count=2)),
        ]
    )
    result = await provider.generate_structured(system="s", user="u", schema=_Toy, model="m")
    assert result.value == _Toy(greeting="hi", count=2)
    assert result.repaired is True
    assert len(provider.calls) == 2


@pytest.mark.asyncio
async def test_queue_invalidTwice_failsAfterExactlyOneRepairAttempt():
    provider = MockProvider(
        queue=[
            MockResponse(raw_invalid="{}"),
            MockResponse(raw_invalid="{}"),
            MockResponse(value=_Toy(greeting="unreachable", count=99)),
        ]
    )
    with pytest.raises(ModelInvalidSchema):
        await provider.generate_structured(system="s", user="u", schema=_Toy, model="m")
    # Exactly 2 attempts consumed (1 initial + 1 repair) — the third queue
    # entry must be untouched, proving the bound is enforced, not accidental.
    assert len(provider.calls) == 2
    assert len(provider._queue) == 1  # noqa: SLF001 - test-only introspection


@pytest.mark.asyncio
async def test_queue_injectedError_propagatesAsIs():
    provider = MockProvider(queue=[MockResponse(error=ModelTimeout("simulated timeout"))])
    with pytest.raises(ModelTimeout):
        await provider.generate_structured(system="s", user="u", schema=_Toy, model="m")


@pytest.mark.asyncio
async def test_queue_exhausted_raisesModelUnavailable():
    provider = MockProvider(queue=[])
    with pytest.raises(ModelUnavailable):
        await provider.generate_structured(system="s", user="u", schema=_Toy, model="m")
