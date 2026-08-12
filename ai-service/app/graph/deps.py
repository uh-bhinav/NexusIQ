"""Bundles what every node closure needs, built once per decision run in the
consumer and threaded through graph/builder.py's node factories. Keeps node
factory signatures from growing a parameter per dependency.

Deliberately does NOT hold a shared AsyncSession: LangGraph schedules each
node as its own `asyncio.create_task()` (confirmed by inspecting its
Pregel runner), so a session created in the outer `_run_workflow` coroutine
and used inside a node's task crosses an asyncio task boundary — SQLAlchemy's
async extension does not support that and raises
`IllegalStateChangeError: Method 'close()' can't be called here` at
teardown, confirmed empirically in a live run. This is the same class of
event-loop/task-affinity bug NullPool (app/db/session.py) and the
non-cached Redis client (retrieval/cache.py) already exist to avoid —
retrieval_node opens its own short-lived session via get_session()
internally instead, matching that established pattern.
"""

import uuid
from dataclasses import dataclass

from opentelemetry.trace import Tracer

from app.config import Settings
from app.llm.provider import ModelProvider
from app.messaging.decision_producer import DecisionEventProducer


@dataclass
class GraphDeps:
    settings: Settings
    provider: ModelProvider
    producer: DecisionEventProducer
    tracer: Tracer
    workspace_id: uuid.UUID
    correlation_id: uuid.UUID | None
