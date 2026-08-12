"""Retrieval metrics (docs/AI/RAG.md "Metrics"): structured log lines for now.
Full aggregation/dashboards (Prometheus + Grafana) are Phase 8 — this captures
the right *values* per request so wiring a real backend later is a sink
change, not a redesign.
"""

import logging
import uuid

from pydantic import BaseModel

from app.observability.metrics import (
    record_retrieval_duration,
    record_retrieval_result_count,
    record_retrieval_similarity,
)

logger = logging.getLogger("nexusiq.retrieval.metrics")


class RetrievalMetrics(BaseModel):
    workspace_id: uuid.UUID
    query: str
    cache_hit: bool
    total_latency_ms: float
    vector_search_latency_ms: float | None = None
    rerank_latency_ms: float | None = None
    result_count: int
    min_similarity: float | None = None
    max_similarity: float | None = None
    # Full per-result scores, for the retrieval_similarity histogram — min/max
    # alone can't reconstruct a distribution. None on the cache-hit path
    # (cached results don't re-run the scoring that produced these).
    similarities: list[float] | None = None


def record_retrieval(metrics: RetrievalMetrics) -> None:
    logger.info(
        "retrieval workspace=%s cache_hit=%s total_ms=%.1f vector_ms=%s rerank_ms=%s "
        "result_count=%d empty=%s similarity_range=[%s,%s]",
        metrics.workspace_id,
        metrics.cache_hit,
        metrics.total_latency_ms,
        f"{metrics.vector_search_latency_ms:.1f}" if metrics.vector_search_latency_ms else "n/a",
        f"{metrics.rerank_latency_ms:.1f}" if metrics.rerank_latency_ms else "n/a",
        metrics.result_count,
        metrics.result_count == 0,
        metrics.min_similarity,
        metrics.max_similarity,
    )

    record_retrieval_duration("total", metrics.total_latency_ms)
    if metrics.vector_search_latency_ms is not None:
        record_retrieval_duration("vector_search", metrics.vector_search_latency_ms)
    if metrics.rerank_latency_ms is not None:
        record_retrieval_duration("rerank", metrics.rerank_latency_ms)
    record_retrieval_result_count(metrics.result_count)
    for score in metrics.similarities or []:
        record_retrieval_similarity(score)
