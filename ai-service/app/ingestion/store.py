"""Writes chunks to Postgres — the only table this service writes rows into
besides processed_events (.claude/rules/database.md). One `add_all` + flush
per document: SQLAlchemy 2.x's insertmanyvalues batches this into a small
number of round trips, not one INSERT per row.
"""

import uuid

from sqlalchemy.ext.asyncio import AsyncSession

from app.db.models import DocumentChunk
from app.guardrails.injection import scan_for_injection
from app.models.ingestion import Chunk


async def bulk_insert_chunks(
    session: AsyncSession,
    *,
    document_id: uuid.UUID,
    workspace_id: uuid.UUID,
    chunks: list[Chunk],
    embeddings: list[list[float]],
    embedding_model: str,
    embedding_version: int,
) -> int:
    if len(chunks) != len(embeddings):
        raise ValueError(f"chunk/embedding count mismatch: {len(chunks)} vs {len(embeddings)}")

    rows = []
    for chunk, embedding in zip(chunks, embeddings, strict=True):
        flag_reason = scan_for_injection(chunk.content)
        rows.append(
            DocumentChunk(
                document_id=document_id,
                workspace_id=workspace_id,
                chunk_index=chunk.chunk_index,
                content=chunk.content,
                embedding=embedding,
                token_count=chunk.token_count,
                page_number=chunk.page_number,
                section=chunk.section,
                subsection=chunk.subsection,
                heading_path=chunk.heading_path,
                embedding_model=embedding_model,
                embedding_version=embedding_version,
                is_flagged=flag_reason is not None,
                flag_reason=flag_reason,
            )
        )

    session.add_all(rows)
    await session.flush()
    return len(rows)
