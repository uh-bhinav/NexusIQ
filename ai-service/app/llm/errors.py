"""Normalized error types every ModelProvider adapter must raise
(docs/AI/MODEL_STRATEGY.md) — callers branch on these, never on a vendor
SDK's exception hierarchy."""


class ModelError(Exception):
    """Base for all normalized LLM errors."""


class ModelTimeout(ModelError):
    pass


class ModelRateLimited(ModelError):
    pass


class ModelInvalidSchema(ModelError):
    """Raised after the one allowed repair retry still fails validation."""


class ModelRefused(ModelError):
    """Provider declined to answer (safety block, no candidates, etc.)."""


class ModelUnavailable(ModelError):
    """Transient or permanent provider-side failure (5xx, network, etc.)."""
