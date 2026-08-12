"""Internal-only endpoints for isolated agent testing (roadmap Phase 4:
"POST /internal/agents/intent for isolated testing"). Real orchestration
across agents arrives in Phase 5 (LangGraph); until then each agent is
independently callable and independently testable."""

from fastapi import APIRouter, Depends
from pydantic import BaseModel

from app.agents.intent import analyze_intent
from app.api.internal_auth import verify_internal_token
from app.config import get_settings
from app.llm.factory import get_model_provider
from app.models.agents import IntentAnalysis

router = APIRouter(
    prefix="/internal/agents",
    tags=["internal", "agents"],
    dependencies=[Depends(verify_internal_token)],
)


class IntentRequest(BaseModel):
    question: str


class IntentResponse(BaseModel):
    result: IntentAnalysis
    model: str
    input_tokens: int
    output_tokens: int
    latency_ms: int
    estimated_cost_usd: float
    repaired: bool


@router.post("/intent", response_model=IntentResponse)
async def intent_endpoint(request: IntentRequest) -> IntentResponse:
    settings = get_settings()
    provider = get_model_provider(settings)
    result = await analyze_intent(request.question, provider=provider, model=settings.llm_model)
    return IntentResponse(
        result=result.value,
        model=result.model,
        input_tokens=result.input_tokens,
        output_tokens=result.output_tokens,
        latency_ms=result.latency_ms,
        estimated_cost_usd=result.estimated_cost_usd,
        repaired=result.repaired,
    )
