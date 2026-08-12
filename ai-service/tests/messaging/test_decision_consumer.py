import asyncio
import uuid
from pathlib import Path
from unittest import mock

import pytest
from sqlalchemy import text

from app.config import get_settings
from app.db.session import get_session
from app.embeddings.local import LocalEmbeddingProvider
from app.messaging.decision_consumer import DecisionWorkflowConsumer
from app.messaging.decision_producer import DecisionEventProducer
from app.messaging.envelope import ApprovalCompletedPayload, DecisionRequestedPayload, EventEnvelope
from app.messaging.topics import DECISION_COMPLETED, DECISION_FAILED, DECISION_REQUESTED, dlq

_FIXTURES_DIR = Path(__file__).resolve().parents[1] / "fixtures" / "llm"
_EMBEDDING_PROVIDER = LocalEmbeddingProvider("BAAI/bge-small-en-v1.5", batch_size=8)


async def _drain_matching(topic: str, settings, matches: bytes, timeout: float = 25.0) -> bytes:
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


def _requested_envelope(workspace_id: uuid.UUID, decision_id: uuid.UUID) -> EventEnvelope:
    payload = DecisionRequestedPayload(
        decision_id=decision_id, question="Should Vendor Alpha be approved for EU production?"
    )
    return EventEnvelope.new_event("DECISION_REQUESTED", workspace_id, uuid.uuid4(), payload)


async def _seed_security_policy_workspace() -> uuid.UUID:
    user_id = uuid.uuid4()
    workspace_id = uuid.uuid4()
    document_id = uuid.uuid4()
    content = (
        "All vendor systems processing EU customer data must store and process that data "
        "exclusively within EU/EEA data centers unless an approved exception is on file."
    )
    async with get_session() as session:
        await session.execute(
            text(
                "INSERT INTO users (id, email, name, password_hash, role) "
                "VALUES (:id, :email, 'Consumer Test', 'x', 'ADMIN')"
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
        await session.execute(
            text(
                "INSERT INTO documents "
                "(id, workspace_id, name, document_type, status, uploaded_by) "
                "VALUES (:id, :workspace_id, 'Data Residency Policy', 'SECURITY_POLICY', "
                " 'READY', :uploaded_by)"
            ),
            {"id": document_id, "workspace_id": workspace_id, "uploaded_by": user_id},
        )
        [embedding] = _EMBEDDING_PROVIDER.embed([content])
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
                "model": "BAAI/bge-small-en-v1.5",
            },
        )
        await session.commit()
    return workspace_id


@pytest.mark.asyncio
async def test_handleMessage_malformedEnvelope_routesStraightToDlq():
    settings = get_settings()
    producer = DecisionEventProducer(settings)
    await producer.start()
    try:
        consumer = DecisionWorkflowConsumer(settings, producer=producer)
        drain_task = asyncio.create_task(
            _drain_matching(dlq(DECISION_REQUESTED), settings, b"not even json")
        )
        await asyncio.sleep(0.5)
        await consumer.handle_message(b"not even json", key=None)
        dlq_raw = await drain_task
        assert dlq_raw == b"not even json"
    finally:
        await producer.stop()


@pytest.mark.asyncio
async def test_handleMessage_duplicateEvent_runsWorkflowExactlyOnce():
    settings = get_settings()
    producer = DecisionEventProducer(settings)
    await producer.start()
    try:
        workspace_id = await _seed_security_policy_workspace()
        decision_id = uuid.uuid4()
        envelope = _requested_envelope(workspace_id, decision_id)
        raw = envelope.model_dump_json().encode("utf-8")

        consumer = DecisionWorkflowConsumer(settings, producer=producer)
        with mock.patch(
            "app.messaging.decision_consumer.get_model_provider"
        ) as mock_get_provider:
            from app.llm.mock_provider import MockProvider

            mock_get_provider.return_value = MockProvider(_FIXTURES_DIR)

            await consumer._ensure_schema()
            from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver

            async with AsyncPostgresSaver.from_conn_string(
                settings.langgraph_database_url
            ) as checkpointer:
                await checkpointer.setup()
                consumer._checkpointer = checkpointer

                drain_task = asyncio.create_task(
                    _drain_matching(DECISION_COMPLETED, settings, str(decision_id).encode())
                )
                await asyncio.sleep(0.5)

                await consumer.handle_message(raw, key=str(workspace_id).encode())
                await consumer.handle_message(raw, key=str(workspace_id).encode())

                completed_raw = await drain_task
                assert str(decision_id) in completed_raw.decode("utf-8")

                async with get_session() as session:
                    result = await session.execute(
                        text(
                            "SELECT COUNT(*) FROM processed_events "
                            "WHERE event_id = :event_id AND consumer_group = :group"
                        ),
                        {"event_id": envelope.event_id, "group": consumer.consumer_group},
                    )
                    assert result.scalar_one() == 1
    finally:
        await producer.stop()


@pytest.mark.asyncio
async def test_handleMessage_workflowExceedsTimeout_publishesDecisionFailedWithTimeoutReason():
    """docs/AI/GUARDRAILS.md Layer 4 "Wall clock": a run that exceeds
    WORKFLOW_TIMEOUT_SECONDS terminates cleanly with a reason rather than
    leaving the decision stuck PROCESSING forever."""
    settings = get_settings().model_copy(update={"workflow_timeout_seconds": 1})
    producer = DecisionEventProducer(settings)
    await producer.start()
    try:
        workspace_id = await _seed_security_policy_workspace()
        decision_id = uuid.uuid4()
        envelope = _requested_envelope(workspace_id, decision_id)
        raw = envelope.model_dump_json().encode("utf-8")

        consumer = DecisionWorkflowConsumer(settings, producer=producer)
        with mock.patch(
            "app.messaging.decision_consumer.get_model_provider"
        ) as mock_get_provider:
            from app.llm.mock_provider import MockProvider
            from app.models.agents import IntentAnalysis

            class _SlowProvider(MockProvider):
                async def generate_structured(self, *, system, user, schema, model, **kwargs):
                    if schema is IntentAnalysis:
                        await asyncio.sleep(3.0)
                    return await super().generate_structured(
                        system=system, user=user, schema=schema, model=model
                    )

            mock_get_provider.return_value = _SlowProvider(_FIXTURES_DIR)

            await consumer._ensure_schema()
            from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver

            async with AsyncPostgresSaver.from_conn_string(
                settings.langgraph_database_url
            ) as checkpointer:
                await checkpointer.setup()
                consumer._checkpointer = checkpointer

                drain_task = asyncio.create_task(
                    _drain_matching(DECISION_FAILED, settings, str(decision_id).encode())
                )
                await asyncio.sleep(0.5)

                await consumer.handle_message(raw, key=str(workspace_id).encode())

                failed_raw = await drain_task
                assert str(decision_id) in failed_raw.decode("utf-8")
                assert b"WorkflowTimeout" in failed_raw
    finally:
        await producer.stop()


@pytest.mark.asyncio
async def test_handleMessage_workflowRaises_publishesDecisionFailed():
    settings = get_settings()
    producer = DecisionEventProducer(settings)
    await producer.start()
    try:
        workspace_id = uuid.uuid4()  # no seeded workspace -> retrieval/DB will fail
        decision_id = uuid.uuid4()
        envelope = _requested_envelope(workspace_id, decision_id)
        raw = envelope.model_dump_json().encode("utf-8")

        consumer = DecisionWorkflowConsumer(settings, producer=producer)
        with mock.patch(
            "app.messaging.decision_consumer.get_model_provider"
        ) as mock_get_provider:
            from app.llm.errors import ModelUnavailable
            from app.llm.mock_provider import MockProvider, MockResponse

            mock_get_provider.return_value = MockProvider(
                queue=[MockResponse(error=ModelUnavailable("simulated"))]
            )

            await consumer._ensure_schema()
            from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver

            async with AsyncPostgresSaver.from_conn_string(
                settings.langgraph_database_url
            ) as checkpointer:
                await checkpointer.setup()
                consumer._checkpointer = checkpointer

                drain_task = asyncio.create_task(
                    _drain_matching(DECISION_FAILED, settings, str(decision_id).encode())
                )
                await asyncio.sleep(0.5)

                await consumer.handle_message(raw, key=str(workspace_id).encode())

                failed_raw = await drain_task
                assert str(decision_id) in failed_raw.decode("utf-8")
    finally:
        await producer.stop()


@pytest.mark.asyncio
async def test_handleApprovalMessage_resumesInterruptedRun_duplicateDoesNotDoubleResume():
    """Phase 7 (ADR-006) acceptance criterion 8's Python-side half: a
    duplicate approval.completed does not double-resume the run. The
    Kafka-round-trip counterpart to test_approval_router.py's in-process
    Command(resume=...) tests — this one goes through the real
    DecisionWorkflowConsumer.handle_message/handle_approval_message path."""
    settings = get_settings()
    producer = DecisionEventProducer(settings)
    await producer.start()
    try:
        workspace_id = await _seed_security_policy_workspace()
        decision_id = uuid.uuid4()
        requested_envelope = _requested_envelope(workspace_id, decision_id)
        requested_raw = requested_envelope.model_dump_json().encode("utf-8")

        consumer = DecisionWorkflowConsumer(settings, producer=producer)
        with mock.patch(
            "app.messaging.decision_consumer.get_model_provider"
        ) as mock_get_provider:
            from app.llm.mock_provider import MockProvider
            from app.llm.provider import ModelResult
            from app.models.agents import Recommendation

            class _LowConfidenceProvider(MockProvider):
                async def generate_structured(self, *, system, user, schema, model, **kwargs):
                    if schema is Recommendation:
                        value = Recommendation(
                            recommendation="APPROVE",
                            reasoning_summary="Low confidence on purpose.",
                            confidence=0.30,
                            key_evidence_ids=[],
                            required_actions=[],
                            conditions=[],
                            unresolved_questions=[],
                        )
                        return ModelResult(
                            value=value,
                            model=f"mock-{model}",
                            input_tokens=0,
                            output_tokens=0,
                            latency_ms=0,
                            estimated_cost_usd=0.0,
                            finish_reason="stop",
                            repaired=False,
                        )
                    return await super().generate_structured(
                        system=system, user=user, schema=schema, model=model
                    )

            mock_get_provider.return_value = _LowConfidenceProvider(_FIXTURES_DIR)

            await consumer._ensure_schema()
            from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver

            async with AsyncPostgresSaver.from_conn_string(
                settings.langgraph_database_url
            ) as checkpointer:
                await checkpointer.setup()
                consumer._checkpointer = checkpointer

                completed_drain = asyncio.create_task(
                    _drain_matching(DECISION_COMPLETED, settings, str(decision_id).encode())
                )
                await asyncio.sleep(0.5)
                await consumer.handle_message(requested_raw, key=str(workspace_id).encode())
                await completed_drain  # decision.completed publishes even when interrupted

                approval_payload = ApprovalCompletedPayload(
                    approval_id=uuid.uuid4(),
                    decision_id=decision_id,
                    outcome="APPROVED",
                    resolved_by=uuid.uuid4(),
                    notes="looks fine",
                )
                approval_envelope = EventEnvelope.new_event(
                    "APPROVAL_COMPLETED", workspace_id, uuid.uuid4(), approval_payload
                )
                approval_raw = approval_envelope.model_dump_json().encode("utf-8")

                # First delivery resumes the run.
                await consumer.handle_approval_message(approval_raw, key=str(workspace_id).encode())
                async with get_session() as session:
                    result = await session.execute(
                        text(
                            "SELECT COUNT(*) FROM processed_events "
                            "WHERE event_id = :event_id AND consumer_group = :group"
                        ),
                        {
                            "event_id": approval_envelope.event_id,
                            "group": consumer.approval_consumer_group,
                        },
                    )
                    assert result.scalar_one() == 1

                # Redelivery of the identical event_id must not resume twice
                # (an already-resumed thread has no pending interrupt —
                # calling Command(resume=...) on it again would be a real
                # bug this idempotency guard exists to prevent).
                await consumer.handle_approval_message(approval_raw, key=str(workspace_id).encode())
                async with get_session() as session:
                    result = await session.execute(
                        text(
                            "SELECT COUNT(*) FROM processed_events "
                            "WHERE event_id = :event_id AND consumer_group = :group"
                        ),
                        {
                            "event_id": approval_envelope.event_id,
                            "group": consumer.approval_consumer_group,
                        },
                    )
                    assert result.scalar_one() == 1
    finally:
        await producer.stop()
