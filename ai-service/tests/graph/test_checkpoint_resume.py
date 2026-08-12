"""Acceptance criterion 8: "Killing the AI service mid-run and restarting
resumes from the checkpoint rather than restarting from zero." Uses the
real AsyncPostgresSaver against the real local Postgres (not InMemorySaver —
that's the whole point of this test) in the langgraph schema.
"""

import uuid
from pathlib import Path

import psycopg
import pytest
from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver

from app.config import get_settings
from app.graph.builder import build_graph, initial_state
from app.graph.deps import GraphDeps
from app.llm.mock_provider import MockProvider
from app.models.agents import PolicyAnalysisOutput
from app.observability.tracing import get_in_memory_tracer

_FIXTURES_DIR = Path(__file__).resolve().parents[1] / "fixtures" / "llm"


class _NullProducer:
    async def publish_progress(self, workspace_id, correlation_id, payload):
        pass


class _FailOnceProvider(MockProvider):
    """Fails the first call for `fail_schema`, always — simulates the crash
    that ends process 1 mid-run: everything before the failing node
    completed and was checkpointed; the failing node itself did not."""

    def __init__(self, fixtures_dir, fail_schema):
        super().__init__(fixtures_dir)
        self._fail_schema = fail_schema
        self.calls_by_schema: dict[str, int] = {}

    async def generate_structured(self, *, system, user, schema, model, **kwargs):
        self.calls_by_schema[schema.__name__] = self.calls_by_schema.get(schema.__name__, 0) + 1
        if schema is self._fail_schema:
            raise RuntimeError("simulated process crash")
        return await super().generate_structured(
            system=system, user=user, schema=schema, model=model
        )


class _CountingProvider(MockProvider):
    """No induced failures — represents process 2 after a real restart,
    where whatever caused the crash (e.g. a network blip) is behind it.
    Tracks calls per schema so the test can prove intent/context_planner
    were never re-invoked on resume."""

    def __init__(self, fixtures_dir):
        super().__init__(fixtures_dir)
        self.calls_by_schema: dict[str, int] = {}

    async def generate_structured(self, *, system, user, schema, model, **kwargs):
        self.calls_by_schema[schema.__name__] = self.calls_by_schema.get(schema.__name__, 0) + 1
        return await super().generate_structured(
            system=system, user=user, schema=schema, model=model
        )


@pytest.mark.asyncio
async def test_killedMidRun_resumesFromCheckpoint_doesNotRerunCompletedNodes():
    settings = get_settings()
    tracer, _ = get_in_memory_tracer()
    workspace_id = uuid.uuid4()
    decision_id = str(uuid.uuid4())
    thread_config = {"configurable": {"thread_id": decision_id}}

    base_url = settings.langgraph_database_url.split("?")[0]
    async with await psycopg.AsyncConnection.connect(base_url, autocommit=True) as conn:
        await conn.execute("CREATE SCHEMA IF NOT EXISTS langgraph")

    async with AsyncPostgresSaver.from_conn_string(settings.langgraph_database_url) as checkpointer:
        await checkpointer.setup()

        # First "process": fails on policy_analyst, after intent/context_planner
        # (retrieval is monkeypatched to skip the DB — this test is about
        # checkpoint resume, not retrieval correctness).
        import app.graph.builder as builder_module
        from app.graph.instrumentation import NodeResult

        async def fake_retrieval_node(state, deps):
            return NodeResult(state_update={"retrieved_evidence": []})

        original_retrieval = builder_module.retrieval_node
        builder_module.retrieval_node = fake_retrieval_node
        try:
            crashing_provider = _FailOnceProvider(_FIXTURES_DIR, PolicyAnalysisOutput)
            deps = GraphDeps(
                settings=settings,
                provider=crashing_provider,
                producer=_NullProducer(),  # type: ignore[arg-type]
                tracer=tracer,
                workspace_id=workspace_id,
                correlation_id=None,
            )
            graph = build_graph(deps, checkpointer)
            state = initial_state(
                decision_id, str(workspace_id), "", "Should Vendor Alpha be approved?", "v1"
            )
            with pytest.raises(Exception, match="simulated process crash"):
                await graph.ainvoke(state, thread_config)

            # A checkpoint must exist: intent + context_planner completed
            # before the crash, so the run is resumable, not lost.
            checkpoint = await checkpointer.aget_tuple(thread_config)
            assert checkpoint is not None

            # "Restart": a fresh, non-failing provider (the crash is behind
            # us — a real process restart would construct a brand new
            # provider/graph, and whatever caused the crash is resolved).
            resuming_provider = _CountingProvider(_FIXTURES_DIR)
            resuming_deps = GraphDeps(
                settings=settings,
                provider=resuming_provider,
                producer=_NullProducer(),  # type: ignore[arg-type]
                tracer=tracer,
                workspace_id=workspace_id,
                correlation_id=None,
            )
            resumed_graph = build_graph(resuming_deps, checkpointer)
            final_state = await resumed_graph.ainvoke(None, thread_config)
        finally:
            builder_module.retrieval_node = original_retrieval

    # The resumed run reached a real recommendation.
    assert final_state["recommendation"] is not None
    # Intent and context_planner were NOT re-executed on resume — the
    # resuming provider was never asked for IntentAnalysis or ContextPlan,
    # only for the schemas belonging to nodes that hadn't completed yet
    # (policy_analyst, risk_analyzer, decision).
    assert "IntentAnalysis" not in resuming_provider.calls_by_schema
    assert "ContextPlan" not in resuming_provider.calls_by_schema
    assert resuming_provider.calls_by_schema.get("PolicyAnalysisOutput", 0) >= 1
