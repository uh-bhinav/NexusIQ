"""Ties the retrieval pipeline stages together: cache -> embed -> vector
search (+ threshold, applied inside vector_search) -> rerank -> cache write
-> metrics. docs/AI/RAG.md's five-stage pipeline, minus context assembly
(that's a separate, optional step — see app/retrieval/context.py — not every
caller wants the formatted evidence block, e.g. a debug/inspection caller
just wants raw results).

Cache reads/writes are best-effort: `.claude/rules/architecture.md`'s
degradation table is explicit that Redis being down or slow must "serve
from Postgres, log cache-miss metric — never fail the request." Before this
was wrapped, a real live run's Redis hiccup (several concurrent per-domain
retrieval tasks each opening a fresh connection at once — see
retrieval/cache.py's get_cache() docstring) failed the entire decision run,
confirmed empirically.
"""

import asyncio
import logging
import time
import uuid

from opentelemetry import trace
from sqlalchemy.ext.asyncio import AsyncSession

from app.concurrency import INFERENCE_EXECUTOR
from app.config import Settings, get_settings
from app.embeddings.provider import get_embedding_provider
from app.models.retrieval import SearchFilters, SearchResponse
from app.observability.metrics import record_retrieval_duration
from app.retrieval.cache import RetrievalCache, get_cache
from app.retrieval.metrics import RetrievalMetrics, record_retrieval
from app.retrieval.reranker import get_reranker
from app.retrieval.search import vector_search

logger = logging.getLogger(__name__)

# Uses the globally-registered TracerProvider (app/observability/tracing.py's
# trace.set_tracer_provider call) rather than deps.tracer — this module has
# no GraphDeps to thread through (it's called from agents/retrieval.py's
# asyncio.gather'd per-domain tasks, each opening its own DB session), and
# get_tracer(__name__) still nests correctly under the enclosing
# "retrieval" agent-node span because OTel context propagates via
# contextvars, which asyncio.gather's child tasks inherit at creation.
_tracer = trace.get_tracer(__name__)


async def search(
    session: AsyncSession,
    workspace_id: uuid.UUID,
    query: str,
    filters: SearchFilters | None = None,
    settings: Settings | None = None,
    domain: str | None = None,
) -> SearchResponse:
    settings = settings or get_settings()
    filters = filters or SearchFilters()
    start = time.perf_counter()

    with _tracer.start_as_current_span("retrieval.search") as span:
        if domain:
            span.set_attribute("retrieval.domain", domain)
        span.set_attribute("retrieval.top_k", settings.retrieval_top_k)
        span.set_attribute("retrieval.rerank_enabled", settings.reranker_enabled)

        cache = get_cache(settings)
        cached: SearchResponse | None = None
        try:
            cached = await cache.get(workspace_id, query, filters)
        except Exception:
            logger.warning(
                "Retrieval cache read failed for workspace %s — serving from Postgres instead",
                workspace_id,
                exc_info=True,
            )
        if cached is not None:
            span.set_attribute("retrieval.cache_hit", True)
            span.set_attribute("retrieval.result_count", len(cached.results))
            record_retrieval(
                RetrievalMetrics(
                    workspace_id=workspace_id,
                    query=query,
                    cache_hit=True,
                    total_latency_ms=(time.perf_counter() - start) * 1000,
                    result_count=len(cached.results),
                )
            )
            return cached.model_copy(update={"cached": True})
        span.set_attribute("retrieval.cache_hit", False)

        return await _search_uncached(
            session, workspace_id, query, filters, settings, cache, start, span
        )


async def _search_uncached(
    session: AsyncSession,
    workspace_id: uuid.UUID,
    query: str,
    filters: SearchFilters,
    settings: Settings,
    cache: RetrievalCache,
    start: float,
    span: trace.Span,
) -> SearchResponse:
    provider = get_embedding_provider(settings)
    # embed() is a synchronous, CPU-bound call (sentence-transformers has no
    # async API) — run it off the event loop so it can't block other
    # coroutines. Previously latent risk (Phase 2 technical debt: observed
    # once as a Kafka consumer heartbeat stall); became a real, reproducible
    # failure in Phase 5 once multiple ContextPlan tasks call this
    # concurrently (asyncio.gather in agents/retrieval.py) — the blocked
    # loop starved the Redis cache client's own async I/O, timing it out.
    # Routed through INFERENCE_EXECUTOR (app/concurrency.py), not the default
    # executor: concurrent embed()/rerank() calls from multiple retrieval
    # tasks segfaulted the process (fork-based native parallelism colliding
    # under concurrent threads) before this was serialized to one worker.
    embed_start = time.perf_counter()
    [query_embedding] = await asyncio.get_running_loop().run_in_executor(
        INFERENCE_EXECUTOR, provider.embed, [query]
    )
    record_retrieval_duration("embedding", (time.perf_counter() - embed_start) * 1000)

    vector_start = time.perf_counter()
    results = await vector_search(session, settings, workspace_id, query_embedding, filters)
    vector_latency_ms = (time.perf_counter() - vector_start) * 1000

    rerank_latency_ms = None
    if settings.reranker_enabled and results:
        rerank_start = time.perf_counter()
        # Same reasoning as the embed() call above — CrossEncoder.predict()
        # is synchronous and CPU-bound.
        reranker = get_reranker(settings)
        results = await asyncio.get_running_loop().run_in_executor(
            INFERENCE_EXECUTOR, reranker.rerank, query, results, settings.rerank_top_n
        )
        rerank_latency_ms = (time.perf_counter() - rerank_start) * 1000

    total_latency_ms = (time.perf_counter() - start) * 1000
    response = SearchResponse(
        results=results, query=query, cached=False, latency_ms=total_latency_ms
    )

    try:
        await cache.set(workspace_id, query, filters, response)
    except Exception:
        logger.warning(
            "Retrieval cache write failed for workspace %s — result not cached, self-heals "
            "on the next successful write",
            workspace_id,
            exc_info=True,
        )

    similarities = [result.similarity_score for result in results]
    max_similarity = max(similarities) if similarities else None
    record_retrieval(
        RetrievalMetrics(
            workspace_id=workspace_id,
            query=query,
            cache_hit=False,
            total_latency_ms=total_latency_ms,
            vector_search_latency_ms=vector_latency_ms,
            rerank_latency_ms=rerank_latency_ms,
            result_count=len(results),
            min_similarity=min(similarities) if similarities else None,
            max_similarity=max_similarity,
            similarities=similarities,
        )
    )

    span.set_attribute("retrieval.result_count", len(results))
    if max_similarity is not None:
        span.set_attribute("retrieval.max_similarity", max_similarity)

    return response
