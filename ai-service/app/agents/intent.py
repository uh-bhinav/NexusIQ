"""Intent Analyzer — the first agent (docs/AI/AGENTS.md #1). Understands the
request; nothing else. No retrieval, no policy evaluation, no LangGraph yet
(that's Phase 5) — this module is directly callable in isolation, per the
Phase 4 roadmap's `POST /internal/agents/intent`."""

from app.llm.provider import ModelProvider, ModelResult
from app.models.agents import IntentAnalysis
from app.prompts.compose import compose_prompt


def _system_prompt() -> str:
    return compose_prompt("intent_v1.md")


async def analyze_intent(
    question: str, *, provider: ModelProvider, model: str
) -> ModelResult[IntentAnalysis]:
    system = _system_prompt()
    # The question is the user's own direct input, not retrieved document
    # content — the standing injection clause still applies (defense in
    # depth against a question phrased as an instruction), but this is not
    # the <retrieved_evidence> case that clause is primarily about.
    user = f"<user_question>\n{question}\n</user_question>"
    return await provider.generate_structured(
        system=system, user=user, schema=IntentAnalysis, model=model, temperature=0.1
    )
