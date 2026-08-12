import pytest
from fastapi.testclient import TestClient

from app.config import get_settings
from app.main import app

client = TestClient(app)


@pytest.fixture
def mock_llm_provider(monkeypatch):
    """Routes the endpoint at LLM_PROVIDER=mock for this test only.
    get_settings() is process-wide @lru_cache'd (app/config.py), so the
    cache must be cleared both before (to pick up the env override) and
    after (so later tests see real settings again, not a stale mock
    config leaking across tests in the same process)."""
    monkeypatch.setenv("LLM_PROVIDER", "mock")
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


def test_intentEndpoint_missingToken_returns401():
    response = client.post("/internal/agents/intent", json={"question": "anything"})
    assert response.status_code == 401


def test_intentEndpoint_wrongToken_returns401():
    response = client.post(
        "/internal/agents/intent",
        json={"question": "anything"},
        headers={"X-Internal-Service-Token": "not-the-real-token"},
    )
    assert response.status_code == 401


def test_intentEndpoint_correctTokenMockProvider_returnsClassifiedIntent(mock_llm_provider):
    settings = get_settings()
    response = client.post(
        "/internal/agents/intent",
        json={"question": "Should Vendor Alpha be approved for EU production?"},
        headers={"X-Internal-Service-Token": settings.internal_service_token},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["result"]["decision_type"] == "vendor_approval"
    assert body["result"]["entities"] == ["Vendor Alpha"]
    assert body["result"]["jurisdiction"] == "EU"
    assert body["result"]["environment"] == "production"
    assert "security" in body["result"]["required_domains"]
    assert "data_residency" in body["result"]["required_domains"]
    assert "procurement" in body["result"]["required_domains"]
    assert body["model"] == "mock-gemini-2.5-flash"
    assert body["repaired"] is False
