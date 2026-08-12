"""Stage 1+2 of the retrieval pipeline (docs/AI/RAG.md): workspace-scoped
cosine vector search with metadata filtering. `workspace_id` in the SQL
predicate is non-negotiable (.claude/rules/database.md) — never filtered in
Python after the fact.
"""

import uuid

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings
from app.db.models import Document, DocumentChunk, KnowledgeSource
from app.models.retrieval import RetrievalResult, SearchFilters

_DEFAULT_TRUST_LEVEL = "SUPPORTING"
# Similarity penalty applied to superseded documents so that, for otherwise
# near-tied relevance, the current version ranks first (roadmap Phase 3
# acceptance criterion 5) — without hiding a genuinely much-more-relevant
# superseded chunk (conflict detection needs those visible too).
_SUPERSEDED_PENALTY = 0.05


def _citation_reference(document_name: str, section: str | None, page_number: int | None) -> str:
    parts = [document_name]
    if section:
        parts.append(f"§{section}")
    if page_number is not None:
        parts.append(f"p.{page_number}")
    return " ".join(parts)


async def vector_search(
    session: AsyncSession,
    settings: Settings,
    workspace_id: uuid.UUID,
    query_embedding: list[float],
    filters: SearchFilters,
    top_k: int | None = None,
) -> list[RetrievalResult]:
    """Over-fetches `top_k` (default RETRIEVAL_TOP_K) so later stages
    (threshold, rerank) have something to work with. Returns results ordered
    by version-adjusted similarity, already thresholded — callers doing their
    own re-ranking should use `rerank_score` where present instead of re-sorting.
    """
    limit = top_k or settings.retrieval_top_k
    distance_col = DocumentChunk.embedding.cosine_distance(query_embedding).label("distance")

    stmt = (
        select(
            DocumentChunk.id.label("chunk_id"),
            DocumentChunk.document_id,
            DocumentChunk.content,
            DocumentChunk.page_number,
            DocumentChunk.section,
            DocumentChunk.subsection,
            DocumentChunk.is_flagged,
            Document.name.label("document_name"),
            Document.document_type,
            Document.version.label("document_version"),
            Document.is_current,
            KnowledgeSource.trust_level,
            distance_col,
        )
        .join(Document, DocumentChunk.document_id == Document.id)
        .outerjoin(KnowledgeSource, KnowledgeSource.document_id == Document.id)
        .where(DocumentChunk.workspace_id == workspace_id, Document.status == "READY")
    )

    if filters.document_types:
        stmt = stmt.where(Document.document_type.in_(filters.document_types))
    if filters.created_after:
        stmt = stmt.where(Document.created_at >= filters.created_after)
    if filters.created_before:
        stmt = stmt.where(Document.created_at <= filters.created_before)

    stmt = stmt.order_by(distance_col.asc()).limit(limit)

    rows = (await session.execute(stmt)).all()

    results = []
    for row in rows:
        similarity_score = 1.0 - float(row.distance)
        if similarity_score < settings.retrieval_min_similarity:
            continue
        adjusted_score = similarity_score - (0.0 if row.is_current else _SUPERSEDED_PENALTY)
        results.append(
            (
                adjusted_score,
                RetrievalResult(
                    chunk_id=row.chunk_id,
                    document_id=row.document_id,
                    document_name=row.document_name,
                    document_type=row.document_type,
                    document_version=row.document_version,
                    is_current=row.is_current,
                    section=row.section,
                    subsection=row.subsection,
                    page_number=row.page_number,
                    content=row.content,
                    similarity_score=similarity_score,
                    trust_level=row.trust_level or _DEFAULT_TRUST_LEVEL,
                    is_flagged=row.is_flagged,
                    citation_reference=_citation_reference(
                        row.document_name, row.section, row.page_number
                    ),
                ),
            )
        )

    results.sort(key=lambda pair: pair[0], reverse=True)
    return [result for _, result in results]
