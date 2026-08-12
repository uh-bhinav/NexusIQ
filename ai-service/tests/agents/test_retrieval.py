import uuid

import pytest

from app.agents import retrieval as retrieval_module
from app.agents.retrieval import execute_context_plan
from app.config import get_settings
from app.models.agents import ContextPlan, RetrievalTask
from app.models.retrieval import RetrievalResult, SearchResponse

_SHARED_CHUNK = uuid.uuid4()
_ONLY_SECURITY_CHUNK = uuid.uuid4()
_ONLY_PROCUREMENT_CHUNK = uuid.uuid4()


def _result(chunk_id: uuid.UUID, similarity: float, rerank: float | None = None) -> RetrievalResult:
    return RetrievalResult(
        chunk_id=chunk_id,
        document_id=uuid.uuid4(),
        document_name="Doc",
        document_type="SECURITY_POLICY",
        document_version=1,
        is_current=True,
        content="content",
        similarity_score=similarity,
        rerank_score=rerank,
        trust_level="SUPPORTING",
        is_flagged=False,
        citation_reference="Doc §1",
    )


@pytest.mark.asyncio
async def test_executeContextPlan_tagsResultsWithOriginatingDomain(monkeypatch):
    async def fake_search(session, workspace_id, query, filters, settings, domain=None):
        if "security" in query:
            return SearchResponse(
                results=[_result(_ONLY_SECURITY_CHUNK, 0.9)], query=query, latency_ms=1
            )
        return SearchResponse(
            results=[_result(_ONLY_PROCUREMENT_CHUNK, 0.8)], query=query, latency_ms=1
        )

    monkeypatch.setattr(retrieval_module, "run_search", fake_search)

    plan = ContextPlan(
        tasks=[
            RetrievalTask(
                domain="security", query="security query", document_types=[],
                rationale="r", priority="CRITICAL",
            ),
            RetrievalTask(
                domain="procurement", query="procurement query", document_types=[],
                rationale="r", priority="IMPORTANT",
            ),
        ],
        historical_lookup=False,
    )
    results = await execute_context_plan(uuid.uuid4(), plan, get_settings())

    by_id = {r.chunk_id: r for r in results}
    assert by_id[_ONLY_SECURITY_CHUNK].source_domain == "security"
    assert by_id[_ONLY_PROCUREMENT_CHUNK].source_domain == "procurement"


@pytest.mark.asyncio
async def test_executeContextPlan_dedupesByChunkId_keepingHighestScore(monkeypatch):
    async def fake_search(session, workspace_id, query, filters, settings, domain=None):
        if "low" in query:
            return SearchResponse(
                results=[_result(_SHARED_CHUNK, 0.5, rerank=0.3)], query=query, latency_ms=1
            )
        return SearchResponse(
            results=[_result(_SHARED_CHUNK, 0.5, rerank=0.9)], query=query, latency_ms=1
        )

    monkeypatch.setattr(retrieval_module, "run_search", fake_search)

    plan = ContextPlan(
        tasks=[
            RetrievalTask(
                domain="a", query="low relevance", document_types=[], rationale="r",
                priority="SUPPORTING",
            ),
            RetrievalTask(
                domain="b", query="high relevance", document_types=[], rationale="r",
                priority="CRITICAL",
            ),
        ],
        historical_lookup=False,
    )
    results = await execute_context_plan(uuid.uuid4(), plan, get_settings())

    assert len(results) == 1
    assert results[0].rerank_score == 0.9
    assert results[0].source_domain == "b"
