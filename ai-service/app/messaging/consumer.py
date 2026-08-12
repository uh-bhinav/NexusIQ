"""Consumes document.uploaded, runs the ingestion pipeline, and emits
document.processed / document.failed. Bounded retry with exponential backoff,
then DLQ (.claude/rules/architecture.md: 3 attempts, 1s/4s/16s, then DLQ) —
except IngestionError, which is treated as a permanent, single-attempt
failure (a corrupt file will fail identically on every retry).

Idempotency: a `processed_events` row (event_id, consumer_group) is inserted
in the same transaction as the chunk writes (.claude/rules/architecture.md).
"""

import asyncio
import logging
import uuid

from aiokafka import AIOKafkaConsumer
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings, get_settings
from app.db.session import get_session
from app.ingestion.pipeline import IngestionError, run_ingestion_pipeline
from app.messaging.envelope import (
    DocumentFailedPayload,
    DocumentProcessedPayload,
    DocumentUploadedPayload,
    EventEnvelope,
)
from app.messaging.producer import DocumentEventProducer
from app.messaging.topics import DOCUMENT_UPLOADED
from app.retrieval.cache import get_cache

logger = logging.getLogger(__name__)

MAX_ATTEMPTS = 3
BACKOFF_SECONDS = [1, 4, 16]

_UploadedEnvelope = EventEnvelope[DocumentUploadedPayload]


class DocumentIngestionConsumer:
    def __init__(
        self, settings: Settings | None = None, producer: DocumentEventProducer | None = None
    ):
        self.settings = settings or get_settings()
        self.consumer_group = self.settings.kafka_consumer_group_ai
        self._producer = producer or DocumentEventProducer(self.settings)
        self._consumer = AIOKafkaConsumer(
            DOCUMENT_UPLOADED,
            bootstrap_servers=self.settings.kafka_bootstrap_servers,
            group_id=self.consumer_group,
            enable_auto_commit=False,
            auto_offset_reset="earliest",
        )
        self._task: asyncio.Task[None] | None = None

    async def start(self) -> None:
        await self._producer.start()
        await self._consumer.start()
        self._task = asyncio.create_task(self._run())

    async def stop(self) -> None:
        if self._task is not None:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
        await self._consumer.stop()
        await self._producer.stop()

    async def _run(self) -> None:
        async for record in self._consumer:
            await self.handle_message(record.value, record.key)
            await self._consumer.commit()

    async def handle_message(self, raw: bytes, key: bytes | None) -> None:
        try:
            envelope = _UploadedEnvelope.model_validate_json(raw)
        except Exception:
            logger.exception(
                "Malformed document.uploaded message — routing to DLQ, no document to update"
            )
            await self._producer.publish_to_dlq(DOCUMENT_UPLOADED, raw, key)
            return

        last_error: Exception | None = None
        for attempt in range(1, MAX_ATTEMPTS + 1):
            try:
                await self._process_once(envelope)
                return
            except IngestionError as e:
                logger.warning("document.uploaded %s failed permanently: %s", envelope.event_id, e)
                await self._publish_failed(envelope, str(e))
                return
            except Exception as e:  # noqa: BLE001 - deliberately broad: anything else is transient
                last_error = e
                logger.warning(
                    "document.uploaded %s attempt %d/%d failed: %s",
                    envelope.event_id,
                    attempt,
                    MAX_ATTEMPTS,
                    e,
                )
                if attempt < MAX_ATTEMPTS:
                    await asyncio.sleep(BACKOFF_SECONDS[attempt - 1])

        await self._producer.publish_to_dlq(DOCUMENT_UPLOADED, raw, key)
        reason = f"Processing failed after {MAX_ATTEMPTS} attempts: {last_error}"
        await self._publish_failed(envelope, reason)

    async def _process_once(self, envelope: _UploadedEnvelope) -> None:
        """Publishing document.processed must happen even on a retry that
        follows a partially-successful earlier attempt (DB write committed,
        then cache invalidation or the publish itself failed transiently) —
        otherwise the dedup check below would see the row already inserted
        and return early, leaving the document stuck in UPLOADED forever with
        orphaned chunks. Confirmed empirically in a live run (Redis briefly
        unreachable after a successful commit). Re-publishing on a retry is
        safe either way: Java's DocumentProcessedConsumer is itself idempotent
        on event_id (.claude/rules/architecture.md)."""
        async with get_session() as session:
            already_processed = await session.execute(
                text(
                    "SELECT 1 FROM processed_events "
                    "WHERE event_id = :event_id AND consumer_group = :group"
                ),
                {"event_id": envelope.event_id, "group": self.consumer_group},
            )
            if already_processed.first() is not None:
                logger.info(
                    "document.uploaded %s already processed — re-publishing completion "
                    "without redoing the ingestion work",
                    envelope.event_id,
                )
                chunk_count = await self._existing_chunk_count(
                    session, envelope.payload.document_id
                )
            else:
                chunk_count = await run_ingestion_pipeline(
                    session, self.settings, envelope.workspace_id, envelope.payload
                )
                await session.execute(
                    text(
                        "INSERT INTO processed_events (event_id, consumer_group) "
                        "VALUES (:event_id, :group)"
                    ),
                    {"event_id": envelope.event_id, "group": self.consumer_group},
                )
                await session.commit()

        # Best-effort: cache staleness is already bounded by TTL
        # (docs/AI/RAG.md), so a failure here must never block the publish
        # below — that's the critical step.
        try:
            await get_cache(self.settings).invalidate_workspace(envelope.workspace_id)
        except Exception:
            logger.warning(
                "Cache invalidation failed for workspace %s — will self-heal via TTL",
                envelope.workspace_id,
                exc_info=True,
            )

        await self._producer.publish_processed(
            envelope.workspace_id,
            envelope.correlation_id,
            DocumentProcessedPayload(
                document_id=envelope.payload.document_id, chunk_count=chunk_count
            ),
        )

    async def _existing_chunk_count(self, session: AsyncSession, document_id: uuid.UUID) -> int:
        result = await session.execute(
            text("SELECT COUNT(*) FROM document_chunks WHERE document_id = :document_id"),
            {"document_id": document_id},
        )
        return int(result.scalar_one())

    async def _publish_failed(self, envelope: _UploadedEnvelope, reason: str) -> None:
        await self._producer.publish_failed(
            envelope.workspace_id,
            envelope.correlation_id,
            DocumentFailedPayload(document_id=envelope.payload.document_id, reason=reason[:500]),
        )
