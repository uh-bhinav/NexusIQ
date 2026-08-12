"""Validation metrics (docs/AI/GUARDRAILS.md "Metrics": validation_failure_rate,
citation_invalid_rate, evidence_coverage distribution, escalation_rate,
injection_detected_count). Structured log lines for now — same "values now,
aggregation backend later" convention as retrieval/metrics.py; Phase 8 wires
a real Prometheus/Grafana sink without touching call sites.
"""

import logging
import uuid

from pydantic import BaseModel

from app.models.agents import ValidationCheck
from app.observability.metrics import record_validation_failure

logger = logging.getLogger("nexusiq.guardrails.metrics")


class ValidationMetrics(BaseModel):
    decision_id: uuid.UUID
    iteration: int
    passed: bool
    recommended_action: str
    evidence_coverage: float
    checks: list[ValidationCheck]
    injection_findings_count: int


def record_validation(metrics: ValidationMetrics) -> None:
    failed_checks = [c.check for c in metrics.checks if not c.passed]
    for check in failed_checks:
        record_validation_failure(check)
    logger.info(
        "validation decision_id=%s iteration=%d passed=%s action=%s coverage=%.2f "
        "failed_checks=%s injection_findings=%d",
        metrics.decision_id,
        metrics.iteration,
        metrics.passed,
        metrics.recommended_action,
        metrics.evidence_coverage,
        failed_checks,
        metrics.injection_findings_count,
    )
