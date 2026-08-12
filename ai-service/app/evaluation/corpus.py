"""Seeds a fixed evaluation workspace with the full docs/sample-enterprise/
corpus, using the real extract -> chunk -> embed -> store pipeline
(app/ingestion/pipeline.py) rather than a hand-typed paragraph — the harness
needs realistic document structure (headings, sections, multiple chunks per
document) for retrieval metrics to mean anything.

Writes directly via SQLAlchemy (documents + document_chunks), the same
pattern already used by ai-service's own tests
(tests/graph/test_end_to_end.py::_seed_security_policy_workspace) — bypasses
spring-api/Kafka entirely since this is a one-time setup step for a harness
run, not something needing the real upload API's authz/audit trail.
"""

import asyncio
import uuid
from collections.abc import Callable
from contextlib import AbstractAsyncContextManager
from pathlib import Path

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from app.concurrency import INFERENCE_EXECUTOR
from app.config import Settings
from app.embeddings.provider import get_embedding_provider
from app.ingestion.chunk import chunk_document
from app.ingestion.extract import extract
from app.ingestion.store import bulk_insert_chunks

SessionFactory = Callable[[], AbstractAsyncContextManager[AsyncSession]]

_CORPUS_ROOT = Path(__file__).resolve().parents[3] / "docs" / "sample-enterprise"

# subdirectory -> DocumentType (backend/spring-api/.../document/entity/DocumentType.java)
_DOCUMENT_TYPE_BY_DIR = {
    "security": "SECURITY_POLICY",
    "compliance": "COMPLIANCE_POLICY",
    "procurement": "PROCUREMENT_POLICY",
    "architecture": "ARCHITECTURE_STANDARD",
    "vendors": "VENDOR_DOCUMENT",
    "historical": "HISTORICAL_DECISION",
    "incidents": "INCIDENT_REPORT",
}

# slug -> (version, is_current, supersedes_slug). Every other document is
# version 1 / current / no predecessor by default.
_VERSIONING = {
    "security-policy-v1": (1, False, None),
    "security-policy-v2": (2, True, "security-policy-v1"),
}


class CorpusSeedResult:
    def __init__(self, workspace_id: uuid.UUID, document_ids_by_slug: dict[str, uuid.UUID]):
        self.workspace_id = workspace_id
        self.document_ids_by_slug = document_ids_by_slug


def _iter_corpus_files() -> list[Path]:
    return sorted(p for p in _CORPUS_ROOT.rglob("*.md") if p.parent.name in _DOCUMENT_TYPE_BY_DIR)


async def seed_eval_corpus(session_factory: SessionFactory, settings: Settings) -> CorpusSeedResult:
    """session_factory: app.db.session.get_session (passed in, not imported
    directly, so callers can swap in a per-run session as needed)."""
    files = _iter_corpus_files()
    if not files:
        raise RuntimeError(f"No corpus documents found under {_CORPUS_ROOT}")

    user_id = uuid.uuid4()
    workspace_id = uuid.uuid4()
    embedding_provider = get_embedding_provider(settings)
    document_ids_by_slug: dict[str, uuid.UUID] = {}

    async with session_factory() as session:
        await session.execute(
            text(
                "INSERT INTO users (id, email, name, password_hash, role) "
                "VALUES (:id, :email, 'Evaluation Harness', 'x', 'ADMIN')"
            ),
            {"id": user_id, "email": f"eval-{user_id}@example.com"},
        )
        await session.execute(
            text(
                "INSERT INTO workspaces (id, name, slug, created_by) "
                "VALUES (:id, 'eval', :slug, :created_by)"
            ),
            {"id": workspace_id, "slug": f"eval-{workspace_id}", "created_by": user_id},
        )

        for path in files:
            slug = path.stem
            document_type = _DOCUMENT_TYPE_BY_DIR[path.parent.name]
            version, is_current, _supersedes_slug = _VERSIONING.get(slug, (1, True, None))
            document_id = uuid.uuid4()
            document_ids_by_slug[slug] = document_id

            await session.execute(
                text(
                    "INSERT INTO documents "
                    "(id, workspace_id, name, document_type, version, is_current, status, "
                    " uploaded_by) "
                    "VALUES (:id, :workspace_id, :name, :document_type, :version, :is_current, "
                    " 'READY', :uploaded_by)"
                ),
                {
                    "id": document_id,
                    "workspace_id": workspace_id,
                    "name": slug,
                    "document_type": document_type,
                    "version": version,
                    "is_current": is_current,
                    "uploaded_by": user_id,
                },
            )

            extracted = extract(path, "md")
            chunks = chunk_document(extracted)
            embeddings = await asyncio.get_running_loop().run_in_executor(
                INFERENCE_EXECUTOR, embedding_provider.embed, [c.content for c in chunks]
            )
            await bulk_insert_chunks(
                session,
                document_id=document_id,
                workspace_id=workspace_id,
                chunks=chunks,
                embeddings=embeddings,
                embedding_model=embedding_provider.model_name,
                embedding_version=settings.embedding_model_version,
            )

        # supersedes_document_id needs both rows to exist first (FK), so it's
        # a second pass rather than trying to interleave with the insert loop.
        for slug, (_version, _is_current, supersedes_slug) in _VERSIONING.items():
            if supersedes_slug is None or slug not in document_ids_by_slug:
                continue
            await session.execute(
                text("UPDATE documents SET supersedes_document_id = :old_id WHERE id = :new_id"),
                {
                    "old_id": document_ids_by_slug[supersedes_slug],
                    "new_id": document_ids_by_slug[slug],
                },
            )

        await session.commit()

    return CorpusSeedResult(workspace_id=workspace_id, document_ids_by_slug=document_ids_by_slug)
