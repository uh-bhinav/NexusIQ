import asyncio
import uuid

from fastapi.testclient import TestClient
from sqlalchemy import text

from app.config import get_settings
from app.db.session import get_session
from app.main import app

client = TestClient(app)


async def _seed_document_with_chunks(chunk_count: int) -> tuple[uuid.UUID, uuid.UUID]:
    user_id = uuid.uuid4()
    workspace_id = uuid.uuid4()
    document_id = uuid.uuid4()

    async with get_session() as session:
        await session.execute(
            text(
                "INSERT INTO users (id, email, name, password_hash, role) "
                "VALUES (:id, :email, 'API Test', 'x', 'ADMIN')"
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
        await session.execute(
            text(
                "INSERT INTO documents "
                "(id, workspace_id, name, document_type, status, uploaded_by) "
                "VALUES (:id, :workspace_id, 'Security Policy', 'SECURITY_POLICY', "
                " 'READY', :uploaded_by)"
            ),
            {"id": document_id, "workspace_id": workspace_id, "uploaded_by": user_id},
        )
        for i in range(chunk_count):
            await session.execute(
                text(
                    "INSERT INTO document_chunks "
                    "(document_id, workspace_id, chunk_index, content, section, page_number, "
                    " embedding_model, embedding_version) "
                    "VALUES (:document_id, :workspace_id, :chunk_index, :content, :section, "
                    " :page_number, 'BAAI/bge-small-en-v1.5', 1)"
                ),
                {
                    "document_id": document_id,
                    "workspace_id": workspace_id,
                    "chunk_index": i,
                    "content": f"Chunk content {i}",
                    "section": f"Section {i}",
                    "page_number": i + 1,
                },
            )
        await session.commit()
    return workspace_id, document_id


def test_listChunks_missingToken_returns401():
    response = client.get(
        f"/internal/documents/{uuid.uuid4()}/chunks", params={"workspace_id": str(uuid.uuid4())}
    )
    assert response.status_code == 401


def test_listChunks_wrongToken_returns401():
    response = client.get(
        f"/internal/documents/{uuid.uuid4()}/chunks",
        params={"workspace_id": str(uuid.uuid4())},
        headers={"X-Internal-Service-Token": "not-the-real-token"},
    )
    assert response.status_code == 401


def test_listChunks_correctToken_returnsChunksInReadingOrder():
    # Sync test calling the sync TestClient, seeding via asyncio.run() —
    # same reasoning as test_search.py's identical pattern (avoids
    # corrupting the session-scoped event loop across tests).
    workspace_id, document_id = asyncio.run(_seed_document_with_chunks(3))
    settings = get_settings()

    response = client.get(
        f"/internal/documents/{document_id}/chunks",
        params={"workspace_id": str(workspace_id)},
        headers={"X-Internal-Service-Token": settings.internal_service_token},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["total_elements"] == 3
    assert [c["chunk_index"] for c in body["content"]] == [0, 1, 2]
    assert body["content"][0]["content"] == "Chunk content 0"
    assert body["content"][1]["section"] == "Section 1"
    assert body["content"][2]["page_number"] == 3


def test_listChunks_wrongWorkspaceId_returnsEmpty():
    """Defense-in-depth check: even though document_id alone would already
    narrow correctly, a mismatched workspace_id must still yield nothing —
    proves the workspace_id predicate is real, not decorative."""
    _workspace_id, document_id = asyncio.run(_seed_document_with_chunks(2))
    settings = get_settings()

    response = client.get(
        f"/internal/documents/{document_id}/chunks",
        params={"workspace_id": str(uuid.uuid4())},
        headers={"X-Internal-Service-Token": settings.internal_service_token},
    )

    assert response.status_code == 200
    assert response.json()["total_elements"] == 0


def test_listChunks_pagination():
    workspace_id, document_id = asyncio.run(_seed_document_with_chunks(5))
    settings = get_settings()

    response = client.get(
        f"/internal/documents/{document_id}/chunks",
        params={"workspace_id": str(workspace_id), "page": 1, "size": 2},
        headers={"X-Internal-Service-Token": settings.internal_service_token},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["total_elements"] == 5
    assert body["total_pages"] == 3
    assert [c["chunk_index"] for c in body["content"]] == [2, 3]
