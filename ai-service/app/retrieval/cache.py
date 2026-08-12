"""Redis retrieval cache (docs/AI/RAG.md "Caching"). The tenant is part of the
key — a cache key without it is a cross-tenant data leak, not a performance
detail (.claude/rules/security.md).

Invalidation uses a per-workspace generation counter rather than SCAN/KEYS
(both O(N) and blocking against production Redis): the cache key embeds the
workspace's current generation, and marking a workspace dirty is a single
`INCR` — every previously-cached key for that workspace is then simply never
looked up again and expires naturally via TTL. Functionally equivalent to "the
workspace's retrieval cache is invalidated when a document reaches READY", the
literal requirement in RAG.md, without an expensive scan.
"""

import hashlib
import uuid
from typing import cast

from redis.asyncio import Redis

from app.config import Settings, get_settings
from app.models.retrieval import SearchFilters, SearchResponse

_GEN_KEY_PREFIX = "retrieval:gen"
_CACHE_KEY_PREFIX = "retrieval"


def _normalize_query(query: str) -> str:
    return " ".join(query.strip().lower().split())


class RetrievalCache:
    def __init__(self, redis_url: str, ttl_seconds: int):
        # Explicit, generous timeouts: get_cache() constructs a fresh client
        # per call by design (docstring below), and Phase 5's concurrent
        # per-domain retrieval tasks (asyncio.gather in agents/retrieval.py)
        # can construct several of these at once — redis-py's default
        # socket_connect_timeout is aggressive enough that a real live run
        # with several simultaneous new connections hit it and raised
        # TimeoutError, confirmed empirically. 5s is well inside the
        # retrieval latency budget (docs/AI/RAG.md) and this is a cache, not
        # a source of truth — a slow Redis degrades latency, it never
        # produces a wrong answer.
        self._redis = Redis.from_url(
            redis_url, decode_responses=True, socket_connect_timeout=5, socket_timeout=5
        )
        self._ttl_seconds = ttl_seconds

    async def close(self) -> None:
        await self._redis.aclose()

    async def _build_key(
        self, workspace_id: uuid.UUID, query: str, filters: SearchFilters
    ) -> str:
        gen_key = f"{_GEN_KEY_PREFIX}:{workspace_id}"
        raw_generation = cast(str | None, await self._redis.get(gen_key))
        generation = raw_generation if raw_generation is not None else "0"
        normalized = _normalize_query(query)
        filters_json = filters.model_dump_json(exclude_none=True)
        digest = hashlib.sha256(f"{normalized}|{filters_json}".encode()).hexdigest()
        return f"{_CACHE_KEY_PREFIX}:{workspace_id}:{generation}:{digest}"

    async def get(
        self, workspace_id: uuid.UUID, query: str, filters: SearchFilters
    ) -> SearchResponse | None:
        key = await self._build_key(workspace_id, query, filters)
        raw = cast(str | None, await self._redis.get(key))
        if raw is None:
            return None
        return SearchResponse.model_validate_json(raw)

    async def set(
        self,
        workspace_id: uuid.UUID,
        query: str,
        filters: SearchFilters,
        response: SearchResponse,
    ) -> None:
        key = await self._build_key(workspace_id, query, filters)
        await self._redis.set(key, response.model_dump_json(), ex=self._ttl_seconds)

    async def invalidate_workspace(self, workspace_id: uuid.UUID) -> None:
        await self._redis.incr(f"{_GEN_KEY_PREFIX}:{workspace_id}")


def get_cache(settings: Settings | None = None) -> RetrievalCache:
    """Deliberately NOT a cached singleton (unlike get_embedding_provider/
    get_reranker): a redis.asyncio client holds a live connection bound to
    whatever event loop was running when it first connects. In production
    there's one loop for the process lifetime, so this would be harmless to
    cache — but a cached client surviving across event loops (e.g. a
    TestClient request's own temporary loop, or pytest-asyncio's session loop
    after such a request) fails with "Event loop is closed" on the next use.
    Confirmed empirically. `Redis.from_url()` doesn't connect eagerly, so
    constructing fresh here is cheap — same trade-off as NullPool on the
    SQLAlchemy engine (app/db/session.py), for the same underlying reason.
    """
    settings = settings or get_settings()
    return RetrievalCache(settings.redis_url, settings.retrieval_cache_ttl_seconds)
