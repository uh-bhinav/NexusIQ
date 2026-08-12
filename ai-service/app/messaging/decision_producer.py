import uuid

from aiokafka import AIOKafkaProducer

from app.config import Settings
from app.messaging.envelope import (
    DecisionCompletedPayload,
    DecisionFailedPayload,
    DecisionProgressPayload,
    EventEnvelope,
)
from app.messaging.topics import DECISION_COMPLETED, DECISION_FAILED, DECISION_PROGRESS, dlq
from app.observability.trace_context import current_traceparent


class DecisionEventProducer:
    def __init__(self, settings: Settings):
        self._settings = settings
        self._producer = AIOKafkaProducer(bootstrap_servers=settings.kafka_bootstrap_servers)

    async def start(self) -> None:
        await self._producer.start()

    async def stop(self) -> None:
        await self._producer.stop()

    async def publish_progress(
        self,
        workspace_id: uuid.UUID,
        correlation_id: uuid.UUID | None,
        payload: DecisionProgressPayload,
    ) -> None:
        envelope = EventEnvelope.new_event(
            "DECISION_PROGRESS", workspace_id, correlation_id, payload, current_traceparent()
        )
        await self._producer.send_and_wait(
            DECISION_PROGRESS,
            key=str(payload.decision_id).encode("utf-8"),
            value=envelope.model_dump_json().encode("utf-8"),
        )

    async def publish_completed(
        self,
        workspace_id: uuid.UUID,
        correlation_id: uuid.UUID | None,
        payload: DecisionCompletedPayload,
    ) -> None:
        envelope = EventEnvelope.new_event(
            "DECISION_COMPLETED", workspace_id, correlation_id, payload, current_traceparent()
        )
        await self._producer.send_and_wait(
            DECISION_COMPLETED,
            key=str(payload.decision_id).encode("utf-8"),
            value=envelope.model_dump_json().encode("utf-8"),
        )

    async def publish_failed(
        self,
        workspace_id: uuid.UUID,
        correlation_id: uuid.UUID | None,
        payload: DecisionFailedPayload,
    ) -> None:
        envelope = EventEnvelope.new_event(
            "DECISION_FAILED", workspace_id, correlation_id, payload, current_traceparent()
        )
        await self._producer.send_and_wait(
            DECISION_FAILED,
            key=str(payload.decision_id).encode("utf-8"),
            value=envelope.model_dump_json().encode("utf-8"),
        )

    async def publish_to_dlq(self, source_topic: str, raw_value: bytes, key: bytes | None) -> None:
        await self._producer.send_and_wait(dlq(source_topic), key=key, value=raw_value)
