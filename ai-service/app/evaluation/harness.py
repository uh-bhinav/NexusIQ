"""The evaluation harness CLI (docs/AI/EVALUATION.md). Seeds the corpus once,
runs every labelled case directly through the graph (build_graph + ainvoke,
same pattern as ai-service's own tests/graph/test_end_to_end.py — no
Kafka/spring-api round trip needed since this is an offline batch tool, not
a production code path), computes metrics per case, aggregates, prints a
report, and writes results as JSON.

Usage:
    uv run python -m app.evaluation.harness
    uv run python -m app.evaluation.harness --provider gemini
    uv run python -m app.evaluation.harness --case EVAL-007
    uv run python -m app.evaluation.harness --compare-baseline
"""

import argparse
import asyncio
import json
import time
import uuid
from pathlib import Path

from langgraph.checkpoint.memory import InMemorySaver

from app.config import Settings, get_settings
from app.db.session import get_session
from app.evaluation.corpus import seed_eval_corpus
from app.evaluation.metrics import (
    aggregate,
    compute_decision_metrics,
    compute_generation_metrics,
    compute_retrieval_metrics,
)
from app.evaluation.models import CaseResult, EvalCase, EvalRun
from app.graph.builder import build_graph, initial_state
from app.graph.deps import GraphDeps
from app.llm.factory import get_model_provider
from app.observability.tracing import get_in_memory_tracer

_DATASET_PATH = Path(__file__).resolve().parent / "datasets" / "cases.json"
_RESULTS_DIR = Path(__file__).resolve().parent / "datasets" / "results"


class _NullProducer:
    async def publish_progress(self, workspace_id, correlation_id, payload) -> None:  # type: ignore[no-untyped-def]
        pass


def load_cases(case_id: str | None = None) -> list[EvalCase]:
    raw = json.loads(_DATASET_PATH.read_text())
    cases = [EvalCase.model_validate(c) for c in raw]
    if case_id:
        cases = [c for c in cases if c.id == case_id]
        if not cases:
            raise ValueError(f"No case with id {case_id!r} in {_DATASET_PATH}")
    return cases


async def _run_one_case(
    case: EvalCase,
    *,
    settings: Settings,
    workspace_id: uuid.UUID,
    document_ids_by_slug: dict[str, uuid.UUID],
) -> CaseResult:
    provider = get_model_provider(settings)
    tracer, _ = get_in_memory_tracer()
    checkpointer = InMemorySaver()
    deps = GraphDeps(
        settings=settings,
        provider=provider,
        producer=_NullProducer(),  # type: ignore[arg-type]
        tracer=tracer,
        workspace_id=workspace_id,
        correlation_id=None,
    )
    graph = build_graph(deps, checkpointer)
    decision_id = str(uuid.uuid4())
    state = initial_state(decision_id, str(workspace_id), "", case.question, "v1")

    started = time.monotonic()
    try:
        final_state = await graph.ainvoke(state, {"configurable": {"thread_id": decision_id}})
    except Exception as exc:  # noqa: BLE001 - a harness run reports failures, it doesn't crash on one
        return CaseResult(
            case_id=case.id, category=case.category, question=case.question, error=str(exc)
        )
    latency_ms = int((time.monotonic() - started) * 1000)

    document_slug_by_id = {str(v): k for k, v in document_ids_by_slug.items()}
    retrieved_evidence = final_state.get("retrieved_evidence") or []
    retrieved_ranked: list[str] = []
    for r in retrieved_evidence:
        slug = document_slug_by_id.get(str(r.document_id))
        if slug and slug not in retrieved_ranked:
            retrieved_ranked.append(slug)
    retrieval_metrics = compute_retrieval_metrics(
        retrieved_ranked, set(case.expected.relevant_document_ids)
    )

    recommendation = final_state.get("recommendation")
    validation_result = final_state.get("validation_result")
    validation_checks = (
        {c.check: c.passed for c in validation_result.checks}
        if validation_result is not None
        else None
    )
    findings = final_state.get("policy_findings") or []
    output_text = " ".join(
        [
            recommendation.reasoning_summary if recommendation else "",
            *[f.get("explanation", "") for f in findings],
            *(recommendation.required_actions if recommendation else []),
            *(recommendation.conditions if recommendation else []),
        ]
    )
    generation_metrics = compute_generation_metrics(
        validation_checks=validation_checks,
        must_not_claim=case.expected.must_not_claim,
        output_text=output_text,
    )

    actual_policy_statuses = {
        f["policy_name"]: f["status"] for f in findings if f.get("policy_name")
    }
    intent = final_state.get("intent")
    decision_metrics = compute_decision_metrics(
        actual_recommendation=recommendation.recommendation if recommendation else "NONE",
        expected_recommendations=case.expected.recommendation,
        actual_policy_statuses=actual_policy_statuses,
        expected_policy_statuses=case.expected.policy_statuses,
        actual_requires_human_approval=bool(final_state.get("requires_human_approval")),
        expected_requires_human_approval=case.expected.requires_human_approval,
        actual_decision_type=intent.decision_type if intent else final_state.get("decision_type"),
        expected_decision_type=case.expected.decision_type,
    )

    return CaseResult(
        case_id=case.id,
        category=case.category,
        question=case.question,
        retrieval=retrieval_metrics,
        generation=generation_metrics,
        decision=decision_metrics,
        latency_ms=latency_ms,
        total_input_tokens=final_state.get("total_input_tokens", 0),
        total_output_tokens=final_state.get("total_output_tokens", 0),
        estimated_cost_usd=final_state.get("estimated_cost_usd", 0.0),
    )


async def run(*, provider: str | None, case_id: str | None) -> EvalRun:
    settings = get_settings()
    if provider:
        settings = settings.model_copy(update={"llm_provider": provider})
    cases = load_cases(case_id)

    seed = await seed_eval_corpus(get_session, settings)

    results = []
    for case in cases:
        result = await _run_one_case(
            case,
            settings=settings,
            workspace_id=seed.workspace_id,
            document_ids_by_slug=seed.document_ids_by_slug,
        )
        results.append(result)
        status = (
            "ERROR"
            if result.error
            else (result.decision.actual_recommendation if result.decision else "?")
        )
        print(f"  {case.id:10s} [{case.category:22s}] -> {status}")

    return EvalRun(
        provider=settings.llm_provider,
        workflow_version="v1",
        prompt_version="v1",
        case_results=results,
        aggregate=aggregate(results),
    )


def _print_report(run_result: EvalRun) -> None:
    m = run_result.aggregate
    print()
    print(f"=== Evaluation report (provider={run_result.provider}) ===")

    def fmt(value: float | None) -> str:
        return "None" if value is None else str(round(value, 2))

    print(f"cases: {m.case_count}  errors: {m.error_count}")
    print(
        f"retrieval:  recall@5={m.recall_at_5:.2f}  recall@10={m.recall_at_10:.2f}  "
        f"precision@5={m.precision_at_5:.2f}  MRR={m.mrr:.2f}  empty_rate={m.empty_result_rate:.2f}"
    )
    print(
        f"generation: groundedness={fmt(m.groundedness)}  "
        f"citation_validity={fmt(m.citation_validity_rate)}  "
        f"hallucination_rate={fmt(m.hallucination_rate)}"
    )
    print(
        f"decision:   recommendation_accuracy={fmt(m.recommendation_accuracy)}  "
        f"intent_accuracy={fmt(m.intent_accuracy)}  "
        f"escalation_precision={fmt(m.escalation_precision)}  "
        f"escalation_recall={fmt(m.escalation_recall)}"
    )
    print(
        f"cost:       tokens_in={m.total_input_tokens}  tokens_out={m.total_output_tokens}  "
        f"cost_usd={m.estimated_cost_usd:.4f}"
    )


def _save_results(run_result: EvalRun, provider: str) -> Path:
    _RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    ts = time.strftime("%Y%m%d-%H%M%S")
    out_path = _RESULTS_DIR / f"{ts}-{provider}.json"
    out_path.write_text(run_result.model_dump_json(indent=2))
    return out_path


def main() -> None:
    parser = argparse.ArgumentParser(description="NexusIQ AI evaluation harness")
    parser.add_argument("--provider", default=None, help="Override LLM_PROVIDER (mock|gemini)")
    parser.add_argument("--case", default=None, help="Run a single case by id (e.g. EVAL-007)")
    args = parser.parse_args()

    run_result = asyncio.run(run(provider=args.provider, case_id=args.case))
    _print_report(run_result)
    out_path = _save_results(run_result, run_result.provider)
    print(f"\nResults written to {out_path}")


if __name__ == "__main__":
    main()
