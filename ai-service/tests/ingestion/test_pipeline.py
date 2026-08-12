import shutil
import uuid
from pathlib import Path

import pytest
from sqlalchemy import text

from app.config import Settings
from app.db.session import get_session
from app.ingestion.pipeline import IngestionError, run_ingestion_pipeline
from app.messaging.envelope import DocumentUploadedPayload
from tests.conftest import seed_workspace_and_document

FIXTURES = Path(__file__).resolve().parents[1] / "fixtures"


def _settings_with_storage(storage_dir: Path) -> Settings:
    return Settings(storage_local_path=str(storage_dir))


def _place_fixture(storage_dir: Path, workspace_id: uuid.UUID, filename: str) -> str:
    """Copies a fixture into {storage_dir}/{workspace_id}/{filename}, mirroring
    where spring-api's LocalDocumentStorage actually writes uploads, and
    returns the storage_path a document.uploaded payload would carry."""
    target_dir = storage_dir / str(workspace_id)
    target_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy(FIXTURES / filename, target_dir / filename)
    return f"{workspace_id}/{filename}"


@pytest.mark.asyncio
async def test_runIngestionPipeline_extractsChunksEmbedsAndStores(tmp_path):
    settings = _settings_with_storage(tmp_path)

    async with get_session() as session:
        workspace_id, document_id = await seed_workspace_and_document(session)
        storage_path = _place_fixture(tmp_path, workspace_id, "sample_policy.md")

        payload = DocumentUploadedPayload(
            document_id=document_id,
            document_type="SECURITY_POLICY",
            storage_path=storage_path,
            content_type="text/markdown",
            size_bytes=100,
            checksum_sha256="deadbeef",
            original_filename="sample_policy.md",
        )

        chunk_count = await run_ingestion_pipeline(session, settings, workspace_id, payload)
        await session.commit()

        assert chunk_count > 0

        result = await session.execute(
            text(
                "SELECT section, embedding_model, embedding_version "
                "FROM document_chunks WHERE document_id = :document_id ORDER BY chunk_index"
            ),
            {"document_id": document_id},
        )
        rows = result.all()
        assert len(rows) == chunk_count
        assert rows[0].section == "Vendor Security Standard"
        assert rows[0].embedding_version == settings.embedding_model_version


@pytest.mark.asyncio
async def test_runIngestionPipeline_missingFile_raisesIngestionError(tmp_path):
    settings = _settings_with_storage(tmp_path)

    async with get_session() as session:
        workspace_id, document_id = await seed_workspace_and_document(session)

        payload = DocumentUploadedPayload(
            document_id=document_id,
            document_type="SECURITY_POLICY",
            storage_path=f"{workspace_id}/does-not-exist.md",
            content_type="text/markdown",
            size_bytes=100,
            checksum_sha256="deadbeef",
            original_filename="does-not-exist.md",
        )

        with pytest.raises(IngestionError, match="not found"):
            await run_ingestion_pipeline(session, settings, workspace_id, payload)


@pytest.mark.asyncio
async def test_runIngestionPipeline_corruptFile_raisesIngestionError(tmp_path):
    settings = _settings_with_storage(tmp_path)

    async with get_session() as session:
        workspace_id, document_id = await seed_workspace_and_document(session)
        storage_path = _place_fixture(tmp_path, workspace_id, "corrupt.pdf")

        payload = DocumentUploadedPayload(
            document_id=document_id,
            document_type="SECURITY_POLICY",
            storage_path=storage_path,
            content_type="application/pdf",
            size_bytes=100,
            checksum_sha256="deadbeef",
            original_filename="corrupt.pdf",
        )

        with pytest.raises(IngestionError):
            await run_ingestion_pipeline(session, settings, workspace_id, payload)


@pytest.mark.asyncio
async def test_runIngestionPipeline_unresolvableFormat_raisesIngestionError(tmp_path):
    settings = _settings_with_storage(tmp_path)

    async with get_session() as session:
        workspace_id, document_id = await seed_workspace_and_document(session)

        payload = DocumentUploadedPayload(
            document_id=document_id,
            document_type="SECURITY_POLICY",
            storage_path=f"{workspace_id}/mystery",
            content_type="application/octet-stream",
            size_bytes=100,
            checksum_sha256="deadbeef",
            original_filename=None,
        )

        with pytest.raises(IngestionError, match="format"):
            await run_ingestion_pipeline(session, settings, workspace_id, payload)
