import uuid

import pytest
from sqlalchemy import text

from app.config import get_settings
from app.db.session import get_session
from app.embeddings.local import LocalEmbeddingProvider
from app.models.retrieval import SearchFilters
from app.retrieval.search import vector_search

_MODEL = "BAAI/bge-small-en-v1.5"
_provider = LocalEmbeddingProvider(_MODEL, batch_size=8)


async def _seed_document(
    session,
    workspace_id: uuid.UUID,
    user_id: uuid.UUID,
    *,
    name: str,
    document_type: str = "SECURITY_POLICY",
    status: str = "READY",
    version: int = 1,
    is_current: bool = True,
) -> uuid.UUID:
    document_id = uuid.uuid4()
    await session.execute(
        text(
            "INSERT INTO documents "
            "(id, workspace_id, name, document_type, status, version, is_current, uploaded_by) "
            "VALUES (:id, :workspace_id, :name, :document_type, :status, :version, "
            ":is_current, :uploaded_by)"
        ),
        {
            "id": document_id,
            "workspace_id": workspace_id,
            "name": name,
            "document_type": document_type,
            "status": status,
            "version": version,
            "is_current": is_current,
            "uploaded_by": user_id,
        },
    )
    return document_id


async def _seed_chunk(
    session,
    document_id: uuid.UUID,
    workspace_id: uuid.UUID,
    *,
    content: str,
    section: str | None = None,
    page_number: int | None = None,
    chunk_index: int = 0,
    is_flagged: bool = False,
) -> None:
    [embedding] = _provider.embed([content])
    await session.execute(
        text(
            "INSERT INTO document_chunks "
            "(document_id, workspace_id, chunk_index, content, embedding, section, "
            " page_number, is_flagged, embedding_model, embedding_version) "
            "VALUES (:document_id, :workspace_id, :chunk_index, :content, "
            " CAST(:embedding AS vector), :section, :page_number, :is_flagged, :model, 1)"
        ),
        {
            "document_id": document_id,
            "workspace_id": workspace_id,
            "chunk_index": chunk_index,
            "content": content,
            "embedding": str(embedding),
            "section": section,
            "page_number": page_number,
            "is_flagged": is_flagged,
            "model": _MODEL,
        },
    )


async def _seed_user_and_workspace(session) -> tuple[uuid.UUID, uuid.UUID]:
    user_id = uuid.uuid4()
    workspace_id = uuid.uuid4()
    await session.execute(
        text(
            "INSERT INTO users (id, email, name, password_hash, role) "
            "VALUES (:id, :email, 'Retrieval Test', 'x', 'ADMIN')"
        ),
        {"id": user_id, "email": f"{user_id}@example.com"},
    )
    await session.execute(
        text(
            "INSERT INTO workspaces (id, name, slug, created_by) "
            "VALUES (:id, 'ws', :slug, :created_by)"
        ),
        {"id": workspace_id, "slug": f"ws-{workspace_id}", "created_by": user_id},
    )
    return user_id, workspace_id


@pytest.mark.asyncio
async def test_vectorSearch_topResultIsTheRelevantPolicy():
    async with get_session() as session:
        user_id, workspace_id = await _seed_user_and_workspace(session)
        doc_id = await _seed_document(session, workspace_id, user_id, name="Data Residency Policy")
        other_id = await _seed_document(session, workspace_id, user_id, name="Cafeteria Menu")

        await _seed_chunk(
            session,
            doc_id,
            workspace_id,
            content="Personal data of EU data subjects must be stored within the EEA.",
            section="3.1 Data Residency",
        )
        await _seed_chunk(
            session,
            other_id,
            workspace_id,
            content="The cafeteria menu changes every Tuesday and Thursday.",
            section="Menu",
        )
        await session.commit()

        [query_embedding] = _provider.embed(["What are the EU data residency requirements?"])
        settings = get_settings()
        results = await vector_search(
            session, settings, workspace_id, query_embedding, SearchFilters()
        )

        assert results[0].document_name == "Data Residency Policy"
        assert results[0].section == "3.1 Data Residency"


@pytest.mark.asyncio
async def test_vectorSearch_neverReturnsAnotherWorkspacesChunks():
    async with get_session() as session:
        user_a, workspace_a = await _seed_user_and_workspace(session)
        user_b, workspace_b = await _seed_user_and_workspace(session)

        doc_a = await _seed_document(session, workspace_a, user_a, name="Workspace A Policy")
        doc_b = await _seed_document(session, workspace_b, user_b, name="Workspace B Policy")

        shared_text = "All vendors must comply with the data residency policy for EU customers."
        await _seed_chunk(session, doc_a, workspace_a, content=shared_text)
        await _seed_chunk(session, doc_b, workspace_b, content=shared_text)
        await session.commit()

        [query_embedding] = _provider.embed(["data residency policy for EU customers"])
        settings = get_settings()
        results = await vector_search(
            session, settings, workspace_a, query_embedding, SearchFilters()
        )

        assert len(results) == 1
        assert results[0].document_name == "Workspace A Policy"


@pytest.mark.asyncio
async def test_vectorSearch_excludesResultsBelowMinimumSimilarity():
    async with get_session() as session:
        user_id, workspace_id = await _seed_user_and_workspace(session)
        doc_id = await _seed_document(session, workspace_id, user_id, name="Cafeteria Menu")
        await _seed_chunk(
            session, doc_id, workspace_id, content="The cafeteria menu changes weekly."
        )
        await session.commit()

        [query_embedding] = _provider.embed(["What are the EU data residency requirements?"])
        settings = get_settings().model_copy(update={"retrieval_min_similarity": 0.9})
        results = await vector_search(
            session, settings, workspace_id, query_embedding, SearchFilters()
        )

        assert results == []


@pytest.mark.asyncio
async def test_vectorSearch_prefersCurrentVersionOverSupersededOnNearTie():
    async with get_session() as session:
        user_id, workspace_id = await _seed_user_and_workspace(session)
        current_doc = await _seed_document(
            session, workspace_id, user_id, name="Security Standard v2", version=2, is_current=True
        )
        superseded_doc = await _seed_document(
            session, workspace_id, user_id, name="Security Standard v1", version=1, is_current=False
        )

        identical_text = "Vendors must hold a current ISO 27001 certification or equivalent."
        await _seed_chunk(session, current_doc, workspace_id, content=identical_text)
        await _seed_chunk(session, superseded_doc, workspace_id, content=identical_text)
        await session.commit()

        [query_embedding] = _provider.embed(["Does the vendor need ISO 27001 certification?"])
        settings = get_settings()
        results = await vector_search(
            session, settings, workspace_id, query_embedding, SearchFilters()
        )

        assert results[0].document_name == "Security Standard v2"
        assert results[0].is_current is True


@pytest.mark.asyncio
async def test_vectorSearch_filtersByDocumentType():
    async with get_session() as session:
        user_id, workspace_id = await _seed_user_and_workspace(session)
        policy_doc = await _seed_document(
            session, workspace_id, user_id, name="Security Policy", document_type="SECURITY_POLICY"
        )
        vendor_doc = await _seed_document(
            session, workspace_id, user_id, name="Vendor Report", document_type="VENDOR_DOCUMENT"
        )

        text_content = "This document discusses vendor security certification requirements."
        await _seed_chunk(session, policy_doc, workspace_id, content=text_content)
        await _seed_chunk(session, vendor_doc, workspace_id, content=text_content)
        await session.commit()

        [query_embedding] = _provider.embed(["vendor security certification requirements"])
        settings = get_settings()
        results = await vector_search(
            session,
            settings,
            workspace_id,
            query_embedding,
            SearchFilters(document_types=["SECURITY_POLICY"]),
        )

        assert len(results) == 1
        assert results[0].document_name == "Security Policy"


@pytest.mark.asyncio
async def test_vectorSearch_excludesDocumentsNotYetReady():
    async with get_session() as session:
        user_id, workspace_id = await _seed_user_and_workspace(session)
        doc_id = await _seed_document(
            session, workspace_id, user_id, name="Still Processing", status="PROCESSING"
        )
        await _seed_chunk(session, doc_id, workspace_id, content="Some policy text about vendors.")
        await session.commit()

        [query_embedding] = _provider.embed(["policy text about vendors"])
        settings = get_settings()
        results = await vector_search(
            session, settings, workspace_id, query_embedding, SearchFilters()
        )

        assert results == []
