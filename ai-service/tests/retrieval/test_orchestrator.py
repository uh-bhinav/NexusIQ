import time
import uuid

import pytest
from sqlalchemy import text

from app.config import get_settings
from app.db.session import get_session
from app.embeddings.local import LocalEmbeddingProvider
from app.retrieval.orchestrator import search

_MODEL = "BAAI/bge-small-en-v1.5"
_provider = LocalEmbeddingProvider(_MODEL, batch_size=8)


async def _seed_user_and_workspace(session) -> tuple[uuid.UUID, uuid.UUID]:
    user_id = uuid.uuid4()
    workspace_id = uuid.uuid4()
    await session.execute(
        text(
            "INSERT INTO users (id, email, name, password_hash, role) "
            "VALUES (:id, :email, 'Orchestrator Test', 'x', 'ADMIN')"
        ),
        {"id": user_id, "email": f"{user_id}@example.com"},
    )
    await session.execute(
        text(
            "INSERT INTO workspaces (id, name, slug, created_by) "
            "VALUES (:id, 'ws', :slug, :created_by)"
        ),
        {"id": workspace_id, "slug": f"ws-{workspace_id}", "created_by": user_id},
    )
    return user_id, workspace_id


async def _seed_document_with_chunk(session, workspace_id, user_id, content: str) -> None:
    document_id = uuid.uuid4()
    await session.execute(
        text(
            "INSERT INTO documents "
            "(id, workspace_id, name, document_type, status, uploaded_by) "
            "VALUES "
            "(:id, :workspace_id, 'Security Policy', 'SECURITY_POLICY', 'READY', :uploaded_by)"
        ),
        {"id": document_id, "workspace_id": workspace_id, "uploaded_by": user_id},
    )
    [embedding] = _provider.embed([content])
    await session.execute(
        text(
            "INSERT INTO document_chunks "
            "(document_id, workspace_id, chunk_index, content, embedding, "
            " embedding_model, embedding_version) "
            "VALUES (:document_id, :workspace_id, 0, :content, "
            " CAST(:embedding AS vector), :model, 1)"
        ),
        {
            "document_id": document_id,
            "workspace_id": workspace_id,
            "content": content,
            "embedding": str(embedding),
            "model": _MODEL,
        },
    )


@pytest.mark.asyncio
async def test_search_endToEnd_returnsRankedRerankedResultsUnderOneSecond():
    async with get_session() as session:
        user_id, workspace_id = await _seed_user_and_workspace(session)
        await _seed_document_with_chunk(
            session,
            workspace_id,
            user_id,
            "All third-party vendors processing production data must hold a current "
            "ISO 27001 certification or equivalent.",
        )
        await session.commit()

        settings = get_settings()
        # Warm the embedding/reranker model singletons first — loading them is
        # a one-time process-startup cost in production (or at worst a
        # cold-first-request cost), never part of steady-state per-query
        # latency, which is what the <1s acceptance criterion is about.
        # Confirmed empirically: the unwarmed call took ~18s, entirely model
        # load time, not retrieval work.
        await search(session, workspace_id, "warm-up query", settings=settings)

        start = time.perf_counter()
        response = await search(
            session, workspace_id, "Does the vendor need ISO 27001?", settings=settings
        )
        elapsed = time.perf_counter() - start

        assert len(response.results) == 1
        assert response.results[0].rerank_score is not None
        assert response.cached is False
        assert elapsed < 1.0


@pytest.mark.asyncio
async def test_search_secondIdenticalQuery_isServedFromCache():
    async with get_session() as session:
        user_id, workspace_id = await _seed_user_and_workspace(session)
        await _seed_document_with_chunk(
            session, workspace_id, user_id, "Vendors must disclose all sub-processors in writing."
        )
        await session.commit()

        settings = get_settings()
        first = await search(session, workspace_id, "sub-processor disclosure", settings=settings)
        second = await search(session, workspace_id, "sub-processor disclosure", settings=settings)

        assert first.cached is False
        assert second.cached is True
        assert second.results[0].content == first.results[0].content


@pytest.mark.asyncio
async def test_search_redisUnavailable_stillReturnsResultsFromPostgres():
    # .claude/rules/architecture.md's degradation table: "Redis down -> Serve
    # from Postgres, log cache-miss metric. Never fail the request." Points
    # redis_host at a real, closed TCP port (nothing listens on 6399 in this
    # environment) rather than mocking RetrievalCache — this exercises the
    # actual connection-refused path through get_cache()'s real redis-py
    # client, not a stand-in for it. get_cache() builds a fresh client per
    # call from settings.redis_url, so overriding just this setting is
    # enough; Postgres and the embedding/reranker models stay real.
    async with get_session() as session:
        user_id, workspace_id = await _seed_user_and_workspace(session)
        await _seed_document_with_chunk(
            session,
            workspace_id,
            user_id,
            "All incident response commitments require a 4-hour notification window.",
        )
        await session.commit()

        settings = get_settings().model_copy(update={"redis_host": "localhost", "redis_port": 1})

        response = await search(
            session, workspace_id, "incident notification window", settings=settings
        )

        assert response.cached is False
        assert len(response.results) == 1
        assert "4-hour notification" in response.results[0].content

        # A cache *write* failing after a real search must not raise either
        # — the response already returned to the caller must not later be
        # invalidated by a background cache-population error.
        second = await search(
            session, workspace_id, "incident notification window", settings=settings
        )
        assert second.cached is False
        assert len(second.results) == 1
