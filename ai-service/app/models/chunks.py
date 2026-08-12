"""Document-chunk browsing (docs/API/API_DESIGN.md:
`GET /workspaces/{id}/documents/{documentId}/chunks` — "paginated (citation
resolution)"). Distinct from app/models/retrieval.py's RetrievalResult: this
lists a document's chunks in reading order for browsing/citation-jump, not a
similarity-ranked search result — no similarity_score/rerank_score.
"""

import uuid

from pydantic import BaseModel


class ChunkResponse(BaseModel):
    id: uuid.UUID
    document_id: uuid.UUID
    chunk_index: int
    content: str
    section: str | None = None
    subsection: str | None = None
    page_number: int | None = None
    is_flagged: bool


class ChunkPageResponse(BaseModel):
    content: list[ChunkResponse]
    page: int
    size: int
    total_elements: int
    total_pages: int
