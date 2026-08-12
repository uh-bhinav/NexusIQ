"""Policy Analyst (docs/AI/AGENTS.md #4, parallel with risk_analyzer). One
PolicyFinding per applicable policy, UNKNOWN when the evidence doesn't say."""

from app.graph.evidence import resolve_evidence_labels
from app.llm.provider import ModelProvider, ModelResult
from app.models.agents import PolicyAnalysisOutput, PolicyFinding
from app.models.retrieval import ContextAssembly
from app.prompts.compose import compose_prompt


def _system_prompt() -> str:
    return compose_prompt("policy_analyst_v1.md")


async def analyze_policy(
    question: str,
    context: ContextAssembly,
    *,
    provider: ModelProvider,
    model: str,
) -> tuple[list[PolicyFinding], ModelResult[PolicyAnalysisOutput]]:
    system = _system_prompt()
    user = f"<user_question>\n{question}\n</user_question>\n\n{context.evidence_block}"
    result = await provider.generate_structured(
        system=system, user=user, schema=PolicyAnalysisOutput, model=model, temperature=0.1
    )

    resolved_findings = [
        finding.model_copy(
            update={
                "evidence_ids": resolve_evidence_labels(
                    finding.evidence_ids, context.included_chunk_ids
                )
            }
        )
        for finding in result.value.findings
    ]
    return resolved_findings, result
