from app.graph.builder import _route_after_intent, _route_after_validator
from app.graph.state import DecisionState
from app.models.agents import ValidationResult


def _state(
    decision_type: str | None = None, validation_result: ValidationResult | None = None
) -> DecisionState:
    return DecisionState(
        decision_id="d",
        workspace_id="w",
        correlation_id="c",
        workflow_version="v1",
        question="q",
        decision_type=decision_type,
        intent=None,
        context_plan=None,
        retrieved_evidence=[],
        policy_findings=[],
        risk_analysis=None,
        recommendation=None,
        validation_result=validation_result,
        injection_findings=[],
        validation_feedback=None,
        iteration=0,
        requires_human_approval=False,
        escalation_reasons=[],
        total_input_tokens=0,
        total_output_tokens=0,
        estimated_cost_usd=0.0,
        errors=[],
    )


def _validation_result(action: str) -> ValidationResult:
    return ValidationResult(
        passed=action == "ACCEPT", checks=[], evidence_coverage=1.0, recommended_action=action
    )


def test_routeAfterIntent_unsupported_routesToUnsupportedTerminal():
    assert _route_after_intent(_state("unsupported")) == "unsupported"


def test_routeAfterIntent_vendorApproval_continuesToContextPlanner():
    assert _route_after_intent(_state("vendor_approval")) == "continue"


def test_routeAfterIntent_policyQuestion_continues():
    assert _route_after_intent(_state("policy_question")) == "continue"


def test_routeAfterValidator_accept_routesToApprovalRouter():
    assert (
        _route_after_validator(_state(validation_result=_validation_result("ACCEPT")))
        == "approval_router"
    )


def test_routeAfterValidator_retry_routesBackToDecision():
    assert (
        _route_after_validator(_state(validation_result=_validation_result("RETRY"))) == "retry"
    )


def test_routeAfterValidator_escalate_routesToApprovalRouter():
    assert (
        _route_after_validator(_state(validation_result=_validation_result("ESCALATE")))
        == "approval_router"
    )
