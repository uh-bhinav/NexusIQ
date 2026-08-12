import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api.agents import router as agents_router
from app.api.chunks import router as chunks_router
from app.api.health import router as health_router
from app.api.search import router as search_router
from app.config import get_settings
from app.messaging.consumer import DocumentIngestionConsumer
from app.messaging.decision_consumer import DecisionWorkflowConsumer

settings = get_settings()
logging.basicConfig(level=settings.log_level)


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    document_consumer = DocumentIngestionConsumer(settings)
    decision_consumer = DecisionWorkflowConsumer(settings)
    await document_consumer.start()
    await decision_consumer.start()
    try:
        yield
    finally:
        await decision_consumer.stop()
        await document_consumer.stop()


app = FastAPI(title="NexusIQ AI Service", lifespan=lifespan)
app.include_router(health_router)
app.include_router(search_router)
app.include_router(agents_router)
app.include_router(chunks_router)
