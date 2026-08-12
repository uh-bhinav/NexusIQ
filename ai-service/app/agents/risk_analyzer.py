"""Risk Analyzer (docs/AI/AGENTS.md #5, parallel with policy_analyst). Missing
critical information raises risk; it never lowers it."""

from app.graph.evidence import resolve_evidence_labels
from app.llm.provider import ModelProvider, ModelResult
from app.models.agents import RiskAssessment
from app.models.retrieval import ContextAssembly
from app.prompts.compose import compose_prompt


def _system_prompt() -> str:
    return compose_prompt("risk_analyzer_v1.md")


async def analyze_risk(
    question: str,
    context: ContextAssembly,
    *,
    provider: ModelProvider,
    model: str,
) -> tuple[RiskAssessment, ModelResult[RiskAssessment]]:
    system = _system_prompt()
    user = f"<user_question>\n{question}\n</user_question>\n\n{context.evidence_block}"
    result = await provider.generate_structured(
        system=system, user=user, schema=RiskAssessment, model=model, temperature=0.1
    )

    resolved = result.value.model_copy(
        update={
            "factors": [
                factor.model_copy(
                    update={
                        "evidence_ids": resolve_evidence_labels(
                            factor.evidence_ids, context.included_chunk_ids
                        )
                    }
                )
                for factor in result.value.factors
            ]
        }
    )
    return resolved, result
