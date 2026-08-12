"""Local, zero-cost embeddings (ADR-009). Vectors are normalized so pgvector's
cosine operator (<=>) behaves correctly (.claude/rules/database.md).
"""

import os

# Must be set before sentence_transformers (and transitively, tokenizers) is
# imported — tokenizers reads this once at import time. Its Rust-side thread
# pool is not fork-safe; left enabled it was one contributor to a segfault
# under concurrent embed() calls (see app/concurrency.py).
os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")

from sentence_transformers import SentenceTransformer  # noqa: E402

from app.embeddings.provider import EmbeddingProvider  # noqa: E402


class LocalEmbeddingProvider(EmbeddingProvider):
    def __init__(self, model_name: str, batch_size: int):
        self._model_name = model_name
        self._batch_size = batch_size
        self._model = SentenceTransformer(model_name)

    def embed(self, texts: list[str]) -> list[list[float]]:
        if not texts:
            return []
        embeddings = self._model.encode(
            texts,
            batch_size=self._batch_size,
            normalize_embeddings=True,
            convert_to_numpy=True,
        )
        return [vector.tolist() for vector in embeddings]

    @property
    def model_name(self) -> str:
        return self._model_name

    @property
    def dimensions(self) -> int:
        dim = self._model.get_embedding_dimension()
        assert dim is not None
        return int(dim)
