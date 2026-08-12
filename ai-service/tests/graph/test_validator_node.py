import uuid

import pytest

from app.config import get_settings
from app.graph.deps import GraphDeps
from app.graph.nodes import validator_node
from app.graph.state import DecisionState
from app.llm.mock_provider import MockProvider, MockResponse
from app.models.agents import (
    ContextPlan,
    IntentAnalysis,
    LLMValidationOutput,
    Recommendation,
    RetrievalTask,
    RiskAssessment,
    RiskFactor,
    ValidationCheck,
)
from app.models.retrieval import RetrievalResult
from app.observability.tracing import get_in_memory_tracer

_CHUNK_1 = uuid.uuid4()


def _passing_llm_output() -> LLMValidationOutput:
    def ok(check: str) -> ValidationCheck:
        return ValidationCheck(check=check, passed=True, details="ok", offending_claims=[])  # type: ignore[arg-type]

    return LLMValidationOutput(
        evidence_grounding=ok("EVIDENCE_GROUNDING"),
        contradiction=ok("CONTRADICTION"),
        hallucination=ok("HALLUCINATION"),
        confidence_justification=ok("CONFIDENCE_JUSTIFICATION"),
    )


def _failing_llm_output(details: str = "not grounded") -> LLMValidationOutput:
    def ok(check: str) -> ValidationCheck:
        return ValidationCheck(check=check, passed=True, details="ok", offending_claims=[])  # type: ignore[arg-type]

    return LLMValidationOutput(
        evidence_grounding=ValidationCheck(
            check="EVIDENCE_GROUNDING", passed=False, details=details, offending_claims=["x"]
        ),
        contradiction=ok("CONTRADICTION"),
        hallucination=ok("HALLUCINATION"),
        confidence_justification=ok("CONFIDENCE_JUSTIFICATION"),
    )


def _retrieved(is_flagged: bool = False) -> RetrievalResult:
    return RetrievalResult(
        chunk_id=_CHUNK_1,
        document_id=uuid.uuid4(),
        document_name="Doc",
        document_type="SECURITY_POLICY",
        document_version=1,
        is_current=True,
        content="content",
        similarity_score=0.9,
        trust_level="AUTHORITATIVE",
        is_flagged=is_flagged,
        citation_reference="Doc §1",
    )


def _state(*, retrieved_evidence: list[RetrievalResult], iteration: int = 0) -> DecisionState:
    intent = IntentAnalysis(
        decision_type="vendor_approval",
        entities=["Vendor Alpha"],
        jurisdiction="EU",
        environment="production",
        required_domains=["security"],
        missing_information=[],
        confidence=0.9,
    )
    context_plan = ContextPlan(
        tasks=[
            RetrievalTask(
                domain="security", query="q", document_types=[], rationale="r", priority="CRITICAL"
            )
        ],
        historical_lookup=False,
    )
    risk = RiskAssessment(
        risk_level="LOW",
        factors=[
            RiskFactor(
                category="DATA",
                description="...",
                severity="LOW",
                likelihood="UNLIKELY",
                evidence_ids=[str(_CHUNK_1)],
            )
        ],
        missing_information=[],
        confidence=0.85,
    )
    recommendation = Recommendation(
        recommendation="APPROVE",
        reasoning_summary="...",
        confidence=0.8,
        key_evidence_ids=[str(_CHUNK_1)],
        required_actions=[],
        conditions=[],
        unresolved_questions=[],
    )
    return DecisionState(
        decision_id=str(uuid.uuid4()),
        workspace_id=str(uuid.uuid4()),
        correlation_id=str(uuid.uuid4()),
        workflow_version="v1",
        question="Should Vendor Alpha be approved for EU production?",
        decision_type="vendor_approval",
        intent=intent,
        context_plan=context_plan,
        retrieved_evidence=retrieved_evidence,
        policy_findings=[
            {
                "policy_name": "Data Residency Policy",
                "policy_reference": "SP-102 §1",
                "status": "SATISFIED",
                "explanation": "...",
                "evidence_ids": [str(_CHUNK_1)],
                "confidence": 0.9,
            }
        ],
        risk_analysis=risk,
        recommendation=recommendation,
        validation_result=None,
        injection_findings=[],
        validation_feedback=None,
        iteration=iteration,
        requires_human_approval=False,
        escalation_reasons=[],
        total_input_tokens=0,
        total_output_tokens=0,
        estimated_cost_usd=0.0,
        errors=[],
    )


def _deps(provider: MockProvider, **settings_overrides) -> GraphDeps:
    tracer, _ = get_in_memory_tracer()
    settings = get_settings().model_copy(update=settings_overrides)
    return GraphDeps(
        settings=settings,
        provider=provider,
        producer=None,  # type: ignore[arg-type]
        tracer=tracer,
        workspace_id=uuid.uuid4(),
        correlation_id=None,
    )


@pytest.mark.asyncio
async def test_validatorNode_allChecksPass_recommendsAccept():
    provider = MockProvider(queue=[MockResponse(value=_passing_llm_output())])
    deps = _deps(provider)
    state = _state(retrieved_evidence=[_retrieved()])

    result = await validator_node(state, deps)

    validation_result = result.state_update["validation_result"]
    assert validation_result.passed
    assert validation_result.recommended_action == "ACCEPT"
    assert result.state_update["iteration"] == 0
    assert result.state_update["validation_feedback"] is None


@pytest.mark.asyncio
async def test_validatorNode_fixableFailureBelowCap_recommendsRetry_incrementsIteration():
    provider = MockProvider(queue=[MockResponse(value=_failing_llm_output())])
    deps = _deps(provider, max_agent_iterations=2)
    state = _state(retrieved_evidence=[_retrieved()], iteration=0)

    result = await validator_node(state, deps)

    validation_result = result.state_update["validation_result"]
    assert not validation_result.passed
    assert validation_result.recommended_action == "RETRY"
    assert result.state_update["iteration"] == 1
    assert result.state_update["validation_feedback"] is not None
    assert "EVIDENCE_GROUNDING" in result.state_update["validation_feedback"]
    assert result.state_update["escalation_reasons"] == []


@pytest.mark.asyncio
async def test_validatorNode_fixableFailureAtCap_recommendsEscalate_notRetry():
    provider = MockProvider(queue=[MockResponse(value=_failing_llm_output())])
    deps = _deps(provider, max_agent_iterations=2)
    state = _state(retrieved_evidence=[_retrieved()], iteration=2)

    result = await validator_node(state, deps)

    validation_result = result.state_update["validation_result"]
    assert validation_result.recommended_action == "ESCALATE"
    assert result.state_update["iteration"] == 2
    assert len(result.state_update["escalation_reasons"]) == 1
    assert "failed after 2 retries" in result.state_update["escalation_reasons"][0]


@pytest.mark.asyncio
async def test_validatorNode_completenessFailure_escalatesImmediately_evenAtIterationZero():
    """A required domain the context planner never queried can't be fixed by
    retrying decision_node with the same findings — escalate on the first
    attempt rather than spend a retry that's guaranteed to fail identically."""
    provider = MockProvider(queue=[MockResponse(value=_passing_llm_output())])
    deps = _deps(provider, max_agent_iterations=2)
    state = _state(retrieved_evidence=[_retrieved()], iteration=0)
    state["intent"] = state["intent"].model_copy(
        update={"required_domains": ["security", "data_residency"]}
    )

    result = await validator_node(state, deps)

    validation_result = result.state_update["validation_result"]
    assert validation_result.recommended_action == "ESCALATE"
    assert result.state_update["iteration"] == 0
    assert any("never queried" in r for r in result.state_update["escalation_reasons"])


@pytest.mark.asyncio
async def test_validatorNode_flaggedRetrievedEvidence_raisesInjectionFinding():
    provider = MockProvider(queue=[MockResponse(value=_passing_llm_output())])
    deps = _deps(provider)
    state = _state(retrieved_evidence=[_retrieved(is_flagged=True)])

    result = await validator_node(state, deps)

    injection_findings = result.state_update["injection_findings"]
    assert len(injection_findings) == 1
    assert str(_CHUNK_1) in injection_findings[0].evidence_ids


@pytest.mark.asyncio
async def test_validatorNode_noFlaggedEvidence_noInjectionFindingAdded():
    provider = MockProvider(queue=[MockResponse(value=_passing_llm_output())])
    deps = _deps(provider)
    state = _state(retrieved_evidence=[_retrieved(is_flagged=False)])

    result = await validator_node(state, deps)

    assert result.state_update["injection_findings"] == []
