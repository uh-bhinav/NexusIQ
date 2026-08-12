import pytest
from pydantic import ValidationError

from app.models.agents import IntentAnalysis

_VALID_KWARGS = dict(
    decision_type="vendor_approval",
    entities=["Vendor Alpha"],
    jurisdiction="EU",
    environment="production",
    required_domains=["security"],
    missing_information=[],
    confidence=0.9,
)


def test_intentAnalysis_validPayload_constructs():
    intent = IntentAnalysis(**_VALID_KWARGS)
    assert intent.decision_type == "vendor_approval"


def test_intentAnalysis_invalidDecisionType_rejected():
    with pytest.raises(ValidationError):
        IntentAnalysis(**{**_VALID_KWARGS, "decision_type": "not_a_real_type"})


def test_intentAnalysis_invalidRequiredDomain_rejected():
    with pytest.raises(ValidationError):
        IntentAnalysis(**{**_VALID_KWARGS, "required_domains": ["not_a_real_domain"]})


def test_intentAnalysis_missingRequiredField_rejected():
    kwargs = dict(_VALID_KWARGS)
    del kwargs["decision_type"]
    with pytest.raises(ValidationError):
        IntentAnalysis(**kwargs)


def test_intentAnalysis_confidenceAboveOne_rejected():
    with pytest.raises(ValidationError):
        IntentAnalysis(**{**_VALID_KWARGS, "confidence": 1.5})


def test_intentAnalysis_confidenceBelowZero_rejected():
    with pytest.raises(ValidationError):
        IntentAnalysis(**{**_VALID_KWARGS, "confidence": -0.1})


def test_intentAnalysis_jurisdictionOmitted_defaultsToNone():
    kwargs = dict(_VALID_KWARGS)
    del kwargs["jurisdiction"]
    intent = IntentAnalysis(**kwargs)
    assert intent.jurisdiction is None


def test_intentAnalysis_malformedJson_rejected():
    with pytest.raises(ValidationError):
        IntentAnalysis.model_validate_json('{"decision_type": "vendor_approval"')  # truncated
