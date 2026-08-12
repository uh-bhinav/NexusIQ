"""Context Planner (docs/AI/AGENTS.md #2). Decides what evidence must be
retrieved so retrieval is targeted rather than indiscriminate."""

import json

from app.llm.provider import ModelProvider, ModelResult
from app.models.agents import ContextPlan, IntentAnalysis
from app.prompts.compose import compose_prompt


def _system_prompt() -> str:
    return compose_prompt("context_planner_v1.md")


async def plan_context(
    question: str, intent: IntentAnalysis, *, provider: ModelProvider, model: str
) -> ModelResult[ContextPlan]:
    system = _system_prompt()
    user = (
        f"<user_question>\n{question}\n</user_question>\n\n"
        f"<intent_analysis>\n{json.dumps(intent.model_dump())}\n</intent_analysis>"
    )
    return await provider.generate_structured(
        system=system, user=user, schema=ContextPlan, model=model, temperature=0.1
    )
