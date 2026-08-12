"""LLM pricing table (docs/AI/MODEL_STRATEGY.md: "a comment stating the date
it was last checked" — prices drift, a stale table produces confidently
wrong cost numbers).

Verified against https://ai.google.dev/gemini-api/docs/pricing on 2026-08-11
(standard/paid tier, USD per 1,000,000 tokens). Output prices include
"thinking" tokens — Gemini's reasoning tokens are billed as output, so
callers must fold `thoughts_token_count` into `output_tokens` before calling
`estimate_cost_usd` (see llm/gemini_provider.py).

gemini-2.5-pro has tiered pricing by prompt length; only models actually
selectable via LLM_MODEL/LLM_MODEL_HEAVY are listed here — an unlisted model
name returns $0.00 rather than a fabricated estimate (see estimate_cost_usd).
"""

from dataclasses import dataclass


@dataclass(frozen=True)
class _FlatPrice:
    input_per_million: float
    output_per_million: float


@dataclass(frozen=True)
class _TieredPrice:
    threshold_tokens: int
    input_per_million_low: float
    output_per_million_low: float
    input_per_million_high: float
    output_per_million_high: float


_PRICING: dict[str, _FlatPrice | _TieredPrice] = {
    "gemini-2.5-flash": _FlatPrice(input_per_million=0.30, output_per_million=2.50),
    "gemini-2.5-pro": _TieredPrice(
        threshold_tokens=200_000,
        input_per_million_low=1.25,
        output_per_million_low=10.00,
        input_per_million_high=2.50,
        output_per_million_high=15.00,
    ),
    # LLM_MODEL_HEAVY as of 2026-08-11 — see config.py's comment on
    # llm_model_heavy for why gemini-2.5-pro was replaced.
    "gemini-3.6-flash": _FlatPrice(input_per_million=1.50, output_per_million=7.50),
}


def estimate_cost_usd(model: str, input_tokens: int, output_tokens: int) -> float:
    """Never fabricates a price for an unlisted model (e.g. `mock-*`) —
    returns 0.0, which is both the honest cost of the mock provider and a
    visible signal that a newly-configured real model needs a pricing entry
    added here before its cost figures can be trusted."""
    price = _PRICING.get(model)
    if price is None:
        return 0.0
    if isinstance(price, _FlatPrice):
        input_rate, output_rate = price.input_per_million, price.output_per_million
    else:
        high = input_tokens > price.threshold_tokens
        input_rate = price.input_per_million_high if high else price.input_per_million_low
        output_rate = price.output_per_million_high if high else price.output_per_million_low
    return (input_tokens / 1_000_000) * input_rate + (output_tokens / 1_000_000) * output_rate
