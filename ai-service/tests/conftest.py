"""Test-only: load the shared root .env so DB-touching tests can reach the
real local dev Postgres with its actual generated credentials. The app itself
never does this (see app/config.py docstring) — this is purely local test
convenience, mirroring how a developer running `make up` already has that
Postgres available on the host-exposed port.

The root .env is written for Docker Compose (POSTGRES_HOST=postgres,
POSTGRES_PORT=5432 — container-network values), so after loading it we
explicitly point tests at the host-exposed address instead
(POSTGRES_HOST=localhost, POSTGRES_EXPOSED_PORT) — same convention
spring-api's tests use against Testcontainers, just against the real local
stack here rather than an ephemeral container.
"""

import os
import uuid
from pathlib import Path

from dotenv import load_dotenv
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

_ROOT_ENV_FILE = Path(__file__).resolve().parents[2] / ".env"
if _ROOT_ENV_FILE.exists():
    load_dotenv(_ROOT_ENV_FILE, override=False)
    os.environ["POSTGRES_HOST"] = "localhost"
    os.environ["POSTGRES_PORT"] = os.environ.get("POSTGRES_EXPOSED_PORT", "5434")
    # Same container-vs-host mismatch as Postgres: .env's KAFKA_BOOTSTRAP_SERVERS
    # is kafka:9092 (container-network); tests run on the host.
    os.environ["KAFKA_BOOTSTRAP_SERVERS"] = os.environ.get(
        "KAFKA_EXTERNAL_BOOTSTRAP", "localhost:29093"
    )
    # Same again for Redis (.env's REDIS_HOST=redis) — confirmed empirically:
    # omitting this override broke every cache test with "nodename nor servname
    # provided" against the container hostname.
    os.environ["REDIS_HOST"] = "localhost"
    os.environ["REDIS_PORT"] = os.environ.get("REDIS_EXPOSED_PORT", "6380")
    # Same again for the OTel collector (.env's OTEL_EXPORTER_OTLP_ENDPOINT is
    # http://otel-collector:4317, container-network) — Phase 5's graph tests
    # construct a real tracer, which otherwise retries against an
    # unresolvable hostname on every test run (noisy, and would eventually
    # hang test teardown waiting on export retries).
    os.environ["OTEL_EXPORTER_OTLP_ENDPOINT"] = (
        f"http://localhost:{os.environ.get('OTEL_GRPC_PORT', '4327')}"
    )


async def seed_workspace_and_document(session: AsyncSession) -> tuple[uuid.UUID, uuid.UUID]:
    """Shared by any test that needs a real FK-valid workspace/document row
    (Java owns these tables; Python only ever reads/references them)."""
    user_id = uuid.uuid4()
    workspace_id = uuid.uuid4()
    document_id = uuid.uuid4()

    await session.execute(
        text(
            "INSERT INTO users (id, email, name, password_hash, role) "
            "VALUES (:id, :email, 'Store Test', 'x', 'ADMIN')"
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
            "INSERT INTO documents (id, workspace_id, name, document_type, uploaded_by) "
            "VALUES (:id, :workspace_id, 'doc', 'OTHER', :uploaded_by)"
        ),
        {"id": document_id, "workspace_id": workspace_id, "uploaded_by": user_id},
    )
    await session.flush()
    return workspace_id, document_id
