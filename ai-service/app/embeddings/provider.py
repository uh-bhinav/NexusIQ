from abc import ABC, abstractmethod
from functools import lru_cache

from app.config import Settings, get_settings


class EmbeddingProvider(ABC):
    @abstractmethod
    def embed(self, texts: list[str]) -> list[list[float]]:
        """Batched. Returns one normalized vector per input text, same order."""

    @property
    @abstractmethod
    def model_name(self) -> str: ...

    @property
    @abstractmethod
    def dimensions(self) -> int: ...


@lru_cache
def _cached_provider(provider_name: str, model_name: str, batch_size: int) -> EmbeddingProvider:
    if provider_name != "local":
        raise NotImplementedError(f"Embedding provider '{provider_name}' is not implemented")
    from app.embeddings.local import LocalEmbeddingProvider

    return LocalEmbeddingProvider(model_name, batch_size)


def get_embedding_provider(settings: Settings | None = None) -> EmbeddingProvider:
    """Loading the model is slow (~seconds) and memory-heavy — cached as a
    singleton per (provider, model, batch_size) so it only happens once."""
    settings = settings or get_settings()
    return _cached_provider(
        settings.embedding_provider, settings.embedding_model, settings.embedding_batch_size
    )
