import asyncio
import uuid

from fastapi.testclient import TestClient
from sqlalchemy import text

from app.config import get_settings
from app.db.session import get_session
from app.embeddings.local import LocalEmbeddingProvider
from app.main import app

client = TestClient(app)
_provider = LocalEmbeddingProvider("BAAI/bge-small-en-v1.5", batch_size=8)


async def _seed_searchable_workspace() -> uuid.UUID:
    user_id = uuid.uuid4()
    workspace_id = uuid.uuid4()
    document_id = uuid.uuid4()
    content = "Vendors must hold a current ISO 27001 certification or equivalent."

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
        [embedding] = _provider.embed([content])
        await session.execute(
            text(
                "INSERT INTO document_chunks "
                "(document_id, workspace_id, chunk_index, content, embedding, "
                " embedding_model, embedding_version) "
                "VALUES (:document_id, :workspace_id, 0, :content, "
                " CAST(:embedding AS vector), :model, 1)"
            ),
            {
                "document_id": document_id,
                "workspace_id": workspace_id,
                "content": content,
                "embedding": str(embedding),
                "model": "BAAI/bge-small-en-v1.5",
            },
        )
        await session.commit()
    return workspace_id


def test_search_missingToken_returns401():
    response = client.post(
        "/internal/search", json={"workspace_id": str(uuid.uuid4()), "query": "anything"}
    )
    assert response.status_code == 401


def test_search_wrongToken_returns401():
    response = client.post(
        "/internal/search",
        json={"workspace_id": str(uuid.uuid4()), "query": "anything"},
        headers={"X-Internal-Service-Token": "not-the-real-token"},
    )
    assert response.status_code == 401


def test_search_correctToken_returnsRankedResults():
    # Deliberately sync, not `async def`: calling TestClient's sync .post()
    # (which spins up its own event loop internally) from inside an already-
    # running pytest-asyncio async test corrupts the session-scoped loop for
    # every test that runs afterward — confirmed empirically ("Event loop is
    # closed" failures in unrelated later tests when this was `async def`
    # awaiting the seed helper directly). asyncio.run() here creates and
    # tears down its own temporary loop just for seeding, safely, because
    # app/db/session.py's engine uses NullPool (no connection persists across
    # loops to begin with).
    workspace_id = asyncio.run(_seed_searchable_workspace())
    settings = get_settings()

    response = client.post(
        "/internal/search",
        json={"workspace_id": str(workspace_id), "query": "Does the vendor need ISO 27001?"},
        headers={"X-Internal-Service-Token": settings.internal_service_token},
    )

    assert response.status_code == 200
    body = response.json()
    assert len(body["results"]) == 1
    assert "ISO 27001" in body["results"][0]["content"]
    assert body["results"][0]["similarity_score"] > 0
