"""Decision Agent (docs/AI/AGENTS.md #6). Synthesises policy findings and
risk into one recommendation. No retrieval access — reasons only over what
policy_analyst and risk_analyzer produced."""

import json

from app.llm.provider import ModelProvider, ModelResult
from app.models.agents import PolicyFinding, Recommendation, RiskAssessment
from app.prompts.compose import compose_prompt


def _system_prompt() -> str:
    return compose_prompt("decision_v1.md")


def _known_evidence_ids(findings: list[PolicyFinding], risk: RiskAssessment) -> set[str]:
    ids = {chunk_id for finding in findings for chunk_id in finding.evidence_ids}
    ids.update(chunk_id for factor in risk.factors for chunk_id in factor.evidence_ids)
    return ids


async def synthesize_decision(
    question: str,
    findings: list[PolicyFinding],
    risk: RiskAssessment,
    *,
    provider: ModelProvider,
    model: str,
    validation_feedback: str | None = None,
) -> tuple[Recommendation, ModelResult[Recommendation]]:
    known_ids = _known_evidence_ids(findings, risk)

    system = _system_prompt()
    user = (
        f"<user_question>\n{question}\n</user_question>\n\n"
        f"<policy_findings>\n{json.dumps([f.model_dump() for f in findings])}\n"
        "</policy_findings>\n\n"
        f"<risk_assessment>\n{json.dumps(risk.model_dump())}\n</risk_assessment>"
    )
    if validation_feedback:
        # Phase 6's validator rejected a prior attempt — the retry edge
        # (graph/builder.py) sends the same findings/risk back here rather
        # than re-retrieving, so without this the model would likely just
        # reproduce the same rejected output. Appended, not prepended: the
        # rules above still take precedence over what is, after all,
        # feedback on a mistake.
        user += (
            "\n\n<validator_feedback>\n"
            "Your previous recommendation for this same question failed validation. "
            f"Address these specific problems in your new recommendation:\n{validation_feedback}"
            "\n</validator_feedback>"
        )
    result = await provider.generate_structured(
        system=system, user=user, schema=Recommendation, model=model, temperature=0.1
    )

    # Drops anything not verbatim-present in the findings/risk evidence_ids
    # (a mistyped or invented id) — same safety floor as
    # graph/evidence.py::resolve_evidence_labels, Phase 6's validator is the
    # real fix.
    resolved = result.value.model_copy(
        update={
            "key_evidence_ids": [
                eid for eid in result.value.key_evidence_ids if eid in known_ids
            ]
        }
    )
    return resolved, result
