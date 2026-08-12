"""The one seam every LLM call goes through (.claude/rules/ai-service.md:
"No LangGraph node imports a vendor SDK. Ever."). Adapters: gemini (real),
mock (test-only, deterministic). See docs/AI/MODEL_STRATEGY.md."""

from typing import Generic, Protocol, TypeVar

from pydantic import BaseModel

T = TypeVar("T", bound=BaseModel)


class ModelResult(BaseModel, Generic[T]):
    value: T
    model: str
    input_tokens: int
    output_tokens: int
    latency_ms: int
    estimated_cost_usd: float
    finish_reason: str
    repaired: bool


class ModelProvider(Protocol):
    async def generate_structured(
        self,
        *,
        system: str,
        user: str,
        schema: type[T],
        model: str,
        temperature: float = 0.1,
        timeout_s: int = 60,
    ) -> ModelResult[T]: ...
