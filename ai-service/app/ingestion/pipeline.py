"""Orchestrates extract -> chunk -> embed -> store for one uploaded document.
Called by the document.uploaded consumer (app/messaging/consumer.py).
"""

import asyncio
import uuid
from pathlib import Path

from sqlalchemy.ext.asyncio import AsyncSession

from app.concurrency import INFERENCE_EXECUTOR
from app.config import Settings
from app.embeddings.provider import get_embedding_provider
from app.ingestion.chunk import chunk_document
from app.ingestion.extract import ExtractionError, extract
from app.ingestion.store import bulk_insert_chunks
from app.messaging.envelope import DocumentUploadedPayload


class IngestionError(Exception):
    """Non-retryable: a corrupt/unsupported file, or a file that legitimately
    can't be processed. The consumer turns this into document.failed directly
    — retrying would fail identically every time
    (.claude/rules/architecture.md)."""


_FORMAT_BY_CONTENT_TYPE = {
    "application/pdf": "pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document": "docx",
    "text/plain": "txt",
    "text/markdown": "md",
}
_FORMAT_BY_EXTENSION = {"pdf": "pdf", "docx": "docx", "txt": "txt", "md": "md", "markdown": "md"}


def _resolve_format(payload: DocumentUploadedPayload) -> str:
    fmt = _FORMAT_BY_CONTENT_TYPE.get(payload.content_type or "")
    if fmt:
        return fmt

    name = (payload.original_filename or "").lower()
    if "." in name:
        fmt = _FORMAT_BY_EXTENSION.get(name.rsplit(".", 1)[-1])
        if fmt:
            return fmt

    raise IngestionError(
        f"Cannot determine document format (content_type={payload.content_type!r}, "
        f"filename={payload.original_filename!r})"
    )


async def run_ingestion_pipeline(
    session: AsyncSession,
    settings: Settings,
    workspace_id: uuid.UUID,
    payload: DocumentUploadedPayload,
) -> int:
    """Returns the chunk count written. Raises IngestionError for anything
    non-retryable; any other exception is treated as transient by the caller.
    """
    document_format = _resolve_format(payload)
    file_path = Path(settings.storage_local_path) / payload.storage_path

    if not file_path.is_file():
        raise IngestionError(f"Stored file not found: {file_path}")

    try:
        extracted = extract(file_path, document_format)
    except ExtractionError as e:
        raise IngestionError(str(e)) from e

    chunks = chunk_document(extracted)
    if not chunks:
        raise IngestionError("No chunks produced from extracted content")

    provider = get_embedding_provider(settings)
    # Off the event loop, and serialized through INFERENCE_EXECUTOR — see
    # retrieval/orchestrator.py's identical fix (app/concurrency.py) for why
    # a synchronous, CPU-bound embed() call left on the loop, or run
    # concurrently with another native inference call, is a real problem.
    embeddings = await asyncio.get_running_loop().run_in_executor(
        INFERENCE_EXECUTOR, provider.embed, [c.content for c in chunks]
    )

    return await bulk_insert_chunks(
        session,
        document_id=payload.document_id,
        workspace_id=workspace_id,
        chunks=chunks,
        embeddings=embeddings,
        embedding_model=provider.model_name,
        embedding_version=settings.embedding_model_version,
    )
