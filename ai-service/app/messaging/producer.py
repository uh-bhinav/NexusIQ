import uuid

from aiokafka import AIOKafkaProducer

from app.config import Settings
from app.messaging.envelope import DocumentFailedPayload, DocumentProcessedPayload, EventEnvelope
from app.messaging.topics import DOCUMENT_FAILED, DOCUMENT_PROCESSED, dlq
from app.observability.trace_context import current_traceparent


class DocumentEventProducer:
    def __init__(self, settings: Settings):
        self._settings = settings
        self._producer = AIOKafkaProducer(bootstrap_servers=settings.kafka_bootstrap_servers)

    async def start(self) -> None:
        await self._producer.start()

    async def stop(self) -> None:
        await self._producer.stop()

    async def publish_processed(
        self,
        workspace_id: uuid.UUID,
        correlation_id: uuid.UUID | None,
        payload: DocumentProcessedPayload,
    ) -> None:
        envelope = EventEnvelope.new_event(
            "DOCUMENT_PROCESSED", workspace_id, correlation_id, payload, current_traceparent()
        )
        await self._producer.send_and_wait(
            DOCUMENT_PROCESSED,
            key=str(workspace_id).encode("utf-8"),
            value=envelope.model_dump_json().encode("utf-8"),
        )

    async def publish_failed(
        self,
        workspace_id: uuid.UUID,
        correlation_id: uuid.UUID | None,
        payload: DocumentFailedPayload,
    ) -> None:
        envelope = EventEnvelope.new_event(
            "DOCUMENT_FAILED", workspace_id, correlation_id, payload, current_traceparent()
        )
        await self._producer.send_and_wait(
            DOCUMENT_FAILED,
            key=str(workspace_id).encode("utf-8"),
            value=envelope.model_dump_json().encode("utf-8"),
        )

    async def publish_to_dlq(
        self, source_topic: str, raw_value: bytes, key: bytes | None
    ) -> None:
        await self._producer.send_and_wait(dlq(source_topic), key=key, value=raw_value)
