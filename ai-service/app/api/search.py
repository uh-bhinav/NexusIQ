"""Internal-only endpoint (.claude/rules/ai-service.md): requires
X-Internal-Service-Token, matching Java's outbound call. The AI service is not
internet-facing and does not authenticate end users — Java has already
authorised the `workspace_id` before this is ever called
(.claude/rules/architecture.md "defence in depth").
"""

from fastapi import APIRouter, Depends

from app.api.internal_auth import verify_internal_token
from app.config import get_settings
from app.db.session import get_session
from app.models.retrieval import SearchRequest, SearchResponse
from app.retrieval.orchestrator import search as run_search

router = APIRouter(
    prefix="/internal", tags=["internal"], dependencies=[Depends(verify_internal_token)]
)


@router.post("/search", response_model=SearchResponse)
async def search_endpoint(request: SearchRequest) -> SearchResponse:
    settings = get_settings()
    async with get_session() as session:
        return await run_search(
            session, request.workspace_id, request.query, request.filters, settings
        )
