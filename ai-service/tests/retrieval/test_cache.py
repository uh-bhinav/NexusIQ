import uuid

import pytest

from app.config import get_settings
from app.models.retrieval import RetrievalResult, SearchFilters, SearchResponse
from app.retrieval.cache import RetrievalCache


def _response(text: str) -> SearchResponse:
    return SearchResponse(
        results=[
            RetrievalResult(
                chunk_id=uuid.uuid4(),
                document_id=uuid.uuid4(),
                document_name="Doc",
                document_type="SECURITY_POLICY",
                document_version=1,
                is_current=True,
                content=text,
                similarity_score=0.9,
                trust_level="SUPPORTING",
                is_flagged=False,
                citation_reference="Doc",
            )
        ],
        query=text,
        latency_ms=1.0,
    )


@pytest.fixture
async def cache():
    settings = get_settings()
    c = RetrievalCache(settings.redis_url, ttl_seconds=5)
    yield c
    await c.close()


@pytest.mark.asyncio
async def test_cache_missThenSetThenHit(cache):
    workspace_id = uuid.uuid4()
    filters = SearchFilters()

    assert await cache.get(workspace_id, "vendor security policy", filters) is None

    await cache.set(workspace_id, "vendor security policy", filters, _response("cached content"))
    hit = await cache.get(workspace_id, "vendor security policy", filters)

    assert hit is not None
    assert hit.results[0].content == "cached content"


@pytest.mark.asyncio
async def test_cache_normalizesQueryWhitespaceAndCase(cache):
    workspace_id = uuid.uuid4()
    filters = SearchFilters()

    await cache.set(workspace_id, "Vendor  Security Policy", filters, _response("x"))
    hit = await cache.get(workspace_id, "  vendor security   policy  ", filters)

    assert hit is not None


@pytest.mark.asyncio
async def test_cache_keyIncludesWorkspace_noCrossTenantHit(cache):
    workspace_a = uuid.uuid4()
    workspace_b = uuid.uuid4()
    filters = SearchFilters()

    await cache.set(workspace_a, "vendor security policy", filters, _response("workspace A's data"))

    assert await cache.get(workspace_b, "vendor security policy", filters) is None


@pytest.mark.asyncio
async def test_cache_differentFilters_areDifferentKeys(cache):
    workspace_id = uuid.uuid4()

    await cache.set(
        workspace_id, "policy", SearchFilters(document_types=["SECURITY_POLICY"]), _response("a")
    )

    assert await cache.get(workspace_id, "policy", SearchFilters()) is None


@pytest.mark.asyncio
async def test_cache_invalidateWorkspace_missesPreviouslyCachedEntry(cache):
    workspace_id = uuid.uuid4()
    filters = SearchFilters()

    await cache.set(workspace_id, "vendor security policy", filters, _response("stale"))
    assert await cache.get(workspace_id, "vendor security policy", filters) is not None

    await cache.invalidate_workspace(workspace_id)

    assert await cache.get(workspace_id, "vendor security policy", filters) is None
