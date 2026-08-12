"""Retrieval Agent (docs/AI/AGENTS.md #3). No LLM call — deterministic code
executing the ContextPlan produced by context_planner. Tasks run
concurrently; results are deduplicated by chunk_id keeping the
highest-scoring occurrence and tagged with their originating domain.

Deliberately does not take a session parameter: with N concurrent tasks
(asyncio.gather below) all needing DB access, a single shared AsyncSession
used from multiple coroutines at once is unsafe — SQLAlchemy's async
extension raises IllegalStateChangeError the moment two of them touch it
concurrently, confirmed empirically in a live run with a multi-task
ContextPlan. Each task opens its own short-lived session instead, the same
per-call pattern already used elsewhere for exactly this class of bug
(app/db/session.py's NullPool docstring, app/retrieval/cache.py's
get_cache())."""

import asyncio
import uuid

from app.config import Settings
from app.db.session import get_session
from app.models.agents import ContextPlan
from app.models.retrieval import RetrievalResult, SearchFilters
from app.retrieval.orchestrator import search as run_search


def _relevance(result: RetrievalResult) -> float:
    return result.rerank_score if result.rerank_score is not None else result.similarity_score


async def execute_context_plan(
    workspace_id: uuid.UUID,
    plan: ContextPlan,
    settings: Settings,
) -> list[RetrievalResult]:
    async def run_task(task_index: int) -> tuple[str, list[RetrievalResult]]:
        task = plan.tasks[task_index]
        filters = SearchFilters(document_types=task.document_types or None)
        async with get_session() as session:
            response = await run_search(
                session, workspace_id, task.query, filters, settings, domain=task.domain
            )
        return task.domain, response.results

    per_task_results = await asyncio.gather(*(run_task(i) for i in range(len(plan.tasks))))

    best_by_chunk: dict[uuid.UUID, RetrievalResult] = {}
    for domain, results in per_task_results:
        for result in results:
            tagged = result.model_copy(update={"source_domain": domain})
            existing = best_by_chunk.get(result.chunk_id)
            if existing is None or _relevance(tagged) > _relevance(existing):
                best_by_chunk[result.chunk_id] = tagged

    return sorted(best_by_chunk.values(), key=_relevance, reverse=True)
