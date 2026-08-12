"""Gemini ModelProvider adapter (ADR-008). Only this module and
mock_provider.py may import `google.genai` — every other module reaches LLM
access through `ModelProvider` (.claude/rules/ai-service.md).

Structured output uses Gemini's native JSON-schema mode (`response_schema`),
which the SDK validates and parses server-side into `response.parsed`
whenever possible. The one-repair-retry contract
(.claude/rules/ai-service.md: "On schema-validation failure: one repair
retry with the validation error appended. Then fail the node.") exists for
the rare case `.parsed` doesn't come back as a valid instance of `schema` —
confirmed live that the happy path returns an already-validated instance
directly, so this is a genuine fallback path, not the common case.

Transient errors (timeout/rate-limit/5xx) get 2 retries with backoff,
independent of and outside the repair retry — see `_call_with_retries`.
"""

import asyncio
import time

from google import genai
from google.genai import errors, types
from pydantic import ValidationError

from app.llm.errors import (
    ModelInvalidSchema,
    ModelRateLimited,
    ModelRefused,
    ModelTimeout,
    ModelUnavailable,
)
from app.llm.pricing import estimate_cost_usd
from app.llm.provider import ModelResult, T

_ACCEPTABLE_FINISH_REASONS = {"STOP", "MAX_TOKENS"}

# 2 retries with backoff for transient errors (.claude/rules/ai-service.md:
# "LLM transient errors (timeout/5xx/rate limit): 2 retries with backoff").
# Independent of and outside the 1-repair schema-validation retry below —
# a transient failure and an invalid response are different failure modes
# with different fixes, and conflating their retry budgets would let one
# silently consume the other's allowance.
_TRANSIENT_BACKOFF_SECONDS = [1, 4]


class GeminiProvider:
    def __init__(self, api_key: str):
        self._client = genai.Client(api_key=api_key)

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
        start = time.monotonic()
        value, input_tokens, output_tokens, finish_reason, repaired = await self._call_with_retries(
            system, user, schema, model, temperature, timeout_s
        )

        latency_ms = int((time.monotonic() - start) * 1000)
        cost = estimate_cost_usd(model, input_tokens, output_tokens)
        return ModelResult(
            value=value,
            model=model,
            input_tokens=input_tokens,
            output_tokens=output_tokens,
            latency_ms=latency_ms,
            estimated_cost_usd=cost,
            finish_reason=finish_reason,
            repaired=repaired,
        )

    async def _call_with_retries(
        self,
        system: str,
        user: str,
        schema: type[T],
        model: str,
        temperature: float,
        timeout_s: int,
    ) -> tuple[T, int, int, str, bool]:
        last_transient_error: Exception | None = None
        for attempt in range(len(_TRANSIENT_BACKOFF_SECONDS) + 1):
            try:
                value, input_tokens, output_tokens, finish_reason = await self._call(
                    system, user, schema, model, temperature, timeout_s
                )
                return value, input_tokens, output_tokens, finish_reason, False
            except ModelInvalidSchema as e:
                repair_user = (
                    f"{user}\n\nYour previous response was invalid: {e}\n"
                    "Return ONLY a JSON object that matches the required schema."
                )
                value, input_tokens, output_tokens, finish_reason = await self._call(
                    system, repair_user, schema, model, temperature, timeout_s
                )
                return value, input_tokens, output_tokens, finish_reason, True
            except (ModelTimeout, ModelRateLimited, ModelUnavailable) as e:
                last_transient_error = e
                if attempt < len(_TRANSIENT_BACKOFF_SECONDS):
                    await asyncio.sleep(_TRANSIENT_BACKOFF_SECONDS[attempt])
                    continue
                raise
        # Unreachable: the loop above always returns or raises on its last
        # iteration. Satisfies mypy's exhaustiveness check without a bogus
        # fallback value.
        raise last_transient_error or ModelUnavailable("retries exhausted")

    async def _call(
        self,
        system: str,
        user: str,
        schema: type[T],
        model: str,
        temperature: float,
        timeout_s: int,
    ) -> tuple[T, int, int, str]:
        try:
            response = await asyncio.wait_for(
                self._client.aio.models.generate_content(
                    model=model,
                    contents=user,
                    config=types.GenerateContentConfig(
                        system_instruction=system,
                        response_mime_type="application/json",
                        response_schema=schema,
                        temperature=temperature,
                    ),
                ),
                timeout=timeout_s,
            )
        except TimeoutError as e:
            raise ModelTimeout(f"Gemini call exceeded {timeout_s}s") from e
        except errors.ClientError as e:
            if e.code == 429:
                raise ModelRateLimited(str(e)) from e
            raise ModelRefused(str(e)) from e
        except errors.ServerError as e:
            raise ModelUnavailable(str(e)) from e

        if not response.candidates:
            raise ModelRefused("No candidates returned (likely a safety block)")
        candidate = response.candidates[0]
        finish_reason = candidate.finish_reason.name if candidate.finish_reason else "UNKNOWN"
        if finish_reason not in _ACCEPTABLE_FINISH_REASONS:
            raise ModelRefused(f"Generation blocked or incomplete: finish_reason={finish_reason}")

        value = response.parsed
        if value is None or not isinstance(value, schema):
            try:
                value = schema.model_validate_json(response.text or "")
            except (ValidationError, ValueError) as e:
                raise ModelInvalidSchema(str(e)) from e

        usage = response.usage_metadata
        input_tokens = (usage.prompt_token_count if usage else 0) or 0
        # Thinking tokens are billed as output (llm/pricing.py docstring).
        output_tokens = ((usage.candidates_token_count if usage else 0) or 0) + (
            (usage.thoughts_token_count if usage else 0) or 0
        )
        return value, input_tokens, output_tokens, finish_reason
