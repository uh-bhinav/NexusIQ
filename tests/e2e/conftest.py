"""Fixtures for the cross-service E2E suite (docs/IMPLEMENTATION/ROADMAP.md
Phase 10: "E2E test: upload -> ingest -> decide -> validate -> escalate ->
approve -> audit"). See test_full_spine.py's module docstring for the exact
preconditions this suite requires — unlike the Java/Python unit+integration
suites (Testcontainers-managed, fully hermetic), this one drives two already
-running, real application processes plus the shared docker-compose infra, so
it cannot spin its own dependencies up the way `mvn verify`/`pytest` do.
"""

import os

import httpx
import pytest

API_BASE_URL = os.environ.get("E2E_API_BASE_URL", "http://localhost:8180/api/v1")
AI_SERVICE_HEALTH_URL = os.environ.get("E2E_AI_SERVICE_HEALTH_URL", "http://localhost:8000/ready")


def _reachable(url: str) -> bool:
    try:
        resp = httpx.get(url, timeout=3.0)
        return resp.status_code < 500
    except httpx.HTTPError:
        return False


@pytest.fixture(scope="session", autouse=True)
def _require_live_stack():
    spring_health_url = API_BASE_URL.rsplit("/api/v1", 1)[0] + "/actuator/health"
    spring_up = _reachable(spring_health_url)
    ai_up = _reachable(AI_SERVICE_HEALTH_URL)
    if not (spring_up and ai_up):
        pytest.skip(
            "tests/e2e requires spring-api and ai-service already running against the "
            "shared docker-compose stack (this suite does not start them itself — see "
            "test_full_spine.py's module docstring and docs/OPERATIONS/LOCAL_DEV.md's "
            f"'E2E testing' section for the exact startup commands). spring-api reachable="
            f"{spring_up} ({spring_health_url}), ai-service reachable={ai_up} "
            f"({AI_SERVICE_HEALTH_URL})."
        )


@pytest.fixture
def api_base_url() -> str:
    return API_BASE_URL
