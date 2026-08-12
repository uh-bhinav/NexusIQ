"""Node functions (docs/AI/AGENTS.md, docs/AI/ARCHITECTURE.md's graph).
Each is an AgentFn: `(state, deps) -> NodeResult`, wrapped by
graph/instrumentation.py::instrument() before being registered on the
graph — nodes themselves never touch Kafka, spans, or budget accounting.

Model tier per node is config-driven, not hardcoded, but which *tier*
(LLM_MODEL vs LLM_MODEL_HEAVY) each node uses is fixed per
docs/AI/MODEL_STRATEGY.md's table: policy_analyst and decision are the
highest-stakes reads/synthesis and use the heavy tier; context_planner and
risk_analyzer use the fast tier.
"""

import uuid
from typing import Any, Literal

from langgraph.types import interrupt

from app.agents.context_planner import plan_context
from app.agents.decision import synthesize_decision
from app.agents.intent import analyze_intent
from app.agents.policy_analyst import analyze_policy
from app.agents.retrieval import execute_context_plan
from app.agents.risk_analyzer import analyze_risk
from app.agents.validator import validate_recommendation
from app.graph.deps import GraphDeps
from app.graph.instrumentation import NodeResult
from app.graph.state import DecisionState, PolicyFindingDict
from app.guardrails.injection import scan_for_injection
from app.guardrails.metrics import ValidationMetrics, record_validation
from app.models.agents import (
    InjectionFinding,
    PolicyFinding,
    Recommendation,
    ValidationCheck,
    ValidationResult,
)
from app.observability.metrics import record_decision_confidence, record_injection_detected
from app.retrieval.context import assemble_context


async def intent_node(state: DecisionState, deps: GraphDeps) -> NodeResult:
    result = await analyze_intent(
        state["question"], provider=deps.provider, model=deps.settings.llm_model
    )

    # Layer 1 input guardrail (docs/AI/GUARDRAILS.md): heuristic scan on the
    # user's own question, not just retrieved documents. "Flag, proceed with
    # the standing defence" — the run is not blocked (a legitimate question
    # can contain words like "ignore" incidentally), but the attempt is
    # recorded, the same reuse of guardrails/injection.py's ingestion-time
    # scan (Phase 2).
    injection_findings = list(state["injection_findings"])
    flag_reason = scan_for_injection(state["question"])
    if flag_reason is not None:
        injection_findings.append(
            InjectionFinding(
                title="Possible prompt injection in the request itself",
                description=(
                    f"The submitted question matched a heuristic injection pattern "
                    f"({flag_reason}). The standing system-prompt defence applies to every "
                    "downstream agent call; this finding records the attempt for review."
                ),
                confidence=0.5,
                evidence_ids=[],
            )
        )
        record_injection_detected()

    return NodeResult(
        state_update={
            "intent": result.value,
            "decision_type": result.value.decision_type,
            "injection_findings": injection_findings,
        },
        model=result.model,
        input_tokens=result.input_tokens,
        output_tokens=result.output_tokens,
        estimated_cost_usd=result.estimated_cost_usd,
        repaired=result.repaired,
        output_summary={"decision_type": result.value.decision_type},
    )


async def context_planner_node(state: DecisionState, deps: GraphDeps) -> NodeResult:
    assert state["intent"] is not None
    result = await plan_context(
        state["question"], state["intent"], provider=deps.provider, model=deps.settings.llm_model
    )
    return NodeResult(
        state_update={"context_plan": result.value},
        model=result.model,
        input_tokens=result.input_tokens,
        output_tokens=result.output_tokens,
        estimated_cost_usd=result.estimated_cost_usd,
        repaired=result.repaired,
        output_summary={"task_count": len(result.value.tasks)},
    )


async def retrieval_node(state: DecisionState, deps: GraphDeps) -> NodeResult:
    assert state["context_plan"] is not None
    # execute_context_plan opens its own session(s) internally — see its
    # docstring for why a session can't be shared across its concurrent
    # per-task retrievals, let alone threaded through GraphDeps.
    results = await execute_context_plan(deps.workspace_id, state["context_plan"], deps.settings)
    return NodeResult(
        state_update={"retrieved_evidence": results},
        output_summary={"result_count": len(results)},
    )


async def policy_analyst_node(state: DecisionState, deps: GraphDeps) -> NodeResult:
    context = assemble_context(state["retrieved_evidence"], deps.settings.context_token_budget)
    findings, result = await analyze_policy(
        state["question"], context, provider=deps.provider, model=deps.settings.llm_model_heavy
    )
    findings_dicts: list[PolicyFindingDict] = [
        {
            "policy_name": f.policy_name,
            "policy_reference": f.policy_reference,
            "status": f.status,
            "explanation": f.explanation,
            "evidence_ids": f.evidence_ids,
            "confidence": f.confidence,
        }
        for f in findings
    ]
    return NodeResult(
        state_update={"policy_findings": findings_dicts},
        model=result.model,
        input_tokens=result.input_tokens,
        output_tokens=result.output_tokens,
        estimated_cost_usd=result.estimated_cost_usd,
        repaired=result.repaired,
        output_summary={"finding_count": len(findings)},
    )


async def risk_analyzer_node(state: DecisionState, deps: GraphDeps) -> NodeResult:
    context = assemble_context(state["retrieved_evidence"], deps.settings.context_token_budget)
    risk, result = await analyze_risk(
        state["question"], context, provider=deps.provider, model=deps.settings.llm_model
    )
    return NodeResult(
        state_update={"risk_analysis": risk},
        model=result.model,
        input_tokens=result.input_tokens,
        output_tokens=result.output_tokens,
        estimated_cost_usd=result.estimated_cost_usd,
        repaired=result.repaired,
        output_summary={"risk_level": risk.risk_level, "factor_count": len(risk.factors)},
    )


async def decision_node(state: DecisionState, deps: GraphDeps) -> NodeResult:
    """Ends the graph on the first pass, or re-synthesizes on a
    validator-forced retry (Phase 6 — see the validator/decision retry edge
    in graph/builder.py). `requires_human_approval` / `escalation_reasons`
    beyond the validator's own (docs/DATABASE/SCHEMA.md) are still not set
    here: Phase 7's approval_router is the deterministic gate; nothing here
    may auto-approve itself (CLAUDE.md non-negotiable #2)."""
    assert state["risk_analysis"] is not None
    findings = [PolicyFinding(**f) for f in state["policy_findings"]]
    recommendation, result = await synthesize_decision(
        state["question"],
        findings,
        state["risk_analysis"],
        provider=deps.provider,
        model=deps.settings.llm_model_heavy,
        validation_feedback=state["validation_feedback"],
    )
    record_decision_confidence(recommendation.confidence)

    return NodeResult(
        state_update={"recommendation": recommendation, "validation_feedback": None},
        model=result.model,
        input_tokens=result.input_tokens,
        output_tokens=result.output_tokens,
        estimated_cost_usd=result.estimated_cost_usd,
        repaired=result.repaired,
        output_summary={"recommendation": recommendation.recommendation},
    )


def _completeness_failure(checks: list[ValidationCheck]) -> list[str]:
    for check in checks:
        if check.check == "COMPLETENESS" and not check.passed:
            return check.offending_claims
    return []


async def validator_node(state: DecisionState, deps: GraphDeps) -> NodeResult:
    """Tries to break the recommendation before a human sees it
    (docs/AI/AGENTS.md #7). `recommended_action` is computed here, in
    Python, from the deterministic checks plus the current iteration count —
    never trusted from the LLM (models/agents.py's ValidationResult
    docstring) because it drives graph routing (graph/builder.py)."""
    assert state["recommendation"] is not None
    assert state["risk_analysis"] is not None
    assert state["intent"] is not None
    assert state["context_plan"] is not None

    findings = [PolicyFinding(**f) for f in state["policy_findings"]]
    retrieved_chunk_ids = {str(r.chunk_id) for r in state["retrieved_evidence"]}
    planned_domains = [t.domain for t in state["context_plan"].tasks]
    context = assemble_context(state["retrieved_evidence"], deps.settings.context_token_budget)

    checks, evidence_coverage, result = await validate_recommendation(
        state["question"],
        findings,
        state["risk_analysis"],
        state["recommendation"],
        context.evidence_block,
        required_domains=state["intent"].required_domains,
        planned_domains=planned_domains,
        retrieved_chunk_ids=retrieved_chunk_ids,
        provider=deps.provider,
        model=deps.settings.llm_model_heavy,
    )

    checks_passed = all(c.passed for c in checks)
    coverage_ok = evidence_coverage >= deps.settings.hitl_min_evidence_coverage
    passed = checks_passed and coverage_ok
    completeness_missing = _completeness_failure(checks)

    escalation_reasons: list[str] = []
    validation_feedback: str | None = None
    next_iteration = state["iteration"]
    recommended_action: Literal["ACCEPT", "RETRY", "ESCALATE"]

    if passed:
        recommended_action = "ACCEPT"
    elif completeness_missing:
        # A missing domain means context_planner never queried for it —
        # retrying decision_node with the same findings cannot fix a gap in
        # what was retrieved, so escalate immediately rather than spend a
        # retry that is guaranteed to fail identically (documented in
        # docs/IMPLEMENTATION/STATUS.md as a deliberate scope decision).
        recommended_action = "ESCALATE"
        escalation_reasons.append(
            "Validator: required domain(s) never queried by the context planner: "
            f"{completeness_missing}"
        )
    elif state["iteration"] < deps.settings.max_agent_iterations:
        recommended_action = "RETRY"
        next_iteration = state["iteration"] + 1
        failed = [f"{c.check}: {c.details}" for c in checks if not c.passed]
        if not coverage_ok:
            failed.append(
                f"EVIDENCE_COVERAGE: {evidence_coverage:.2f} is below the required "
                f"{deps.settings.hitl_min_evidence_coverage:.2f}"
            )
        validation_feedback = "; ".join(failed)
    else:
        recommended_action = "ESCALATE"
        failed_names = [c.check for c in checks if not c.passed]
        escalation_reasons.append(
            f"Validator: failed after {deps.settings.max_agent_iterations} retries — "
            f"{failed_names}"
        )

    if not coverage_ok and recommended_action == "ESCALATE":
        escalation_reasons.append(
            f"Validator: evidence_coverage={evidence_coverage:.2f} below "
            f"HITL_MIN_EVIDENCE_COVERAGE={deps.settings.hitl_min_evidence_coverage:.2f}"
        )

    validation_result = ValidationResult(
        passed=passed,
        checks=checks,
        evidence_coverage=evidence_coverage,
        recommended_action=recommended_action,
    )

    # Deterministic PROMPT_INJECTION_ATTEMPT finding (docs/AI/GUARDRAILS.md
    # "Prompt injection" layer 6, ROADMAP.md Phase 6 acceptance criterion 4):
    # any chunk flagged at ingestion (Phase 2) that was actually retrieved
    # for this run is surfaced, regardless of whether the model's own output
    # happened to cite it — reading it during context assembly is exposure
    # enough to warrant review, and this doesn't depend on the model noticing
    # and self-reporting.
    injection_findings = list(state["injection_findings"])
    flagged = [r for r in state["retrieved_evidence"] if r.is_flagged]
    if flagged:
        injection_findings.append(
            InjectionFinding(
                title="Retrieved evidence flagged as a possible prompt injection attempt",
                description=(
                    f"{len(flagged)} retrieved chunk(s) were flagged at ingestion time as "
                    "matching a prompt-injection heuristic. The standing system-prompt "
                    "defence (docs/AI/GUARDRAILS.md) instructs every agent to treat retrieved "
                    "content as data, never instructions."
                ),
                confidence=0.7,
                evidence_ids=[str(r.chunk_id) for r in flagged],
            )
        )
        record_injection_detected(len(flagged))

    record_validation(
        ValidationMetrics(
            decision_id=uuid.UUID(state["decision_id"]),
            iteration=state["iteration"],
            passed=passed,
            recommended_action=recommended_action,
            evidence_coverage=evidence_coverage,
            checks=checks,
            injection_findings_count=len(injection_findings),
        )
    )

    return NodeResult(
        state_update={
            "validation_result": validation_result,
            "injection_findings": injection_findings,
            "escalation_reasons": state["escalation_reasons"] + escalation_reasons,
            "validation_feedback": validation_feedback,
            "iteration": next_iteration,
        },
        model=result.model,
        input_tokens=result.input_tokens,
        output_tokens=result.output_tokens,
        estimated_cost_usd=result.estimated_cost_usd,
        repaired=result.repaired,
        output_summary={
            "passed": passed,
            "recommended_action": recommended_action,
            "evidence_coverage": evidence_coverage,
        },
    )


async def unsupported_node(state: DecisionState, _deps: GraphDeps) -> NodeResult:
    """Deterministic terminal node for `decision_type == "unsupported"`
    (docs/AI/AGENTS.md: "unsupported is a valid answer and terminates the
    run early") — zero LLM calls. Ensures the graph always ends with a
    well-formed Recommendation regardless of which path it took, so the
    consumer building DecisionCompletedPayload never has to special-case a
    missing recommendation."""
    intent = state["intent"]
    recommendation = Recommendation(
        recommendation="INSUFFICIENT_INFORMATION",
        reasoning_summary=(
            "The question was not classified as a supported decision type "
            "(vendor_approval, technology_approval, or policy_question)."
        ),
        confidence=intent.confidence if intent is not None else 0.0,
        key_evidence_ids=[],
        required_actions=[],
        conditions=[],
        unresolved_questions=[
            "Reclassify or rephrase the question so it maps to a supported decision type."
        ],
    )
    return NodeResult(
        state_update={"recommendation": recommendation},
        output_summary={"recommendation": "INSUFFICIENT_INFORMATION", "reason": "unsupported"},
    )


_RISK_ORDINAL = {"LOW": 0, "MEDIUM": 1, "HIGH": 2, "CRITICAL": 3}


async def approval_router_node(state: DecisionState, deps: GraphDeps) -> dict[str, Any]:
    """Mirrors spring-api's ApprovalGate (ADR-006) exactly — same six
    triggers, same threshold names — to decide whether to suspend this run
    via `interrupt()`. Java's own gate, evaluated independently against the
    same decision.completed payload, remains authoritative for the actual
    approval record (docs/AI/AGENTS.md #8: "the router mirrors it so the
    graph knows whether to interrupt"); this only controls whether the
    LangGraph run pauses, never whether a human is actually required.

    Deliberately NOT wrapped by graph/instrumentation.py::instrument():
    `interrupt()` raises LangGraph's internal `GraphInterrupt` as its
    control-flow mechanism, and instrument()'s `except Exception` would
    catch that and misreport a pause as a node FAILURE — confirmed
    empirically. "approval_router — not an agent" (docs/AI/AGENTS.md #8) —
    zero LLM calls, zero token/cost accounting, zero decision.progress
    event; the interrupt/resume itself is the only observable effect.
    """
    assert state["recommendation"] is not None
    assert state["risk_analysis"] is not None

    any_violated = any(f["status"] == "VIOLATED" for f in state["policy_findings"])
    any_injection = bool(state["injection_findings"])
    risk_ordinal = _RISK_ORDINAL[state["risk_analysis"].risk_level]
    threshold_ordinal = _RISK_ORDINAL[deps.settings.hitl_escalate_on_risk]
    low_confidence = state["recommendation"].confidence < deps.settings.hitl_min_confidence

    validation_result = state["validation_result"]
    low_coverage = (
        validation_result is not None
        and validation_result.evidence_coverage < deps.settings.hitl_min_evidence_coverage
    )
    validator_escalated = (
        validation_result is not None and validation_result.recommended_action == "ESCALATE"
    )

    requires_approval = (
        any_violated
        or any_injection
        or risk_ordinal >= threshold_ordinal
        or low_confidence
        or low_coverage
        or validator_escalated
    )

    if requires_approval:
        interrupt({"reason": "human_approval_required", "decision_id": state["decision_id"]})

    return {}
