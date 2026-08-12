import asyncio
import shutil
import uuid
from pathlib import Path
from unittest import mock

import pytest
from sqlalchemy import text

from app.config import Settings, get_settings
from app.db.session import get_session
from app.ingestion.pipeline import run_ingestion_pipeline
from app.messaging.consumer import DocumentIngestionConsumer
from app.messaging.envelope import DocumentUploadedPayload, EventEnvelope
from app.messaging.producer import DocumentEventProducer
from app.messaging.topics import DOCUMENT_FAILED, DOCUMENT_PROCESSED, DOCUMENT_UPLOADED, dlq
from tests.conftest import seed_workspace_and_document

FIXTURES = Path(__file__).resolve().parents[1] / "fixtures"


async def _drain_matching(
    topic: str, settings: Settings, matches: bytes, timeout: float = 20.0
) -> bytes:
    """Consumes from `topic` (a throwaway, `earliest`-offset group) until a
    record containing `matches` is seen. This is a real, persistent local
    broker shared across every test run — earlier runs (including manual
    debugging) leave old messages behind on these topics, so draining "the
    first message seen" is not reliable; filtering for the message this test
    itself caused is (confirmed empirically: a stale message from an earlier
    debug script was picked up before this filter was added)."""
    from aiokafka import AIOKafkaConsumer

    consumer = AIOKafkaConsumer(
        topic,
        bootstrap_servers=settings.kafka_bootstrap_servers,
        group_id=f"test-drain-{uuid.uuid4()}",
        auto_offset_reset="earliest",
        enable_auto_commit=True,
    )
    await consumer.start()
    try:
        async with asyncio.timeout(timeout):
            async for record in consumer:
                if matches in record.value:
                    return record.value
    finally:
        await consumer.stop()
    raise AssertionError(f"No matching message observed on {topic} within {timeout}s")


def _uploaded_envelope(
    workspace_id: uuid.UUID, document_id: uuid.UUID, storage_path: str
) -> EventEnvelope[DocumentUploadedPayload]:
    payload = DocumentUploadedPayload(
        document_id=document_id,
        document_type="SECURITY_POLICY",
        storage_path=storage_path,
        content_type="text/markdown",
        size_bytes=100,
        checksum_sha256="deadbeef",
        original_filename="sample_policy.md",
    )
    return EventEnvelope.new_event("DOCUMENT_UPLOADED", workspace_id, uuid.uuid4(), payload)


@pytest.mark.asyncio
async def test_handleMessage_successfulIngestion_publishesDocumentProcessed(tmp_path):
    settings = get_settings().model_copy(update={"storage_local_path": str(tmp_path)})
    producer = DocumentEventProducer(settings)
    await producer.start()
    try:
        consumer = DocumentIngestionConsumer(settings, producer=producer)

        async with get_session() as session:
            workspace_id, document_id = await seed_workspace_and_document(session)
            await session.commit()

        target_dir = tmp_path / str(workspace_id)
        target_dir.mkdir(parents=True)
        shutil.copy(FIXTURES / "sample_policy.md", target_dir / "sample_policy.md")
        storage_path = f"{workspace_id}/sample_policy.md"

        envelope = _uploaded_envelope(workspace_id, document_id, storage_path)
        raw = envelope.model_dump_json().encode("utf-8")

        drain_task = asyncio.create_task(
            _drain_matching(DOCUMENT_PROCESSED, settings, str(document_id).encode())
        )
        await asyncio.sleep(0.5)  # let the drain consumer join its group before we send
        await consumer.handle_message(raw, key=str(workspace_id).encode())

        processed_raw = await drain_task
        processed = processed_raw.decode("utf-8")
        assert str(document_id) in processed
        assert '"chunk_count"' in processed
    finally:
        await producer.stop()


@pytest.mark.asyncio
async def test_handleMessage_corruptFile_publishesDocumentFailed_noRetryDelay(tmp_path):
    settings = get_settings().model_copy(update={"storage_local_path": str(tmp_path)})
    producer = DocumentEventProducer(settings)
    await producer.start()
    try:
        consumer = DocumentIngestionConsumer(settings, producer=producer)

        async with get_session() as session:
            workspace_id, document_id = await seed_workspace_and_document(session)
            await session.commit()

        target_dir = tmp_path / str(workspace_id)
        target_dir.mkdir(parents=True)
        shutil.copy(FIXTURES / "corrupt.pdf", target_dir / "corrupt.pdf")
        storage_path = f"{workspace_id}/corrupt.pdf"

        payload = DocumentUploadedPayload(
            document_id=document_id,
            document_type="SECURITY_POLICY",
            storage_path=storage_path,
            content_type="application/pdf",
            size_bytes=10,
            checksum_sha256="deadbeef",
            original_filename="corrupt.pdf",
        )
        envelope = EventEnvelope.new_event("DOCUMENT_UPLOADED", workspace_id, uuid.uuid4(), payload)
        raw = envelope.model_dump_json().encode("utf-8")

        drain_task = asyncio.create_task(
            _drain_matching(DOCUMENT_FAILED, settings, str(document_id).encode())
        )
        await asyncio.sleep(0.5)

        start = asyncio.get_event_loop().time()
        await consumer.handle_message(raw, key=str(workspace_id).encode())
        elapsed = asyncio.get_event_loop().time() - start

        # IngestionError (corrupt file) is non-retryable — must fail on the
        # first attempt, not after the ~5s+ backoff a transient error would take.
        assert elapsed < 3.0

        failed_raw = await drain_task
        failed = failed_raw.decode("utf-8")
        assert str(document_id) in failed
    finally:
        await producer.stop()


@pytest.mark.asyncio
async def test_handleMessage_malformedEnvelope_routesStraightToDlq():
    settings = get_settings()
    producer = DocumentEventProducer(settings)
    await producer.start()
    try:
        consumer = DocumentIngestionConsumer(settings, producer=producer)

        drain_task = asyncio.create_task(
            _drain_matching(dlq(DOCUMENT_UPLOADED), settings, b"not even json")
        )
        await asyncio.sleep(0.5)

        await consumer.handle_message(b"not even json", key=None)

        dlq_raw = await drain_task
        assert dlq_raw == b"not even json"
    finally:
        await producer.stop()


@pytest.mark.asyncio
async def test_handleMessage_duplicateEvent_appliesExactlyOnce(tmp_path):
    settings = get_settings().model_copy(update={"storage_local_path": str(tmp_path)})
    producer = DocumentEventProducer(settings)
    await producer.start()
    try:
        consumer = DocumentIngestionConsumer(settings, producer=producer)

        async with get_session() as session:
            workspace_id, document_id = await seed_workspace_and_document(session)
            await session.commit()

        target_dir = tmp_path / str(workspace_id)
        target_dir.mkdir(parents=True)
        shutil.copy(FIXTURES / "sample_policy.md", target_dir / "sample_policy.md")
        storage_path = f"{workspace_id}/sample_policy.md"
        envelope = _uploaded_envelope(workspace_id, document_id, storage_path)
        raw = envelope.model_dump_json().encode("utf-8")
        # Reuse the SAME raw bytes (same event_id) for both deliveries.

        await consumer.handle_message(raw, key=str(workspace_id).encode())
        await consumer.handle_message(raw, key=str(workspace_id).encode())

        async with get_session() as session:
            result = await session.execute(
                text(
                    "SELECT COUNT(*) FROM processed_events "
                    "WHERE event_id = :event_id AND consumer_group = :group"
                ),
                {"event_id": envelope.event_id, "group": consumer.consumer_group},
            )
            count = result.scalar_one()
            assert count == 1

            chunk_result = await session.execute(
                text("SELECT COUNT(*) FROM document_chunks WHERE document_id = :document_id"),
                {"document_id": document_id},
            )
            assert chunk_result.scalar_one() > 0
    finally:
        await producer.stop()


class _FlakyPublishProducer(DocumentEventProducer):
    """Fails the first N calls to publish_processed, then behaves normally —
    simulates a transient failure (e.g. a Kafka blip) that happens *after*
    the DB transaction has already committed."""

    def __init__(self, settings: Settings, fail_times: int):
        super().__init__(settings)
        self._fail_times = fail_times
        self.publish_processed_calls = 0

    async def publish_processed(self, workspace_id, correlation_id, payload):  # noqa: ANN001
        self.publish_processed_calls += 1
        if self.publish_processed_calls <= self._fail_times:
            raise RuntimeError("simulated transient publish failure")
        await super().publish_processed(workspace_id, correlation_id, payload)


@pytest.mark.asyncio
async def test_handleMessage_publishFailsAfterCommit_retrySucceedsWithoutRedoingWork(tmp_path):
    """Regression test for a real bug found in a live run: if the DB commit
    succeeds but the subsequent publish fails transiently, a naive retry would
    see the processed_events row already inserted, treat the message as a
    duplicate, and return without ever publishing document.processed — the
    document would stay UPLOADED forever with orphaned chunks."""
    settings = get_settings().model_copy(update={"storage_local_path": str(tmp_path)})
    producer = _FlakyPublishProducer(settings, fail_times=1)
    await producer.start()
    try:
        consumer = DocumentIngestionConsumer(settings, producer=producer)

        async with get_session() as session:
            workspace_id, document_id = await seed_workspace_and_document(session)
            await session.commit()

        target_dir = tmp_path / str(workspace_id)
        target_dir.mkdir(parents=True)
        shutil.copy(FIXTURES / "sample_policy.md", target_dir / "sample_policy.md")
        storage_path = f"{workspace_id}/sample_policy.md"
        envelope = _uploaded_envelope(workspace_id, document_id, storage_path)
        raw = envelope.model_dump_json().encode("utf-8")

        drain_task = asyncio.create_task(
            _drain_matching(DOCUMENT_PROCESSED, settings, str(document_id).encode())
        )
        await asyncio.sleep(0.5)

        with mock.patch(
            "app.messaging.consumer.run_ingestion_pipeline", wraps=run_ingestion_pipeline
        ) as pipeline_spy:
            await consumer.handle_message(raw, key=str(workspace_id).encode())

        processed_raw = await drain_task
        assert str(document_id) in processed_raw.decode("utf-8")
        assert producer.publish_processed_calls == 2  # 1 failure, then success

        # The retry took the "already processed" branch, not a second run of
        # the (expensive) extract/chunk/embed pipeline.
        assert pipeline_spy.call_count == 1

        async with get_session() as session:
            chunk_result = await session.execute(
                text("SELECT COUNT(*) FROM document_chunks WHERE document_id = :document_id"),
                {"document_id": document_id},
            )
            assert chunk_result.scalar_one() > 0
    finally:
        await producer.stop()
