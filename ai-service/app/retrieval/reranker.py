"""Stage 3 of the retrieval pipeline (docs/AI/RAG.md): cross-encoder reranking,
toggleable via RERANKER_ENABLED. Local, zero-cost (ADR-009's local-model
convention extended to the reranker).
"""

import os
from functools import lru_cache

# See app/embeddings/local.py's identical guard — must be set before
# sentence_transformers is imported, whichever module imports it first.
os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")

from sentence_transformers import CrossEncoder  # noqa: E402

from app.config import Settings, get_settings  # noqa: E402
from app.models.retrieval import RetrievalResult


class Reranker:
    def __init__(self, model_name: str):
        self._model = CrossEncoder(model_name)

    def rerank(
        self, query: str, results: list[RetrievalResult], top_n: int
    ) -> list[RetrievalResult]:
        """Returns at most `top_n` results, each with `rerank_score` set,
        ordered by that score descending."""
        if not results:
            return []

        pairs = [(query, result.content) for result in results]
        scores = self._model.predict(pairs)

        scored = sorted(zip(results, scores, strict=True), key=lambda pair: pair[1], reverse=True)
        return [
            result.model_copy(update={"rerank_score": float(score)})
            for result, score in scored[:top_n]
        ]


@lru_cache
def _cached_reranker(model_name: str) -> Reranker:
    return Reranker(model_name)


def get_reranker(settings: Settings | None = None) -> Reranker:
    """Loading the cross-encoder is slow — cached as a singleton per model name."""
    settings = settings or get_settings()
    return _cached_reranker(settings.reranker_model)
