"""Deterministic, offline ModelProvider (.claude/rules/ai-service.md,
docs/AI/MODEL_STRATEGY.md). Exists so tests, and the evaluation harness in
CI, run reproducibly and for free — never presented as working AI behaviour.

Two ways to use it:

1. **Via the factory** (`llm/factory.py`, `LLM_PROVIDER=mock`): resolves a
   canned response by looking up `fixtures_dir/{schema.__name__}.json`. This
   is what makes "switching LLM_PROVIDER=mock works with zero code changes"
   true — the calling code (e.g. `agents/intent.py`) only ever names the
   schema it wants; it never knows or cares which provider is behind it.
2. **Constructed directly with an explicit `queue`** (bypassing the
   factory): for tests that need to simulate a specific sequence, e.g. an
   invalid response followed by a valid repair, or a specific `ModelError`.
   This is the normal, expected way unit tests exercise the repair-retry
   contract and error handling without touching the filesystem.
"""

from dataclasses import dataclass
from pathlib import Path
from typing import cast

from pydantic import BaseModel, ValidationError

from app.llm.errors import ModelError, ModelInvalidSchema, ModelUnavailable
from app.llm.provider import ModelResult, T


@dataclass
class MockResponse:
    """One entry in an explicit `MockProvider(queue=[...])`."""

    value: BaseModel | None = None
    raw_invalid: str | None = None
    error: ModelError | None = None


class MockProvider:
    def __init__(
        self,
        fixtures_dir: Path | None = None,
        *,
        queue: list[MockResponse] | None = None,
    ):
        self._fixtures_dir = fixtures_dir
        self._queue = queue
        self.calls: list[dict[str, str]] = []

    async def generate_structured(
        self,
        *,
        system: str,
        user: str,
        schema: type[T],
        model: str,
        temperature: float = 0.1,
        timeout_s: int = 60,
    ) -> ModelResult[T]:
        if self._queue is not None:
            return self._from_queue(system, user, schema, model)
        return self._from_fixture(system, user, schema, model)

    def _from_fixture(
        self, system: str, user: str, schema: type[T], model: str
    ) -> ModelResult[T]:
        self.calls.append({"system": system, "user": user, "model": model})
        if self._fixtures_dir is None:
            raise ModelUnavailable("MockProvider has neither a fixtures_dir nor an explicit queue")
        fixture_path = self._fixtures_dir / f"{schema.__name__}.json"
        if not fixture_path.exists():
            raise ModelUnavailable(
                f"No mock fixture at {fixture_path} for schema {schema.__name__}"
            )
        value = schema.model_validate_json(fixture_path.read_text())
        return ModelResult(
            value=value,
            model=f"mock-{model}",
            input_tokens=0,
            output_tokens=0,
            latency_ms=0,
            estimated_cost_usd=0.0,
            finish_reason="stop",
            repaired=False,
        )

    def _from_queue(
        self, system: str, user: str, schema: type[T], model: str
    ) -> ModelResult[T]:
        queue = self._queue
        assert queue is not None
        repaired = False
        last_error: Exception | None = None
        # 1 initial attempt + 1 repair — matches the real repair contract
        # (.claude/rules/ai-service.md), never more.
        for attempt in range(2):
            self.calls.append(
                {"system": system, "user": user, "model": model, "attempt": str(attempt)}
            )
            if not queue:
                raise ModelUnavailable("MockProvider response queue exhausted")
            item = queue.pop(0)
            if item.error is not None:
                raise item.error
            try:
                value = (
                    item.value
                    if item.value is not None
                    else schema.model_validate_json(item.raw_invalid or "")
                )
            except (ValidationError, ValueError) as e:
                last_error = e
                repaired = True
                continue
            return ModelResult(
                value=cast(T, value),
                model=f"mock-{model}",
                input_tokens=10,
                output_tokens=10,
                latency_ms=1,
                estimated_cost_usd=0.0,
                finish_reason="stop",
                repaired=repaired,
            )
        raise ModelInvalidSchema(str(last_error))
