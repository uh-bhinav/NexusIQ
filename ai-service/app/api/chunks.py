"""Internal-only (.claude/rules/ai-service.md): requires
X-Internal-Service-Token — Java's DocumentStreamController-equivalent
(`DocumentController`'s chunk-fetch endpoint) is the only real caller,
after it has already authorised the workspace/document. `document_chunks`
is Python-owned (.claude/rules/database.md); this is the one place that
table's content is exposed for citation resolution.
"""

import uuid

from fastapi import APIRouter, Depends, Query
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.internal_auth import verify_internal_token
from app.db.models import DocumentChunk
from app.db.session import get_session
from app.models.chunks import ChunkPageResponse, ChunkResponse

router = APIRouter(
    prefix="/internal", tags=["internal"], dependencies=[Depends(verify_internal_token)]
)


async def _list_chunks(
    session: AsyncSession, workspace_id: uuid.UUID, document_id: uuid.UUID, page: int, size: int
) -> ChunkPageResponse:
    # workspace_id in the predicate even though document_id alone would
    # already narrow correctly — defense in depth
    # (.claude/rules/architecture.md: "still scopes every query by
    # workspace_id"), matching every other query in this service.
    base = select(DocumentChunk).where(
        DocumentChunk.workspace_id == workspace_id, DocumentChunk.document_id == document_id
    )

    total = (
        await session.execute(select(func.count()).select_from(base.subquery()))
    ).scalar_one()

    rows = (
        await session.execute(
            base.order_by(DocumentChunk.chunk_index.asc()).offset(page * size).limit(size)
        )
    ).scalars()

    content = [
        ChunkResponse(
            id=row.id,
            document_id=row.document_id,
            chunk_index=row.chunk_index,
            content=row.content,
            section=row.section,
            subsection=row.subsection,
            page_number=row.page_number,
            is_flagged=row.is_flagged,
        )
        for row in rows
    ]
    total_pages = (total + size - 1) // size if size else 0
    return ChunkPageResponse(
        content=content, page=page, size=size, total_elements=total, total_pages=total_pages
    )


@router.get("/documents/{document_id}/chunks", response_model=ChunkPageResponse)
async def list_document_chunks(
    document_id: uuid.UUID,
    workspace_id: uuid.UUID = Query(...),  # noqa: B008 - FastAPI's own idiom, not a mutable-default bug
    page: int = Query(0, ge=0),  # noqa: B008
    size: int = Query(20, ge=1, le=100),  # noqa: B008
) -> ChunkPageResponse:
    async with get_session() as session:
        return await _list_chunks(session, workspace_id, document_id, page, size)
