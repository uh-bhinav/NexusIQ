from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.pool import NullPool

from app.config import get_settings

_settings = get_settings()

# NullPool: no connection is ever reused across event loops. In production
# there's exactly one loop for the process lifetime so this just means a new
# asyncpg connection per checkout (fine at this project's scale). In tests,
# a pooled connection created under one event loop (e.g. FastAPI TestClient's
# internal portal loop) crashes when reused from pytest-asyncio's loop —
# confirmed empirically ("Future attached to a different loop").
engine = create_async_engine(_settings.async_database_url, poolclass=NullPool)
async_session_factory = async_sessionmaker(engine, expire_on_commit=False)


@asynccontextmanager
async def get_session() -> AsyncIterator[AsyncSession]:
    async with async_session_factory() as session:
        yield session
