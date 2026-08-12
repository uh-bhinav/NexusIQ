"""Pydantic mirror of Java's EventEnvelope (messaging/EventEnvelope.java) —
same JSON shape, snake_case throughout, so either service can read what the
other writes.
"""

import uuid
from datetime import UTC, datetime
from typing import Any, Generic, TypeVar

from pydantic import BaseModel

T = TypeVar("T", bound=BaseModel)


class EventEnvelope(BaseModel, Generic[T]):
    event_id: uuid.UUID
    event_type: str
    schema_version: int
    occurred_at: datetime
    workspace_id: uuid.UUID
    correlation_id: uuid.UUID | None = None
    causation_id: uuid.UUID | None = None
    # Phase 8 (ADR-007): W3C trace-context string for the span active when
    # this event was published — explicit, like correlation_id, because
    # automatic propagation does not cross a Kafka broker
    # (docs/OPERATIONS/OBSERVABILITY.md). None on a message published with no
    # sampled span active.
    traceparent: str | None = None
    payload: T

    @classmethod
    def new_event(
        cls,
        event_type: str,
        workspace_id: uuid.UUID,
        correlation_id: uuid.UUID | None,
        payload: T,
        traceparent: str | None = None,
    ) -> "EventEnvelope[T]":
        return cls(
            event_id=uuid.uuid4(),
            event_type=event_type,
            schema_version=1,
            # Must be timezone-aware: Java deserializes this field as
            # java.time.Instant, which requires an explicit offset/'Z' in the
            # ISO-8601 string. A naive datetime.now() serializes without one
            # (e.g. "2026-08-10T18:12:12.347661") and Java rejects every such
            # message — confirmed empirically: every document.processed event
            # this service published landed in the DLQ until this was fixed.
            occurred_at=datetime.now(UTC),
            workspace_id=workspace_id,
            correlation_id=correlation_id,
            causation_id=None,
            traceparent=traceparent,
            payload=payload,
        )


class DocumentUploadedPayload(BaseModel):
    document_id: uuid.UUID
    document_type: str
    storage_path: str
    content_type: str | None = None
    size_bytes: int
    checksum_sha256: str
    original_filename: str | None = None


class DocumentProcessedPayload(BaseModel):
    document_id: uuid.UUID
    chunk_count: int


class DocumentFailedPayload(BaseModel):
    document_id: uuid.UUID
    reason: str


class DecisionRequestedPayload(BaseModel):
    decision_id: uuid.UUID
    question: str


class DecisionProgressPayload(BaseModel):
    decision_id: uuid.UUID
    agent_name: str
    sequence_index: int
    status: str  # SUCCESS | FAILED | SKIPPED | RETRIED — matches agent_executions.status CHECK
    model: str | None = None
    input_tokens: int = 0
    output_tokens: int = 0
    latency_ms: int
    estimated_cost_usd: float = 0.0
    output: dict[str, Any] | None = None
    error: str | None = None
    # Phase 8: the OTel trace id of the span this node ran in — populates
    # agent_executions.trace_id (column existed since Phase 5's V7 migration,
    # never written until now). Mirrors Java's DecisionProgressPayload.traceId.
    trace_id: str | None = None


class EvidencePayload(BaseModel):
    document_id: uuid.UUID
    chunk_id: uuid.UUID
    evidence_text: str
    relevance_score: float
    citation_reference: str


class FindingPayload(BaseModel):
    category: str  # POLICY | RISK | GAP | CONFLICT | PROMPT_INJECTION_ATTEMPT
    policy_name: str | None = None
    status: str | None = None  # SATISFIED | PARTIALLY_SATISFIED | VIOLATED | UNKNOWN
    severity: str | None = None  # INFO | LOW | MEDIUM | HIGH | CRITICAL
    title: str
    description: str
    confidence: float
    evidence_chunk_ids: list[uuid.UUID]


class DecisionCompletedPayload(BaseModel):
    decision_id: uuid.UUID
    workflow_version: str
    # All five prompts (intent/context_planner/policy_analyst/risk_analyzer/
    # decision) are currently _v1.md files — this reflects that real,
    # currently-true fact, not per-node granularity (docs/AI/PROMPTS.md:
    # "Every run records prompt_version and workflow_version").
    prompt_version: str
    llm_model: str
    embedding_model: str
    recommendation: str  # APPROVE | CONDITIONAL_APPROVAL | REJECT | INSUFFICIENT_INFORMATION
    reasoning_summary: str
    confidence: float
    risk_level: str  # LOW | MEDIUM | HIGH | CRITICAL
    # Phase 7 (ADR-006): spring-api's ApprovalGate reads these directly —
    # None on the `unsupported` path, which never runs the validator.
    evidence_coverage: float | None = None
    validation_passed: bool | None = None
    validation_escalated: bool | None = None
    required_actions: list[str]
    conditions: list[str]
    unresolved_questions: list[str]
    key_evidence_chunk_ids: list[uuid.UUID]
    evidence: list[EvidencePayload]
    findings: list[FindingPayload]
    # Phase 6: the validator's own escalation triggers (retry cap exhausted,
    # a required domain never queried). Java still forces
    # requires_human_approval=true unconditionally until Phase 7's
    # deterministic gate exists, but these are real, specific reasons rather
    # than only the one placeholder Java adds itself.
    escalation_reasons: list[str]
    total_input_tokens: int
    total_output_tokens: int
    estimated_cost_usd: float
    latency_ms: int


class DecisionFailedPayload(BaseModel):
    decision_id: uuid.UUID
    reason: str


class ApprovalCompletedPayload(BaseModel):
    approval_id: uuid.UUID
    decision_id: uuid.UUID
    outcome: str  # APPROVED | REJECTED
    resolved_by: uuid.UUID
    notes: str | None = None
