"""Consumes decision.requested, runs the Phase 5 graph, and emits
decision.completed / decision.failed. Unlike DocumentIngestionConsumer,
there is deliberately no message-level retry loop here: every individual
LLM call already retries transient errors internally (llm/gemini_provider.py:
2 retries with backoff), so an exception escaping graph.ainvoke() has
already exhausted the retry budget appropriate to its layer. Re-running the
whole graph from the consumer would re-spend the cost of already-successful
nodes; resuming from the checkpoint on a fresh process start is the correct
recovery path instead (docs/AI/ARCHITECTURE.md "Durability"; roadmap
acceptance criterion 8), not an in-message retry.

Idempotency: a `processed_events` row (event_id, consumer_group) is the
duplicate-delivery guard. There's no DB side effect to share a transaction
with here (Python never writes decision-domain tables), unlike the document
consumer's chunk-write + marker pattern.
"""

import asyncio
import logging
import time
import uuid
from contextlib import AbstractAsyncContextManager
from typing import Any, cast

import psycopg
from aiokafka import AIOKafkaConsumer
from langchain_core.runnables import RunnableConfig
from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
from langgraph.types import Command
from opentelemetry.trace import StatusCode
from sqlalchemy import text

from app.config import Settings, get_settings
from app.db.session import get_session
from app.graph.builder import build_graph, initial_state
from app.graph.deps import GraphDeps
from app.graph.errors import WorkflowTimeout
from app.graph.state import DecisionState
from app.llm.factory import get_model_provider
from app.messaging.decision_producer import DecisionEventProducer
from app.messaging.envelope import (
    ApprovalCompletedPayload,
    DecisionCompletedPayload,
    DecisionFailedPayload,
    DecisionRequestedPayload,
    EventEnvelope,
    EvidencePayload,
    FindingPayload,
)
from app.messaging.topics import APPROVAL_COMPLETED, DECISION_REQUESTED
from app.observability.trace_context import extract_context
from app.observability.tracing import get_tracer

logger = logging.getLogger(__name__)

WORKFLOW_VERSION = "v1"
PROMPT_VERSION = "v1"

_RequestedEnvelope = EventEnvelope[DecisionRequestedPayload]
_ApprovalEnvelope = EventEnvelope[ApprovalCompletedPayload]


class DecisionWorkflowConsumer:
    def __init__(
        self, settings: Settings | None = None, producer: DecisionEventProducer | None = None
    ):
        self.settings = settings or get_settings()
        self.consumer_group = self.settings.kafka_consumer_group_decisions
        # Separate consumer group from the decisions one — logically distinct
        # streams (fresh requests vs. resume-after-approval) sharing only the
        # checkpointer, not offset tracking.
        self.approval_consumer_group = f"{self.consumer_group}-approvals"
        self._producer = producer or DecisionEventProducer(self.settings)
        self._consumer = AIOKafkaConsumer(
            DECISION_REQUESTED,
            bootstrap_servers=self.settings.kafka_bootstrap_servers,
            group_id=self.consumer_group,
            enable_auto_commit=False,
            auto_offset_reset="earliest",
        )
        self._approval_consumer = AIOKafkaConsumer(
            APPROVAL_COMPLETED,
            bootstrap_servers=self.settings.kafka_bootstrap_servers,
            group_id=self.approval_consumer_group,
            enable_auto_commit=False,
            auto_offset_reset="earliest",
        )
        self._checkpointer_cm: AbstractAsyncContextManager[AsyncPostgresSaver] | None = None
        self._checkpointer: AsyncPostgresSaver | None = None
        self._task: asyncio.Task[None] | None = None
        self._approval_task: asyncio.Task[None] | None = None

    async def start(self) -> None:
        await self._producer.start()
        await self._consumer.start()
        await self._approval_consumer.start()
        await self._ensure_schema()
        cm = AsyncPostgresSaver.from_conn_string(self.settings.langgraph_database_url)
        self._checkpointer = await cm.__aenter__()
        self._checkpointer_cm = cm
        await self._checkpointer.setup()
        self._task = asyncio.create_task(self._run())
        self._approval_task = asyncio.create_task(self._run_approvals())

    async def stop(self) -> None:
        for task in (self._task, self._approval_task):
            if task is not None:
                task.cancel()
                try:
                    await task
                except asyncio.CancelledError:
                    pass
        await self._consumer.stop()
        await self._approval_consumer.stop()
        await self._producer.stop()
        if self._checkpointer_cm is not None:
            await self._checkpointer_cm.__aexit__(None, None, None)

    async def _ensure_schema(self) -> None:
        """Flyway never touches the `langgraph` schema (ADR-005) — the
        checkpointer's own .setup() creates its tables, but only once the
        schema namespace itself exists."""
        base_url = self.settings.langgraph_database_url.split("?")[0]
        async with await psycopg.AsyncConnection.connect(base_url, autocommit=True) as conn:
            await conn.execute("CREATE SCHEMA IF NOT EXISTS langgraph")

    async def _run(self) -> None:
        async for record in self._consumer:
            await self.handle_message(record.value, record.key)
            await self._consumer.commit()

    async def _run_approvals(self) -> None:
        async for record in self._approval_consumer:
            await self.handle_approval_message(record.value, record.key)
            await self._approval_consumer.commit()

    async def handle_message(self, raw: bytes, key: bytes | None) -> None:
        try:
            envelope = _RequestedEnvelope.model_validate_json(raw)
        except Exception:
            logger.exception(
                "Malformed decision.requested message — routing to DLQ, no run to update"
            )
            await self._producer.publish_to_dlq(DECISION_REQUESTED, raw, key)
            return

        try:
            if await self._already_processed(envelope.event_id, self.consumer_group):
                logger.info(
                    "decision.requested %s already processed — skipping duplicate delivery",
                    envelope.event_id,
                )
                return
            await self._run_workflow(envelope)
            await self._mark_processed(envelope.event_id, self.consumer_group)
        except Exception as e:  # noqa: BLE001 - single attempt by design, see module docstring
            reason = f"{type(e).__name__}: {e}"
            logger.warning(
                "decision.requested %s failed: %s", envelope.event_id, reason, exc_info=True
            )
            await self._producer.publish_failed(
                envelope.workspace_id,
                envelope.correlation_id,
                DecisionFailedPayload(
                    decision_id=envelope.payload.decision_id, reason=reason[:500]
                ),
            )

    async def handle_approval_message(self, raw: bytes, key: bytes | None) -> None:
        """Resumes the LangGraph run an escalated decision suspended at
        (graph/nodes.py::approval_router_node's interrupt()). Java is already
        authoritative for the approval record by the time this fires — the
        human's decision was committed in ApprovalService's own transaction
        before approval.completed was even published. This only unblocks the
        checkpoint so the run reaches a clean terminal state; it does not
        publish anything further (docs/IMPLEMENTATION/STATUS.md: republishing
        decision.completed here would duplicate evidence/findings rows in
        Java, since each publish creates new ones — confirmed by reasoning
        through DecisionCompletedConsumer's insert-only logic, not by hitting
        the bug live).

        Single-attempt by design, like handle_message: swallows its own
        exceptions (logs, does not raise) so the consumer loop survives and
        the offset still commits. A resume failure leaves the LangGraph
        checkpoint interrupted with no automatic retry — accepted as rare,
        recorded technical debt (there is no established retry path for it,
        matching how this module already treats decision.requested failures
        as single-attempt)."""
        try:
            envelope = _ApprovalEnvelope.model_validate_json(raw)
        except Exception:
            logger.exception(
                "Malformed approval.completed message — routing to DLQ, no run to resume"
            )
            await self._producer.publish_to_dlq(APPROVAL_COMPLETED, raw, key)
            return

        try:
            if await self._already_processed(envelope.event_id, self.approval_consumer_group):
                logger.info(
                    "approval.completed %s already processed — skipping duplicate delivery "
                    "(does not double-resume the run)",
                    envelope.event_id,
                )
                return
            await self._resume_after_approval(envelope)
            await self._mark_processed(envelope.event_id, self.approval_consumer_group)
        except Exception:
            logger.exception(
                "approval.completed %s failed to resume decision %s — checkpoint remains "
                "interrupted, no automatic retry",
                envelope.event_id,
                envelope.payload.decision_id,
            )

    async def _resume_after_approval(self, envelope: _ApprovalEnvelope) -> None:
        assert self._checkpointer is not None
        decision_id = envelope.payload.decision_id
        thread_config: RunnableConfig = {"configurable": {"thread_id": str(decision_id)}}

        tracer = get_tracer(self.settings)
        deps = GraphDeps(
            settings=self.settings,
            provider=get_model_provider(self.settings),
            producer=self._producer,
            tracer=tracer,
            workspace_id=envelope.workspace_id,
            correlation_id=envelope.correlation_id,
        )
        graph = build_graph(deps, self._checkpointer)
        resume_value = {
            "outcome": envelope.payload.outcome,
            "resolved_by": str(envelope.payload.resolved_by),
            "notes": envelope.payload.notes,
        }
        # Child of envelope.traceparent — the span Java's ApprovalService was
        # in when it published approval.completed — so the resumed portion of
        # the run joins the SAME trace as the original decision.workflow span
        # rather than starting an unlinked one.
        with tracer.start_as_current_span(
            "decision.workflow.resume", context=extract_context(envelope.traceparent)
        ) as resume_span:
            resume_span.set_attribute("decision_id", str(decision_id))
            resume_span.set_attribute("approval_outcome", envelope.payload.outcome)
            try:
                await asyncio.wait_for(
                    graph.ainvoke(Command(resume=resume_value), thread_config),
                    timeout=self.settings.workflow_timeout_seconds,
                )
            except TimeoutError as e:
                resume_span.set_status(StatusCode.ERROR, "workflow timeout")
                raise WorkflowTimeout(
                    f"decision {decision_id} exceeded WORKFLOW_TIMEOUT_SECONDS="
                    f"{self.settings.workflow_timeout_seconds}"
                ) from e
        logger.info(
            "decision %s resumed after approval.completed (%s)",
            decision_id,
            envelope.payload.outcome,
        )

    async def _already_processed(self, event_id: uuid.UUID, group: str) -> bool:
        async with get_session() as session:
            result = await session.execute(
                text(
                    "SELECT 1 FROM processed_events "
                    "WHERE event_id = :event_id AND consumer_group = :group"
                ),
                {"event_id": event_id, "group": group},
            )
            return result.first() is not None

    async def _mark_processed(self, event_id: uuid.UUID, group: str) -> None:
        async with get_session() as session:
            await session.execute(
                text(
                    "INSERT INTO processed_events (event_id, consumer_group) "
                    "VALUES (:event_id, :group)"
                ),
                {"event_id": event_id, "group": group},
            )
            await session.commit()

    async def _run_workflow(self, envelope: _RequestedEnvelope) -> None:
        assert self._checkpointer is not None
        decision_id = envelope.payload.decision_id
        thread_config: RunnableConfig = {"configurable": {"thread_id": str(decision_id)}}
        start = time.monotonic()

        # A significant lifecycle event (docs/OPERATIONS/OBSERVABILITY.md
        # "Logs": "INFO: significant lifecycle events") carrying
        # correlation_id explicitly — Python has no MDC equivalent to Java's
        # logback pattern, so unlike the Java side this has to be in the
        # message itself to satisfy Phase 8 AC2 ("the same correlation_id
        # appears in Java logs, Python logs and the trace").
        logger.info(
            "decision %s: starting workflow (correlation_id=%s, workspace_id=%s)",
            decision_id,
            envelope.correlation_id,
            envelope.workspace_id,
        )

        tracer = get_tracer(self.settings)
        deps = GraphDeps(
            settings=self.settings,
            provider=get_model_provider(self.settings),
            producer=self._producer,
            tracer=tracer,
            workspace_id=envelope.workspace_id,
            correlation_id=envelope.correlation_id,
        )
        graph = build_graph(deps, self._checkpointer)

        # The outer span here is what makes "one trace spans HTTP -> Kafka ->
        # AI service -> each agent node" (roadmap Phase 8 acceptance
        # criterion 1) true: it's a child of envelope.traceparent (the span
        # Java's DecisionService.create() was in when it published
        # decision.requested), and every node span nested inside it
        # (graph/instrumentation.py's start_as_current_span calls) becomes a
        # descendant automatically via ambient context propagation — no
        # further wiring needed once this one span exists with the right
        # parent.
        with tracer.start_as_current_span(
            "decision.workflow", context=extract_context(envelope.traceparent)
        ) as workflow_span:
            workflow_span.set_attribute("decision_id", str(decision_id))
            workflow_span.set_attribute("correlation_id", str(envelope.correlation_id))

            final_state: DecisionState
            existing_checkpoint = await self._checkpointer.aget_tuple(thread_config)
            try:
                if existing_checkpoint is not None:
                    logger.info(
                        "decision %s: resuming from existing checkpoint rather than restarting",
                        decision_id,
                    )
                    final_state = await asyncio.wait_for(
                        graph.ainvoke(None, thread_config),
                        timeout=self.settings.workflow_timeout_seconds,
                    )
                else:
                    start_state = initial_state(
                        str(decision_id),
                        str(envelope.workspace_id),
                        str(envelope.correlation_id) if envelope.correlation_id else "",
                        envelope.payload.question,
                        WORKFLOW_VERSION,
                    )
                    final_state = await asyncio.wait_for(
                        graph.ainvoke(start_state, thread_config),
                        timeout=self.settings.workflow_timeout_seconds,
                    )
            except TimeoutError as e:
                # docs/AI/GUARDRAILS.md Layer 4 "Wall clock" — terminate cleanly
                # with a reason rather than leaving the run PROCESSING forever.
                # The checkpoint already written by any completed nodes remains
                # in Postgres; a redelivered decision.requested would resume
                # from it exactly like the crash-recovery path (bugs #2/#5 in
                # Phase 5's STATUS.md entry), not restart from scratch.
                workflow_span.set_status(StatusCode.ERROR, "workflow timeout")
                raise WorkflowTimeout(
                    f"decision {decision_id} exceeded WORKFLOW_TIMEOUT_SECONDS="
                    f"{self.settings.workflow_timeout_seconds}"
                ) from e

            if cast(dict[str, Any], final_state).get("__interrupt__"):
                logger.info(
                    "decision %s suspended pending human approval (approval_router_node)",
                    decision_id,
                )
                workflow_span.set_attribute("suspended_for_approval", True)

        latency_ms = int((time.monotonic() - start) * 1000)
        await self._publish_completed(envelope, final_state, latency_ms)

    async def _publish_completed(
        self, envelope: _RequestedEnvelope, state: DecisionState, latency_ms: int
    ) -> None:
        decision_id = envelope.payload.decision_id
        recommendation = state["recommendation"]
        policy_findings = state["policy_findings"]
        risk_analysis = state["risk_analysis"]
        retrieved_evidence = state["retrieved_evidence"]
        assert recommendation is not None

        cited_chunk_ids: set[str] = set(recommendation.key_evidence_ids)
        for finding in policy_findings:
            cited_chunk_ids.update(finding["evidence_ids"])
        if risk_analysis is not None:
            for factor in risk_analysis.factors:
                cited_chunk_ids.update(factor.evidence_ids)

        by_chunk_id = {str(r.chunk_id): r for r in retrieved_evidence}
        evidence_payloads = [
            EvidencePayload(
                document_id=by_chunk_id[cid].document_id,
                chunk_id=by_chunk_id[cid].chunk_id,
                evidence_text=by_chunk_id[cid].content,
                relevance_score=(
                    by_chunk_id[cid].rerank_score or by_chunk_id[cid].similarity_score
                ),
                citation_reference=by_chunk_id[cid].citation_reference,
            )
            for cid in cited_chunk_ids
            if cid in by_chunk_id
        ]

        finding_payloads = [
            FindingPayload(
                category="POLICY",
                policy_name=finding["policy_name"],
                status=finding["status"],
                severity=None,
                title=f"{finding['policy_name']} ({finding['policy_reference']})",
                description=finding["explanation"],
                confidence=finding["confidence"],
                evidence_chunk_ids=[
                    uuid.UUID(cid) for cid in finding["evidence_ids"] if cid in by_chunk_id
                ],
            )
            for finding in policy_findings
        ]
        if risk_analysis is not None:
            finding_payloads.extend(
                FindingPayload(
                    category="RISK",
                    policy_name=None,
                    status=None,
                    severity=factor.severity,
                    title=f"{factor.category} risk ({factor.likelihood})",
                    description=factor.description,
                    confidence=risk_analysis.confidence,
                    evidence_chunk_ids=[
                        uuid.UUID(cid) for cid in factor.evidence_ids if cid in by_chunk_id
                    ],
                )
                for factor in risk_analysis.factors
            )
        finding_payloads.extend(
            FindingPayload(
                category="PROMPT_INJECTION_ATTEMPT",
                policy_name=None,
                status=None,
                severity=None,
                title=finding.title,
                description=finding.description,
                confidence=finding.confidence,
                evidence_chunk_ids=[
                    uuid.UUID(cid) for cid in finding.evidence_ids if cid in by_chunk_id
                ],
            )
            for finding in state["injection_findings"]
        )

        payload = DecisionCompletedPayload(
            decision_id=decision_id,
            workflow_version=WORKFLOW_VERSION,
            prompt_version=PROMPT_VERSION,
            llm_model=self.settings.llm_model,
            embedding_model=self.settings.embedding_model,
            recommendation=recommendation.recommendation,
            reasoning_summary=recommendation.reasoning_summary,
            confidence=recommendation.confidence,
            risk_level=risk_analysis.risk_level if risk_analysis is not None else "LOW",
            evidence_coverage=(
                state["validation_result"].evidence_coverage
                if state["validation_result"] is not None
                else None
            ),
            validation_passed=(
                state["validation_result"].passed
                if state["validation_result"] is not None
                else None
            ),
            validation_escalated=(
                state["validation_result"].recommended_action == "ESCALATE"
                if state["validation_result"] is not None
                else None
            ),
            required_actions=recommendation.required_actions,
            conditions=recommendation.conditions,
            unresolved_questions=recommendation.unresolved_questions,
            key_evidence_chunk_ids=[
                uuid.UUID(cid) for cid in recommendation.key_evidence_ids if cid in by_chunk_id
            ],
            evidence=evidence_payloads,
            findings=finding_payloads,
            escalation_reasons=state["escalation_reasons"],
            total_input_tokens=state["total_input_tokens"],
            total_output_tokens=state["total_output_tokens"],
            estimated_cost_usd=state["estimated_cost_usd"],
            latency_ms=latency_ms,
        )
        await self._producer.publish_completed(
            envelope.workspace_id, envelope.correlation_id, payload
        )
