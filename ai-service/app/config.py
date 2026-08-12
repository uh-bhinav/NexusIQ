"""All configuration from env, no literals (.claude/rules/ai-service.md).

Deliberately does NOT auto-load the shared root `.env`: that file is written
from Docker Compose's perspective (`POSTGRES_HOST=postgres`,
`KAFKA_BOOTSTRAP_SERVERS=kafka:9092` — container-network hostnames). Compose
itself turns those into real process environment variables inside the
container via its `environment:` block, which pydantic-settings picks up
normally with no help needed. For host execution (`cd ai-service && uv run
uvicorn ...`, docs/OPERATIONS/LOCAL_DEV.md), the field defaults below already
point at the host-exposed addresses — the same convention spring-api's
application.yml uses for its own datasource/kafka defaults. Auto-loading the
shared .env here would silently break host execution by picking up the
container hostnames instead.
"""

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(extra="ignore")

    nexusiq_env: str = "local"
    log_level: str = "INFO"

    # --- Postgres (system of record; this service writes only document_chunks
    # and processed_events, reads documents/knowledge_sources/workspaces) ---
    postgres_host: str = "localhost"
    postgres_port: int = 5434
    postgres_db: str = "nexusiq"
    postgres_user: str = "nexusiq"
    postgres_password: str = "nexusiq"

    # --- Kafka ---
    kafka_bootstrap_servers: str = "localhost:29093"
    kafka_consumer_group_ai: str = "nexusiq-ai-service"

    # --- Internal service auth (Java -> AI service calls only) ---
    internal_service_token: str = ""

    ai_service_port: int = 8000

    # --- Embeddings (ADR-009: local, zero-cost) ---
    embedding_provider: str = "local"
    embedding_model: str = "BAAI/bge-small-en-v1.5"
    embedding_dimensions: int = 384
    embedding_model_version: int = 1
    embedding_batch_size: int = 32

    # --- Document storage (shared filesystem with spring-api). Default must
    # match spring-api's own default exactly (application.yml) — both an
    # absolute path, since each service resolves a relative default against
    # its own working directory otherwise (backend/spring-api vs ai-service),
    # which would silently point them at two different directories. ---
    storage_local_path: str = "/tmp/nexusiq-documents"
    max_upload_mb: int = 25

    # --- Redis (cache only — never source of truth; host-exposed port,
    # same host-execution convention as postgres_port/kafka_bootstrap_servers
    # above) ---
    redis_host: str = "localhost"
    redis_port: int = 6380

    # --- Retrieval tuning (docs/AI/RAG.md) ---
    retrieval_top_k: int = 20
    rerank_top_n: int = 8
    retrieval_min_similarity: float = 0.30
    retrieval_cache_ttl_seconds: int = 300

    # --- Reranking ---
    reranker_enabled: bool = True
    reranker_model: str = "BAAI/bge-reranker-base"

    # --- Context assembly token budget (docs/AI/CONTEXT_ENGINEERING.md) ---
    context_token_budget: int = 4000

    # --- LLM provider abstraction (docs/AI/MODEL_STRATEGY.md, ADR-008).
    # Model IDs verified live against the Gemini API's models.list() and
    # pricing.google.dev on 2026-08-11 — both still current, not deprecated,
    # and gemini-2.5-flash is the most cost-efficient choice for the fast
    # tier (cheaper than the newer gemini-3.5-flash at $0.30/$2.50 vs
    # $1.50/$9.00 per 1M tokens). See llm/pricing.py for the full table. ---
    llm_provider: str = "gemini"
    llm_model: str = "gemini-2.5-flash"
    # gemini-2.5-pro was pinned at Phase 4 scaffold time per start.spring.io-style live
    # verification, but Google retired it for this API key/project ("no longer available
    # to new users") before Phase 5 shipped — confirmed via a live generateContent call
    # returning 404, not from docs. gemini-3.6-flash is the newest model still reachable
    # by this key, verified live 2026-08-11.
    llm_model_heavy: str = "gemini-3.6-flash"
    llm_api_key: str = ""
    llm_temperature: float = 0.1
    llm_timeout_seconds: int = 60
    llm_max_retries: int = 2

    # --- Decision workflow (Phase 5, docs/AI/ARCHITECTURE.md) ---
    kafka_consumer_group_decisions: str = "nexusiq-ai-service-decisions"
    max_workflow_cost_usd: float = 0.50
    max_workflow_tokens: int = 200_000
    max_agent_iterations: int = 2
    context_planner_max_tasks: int = 8

    # --- Validation & guardrails (Phase 6, docs/AI/GUARDRAILS.md) ---
    hitl_min_evidence_coverage: float = 0.6
    workflow_timeout_seconds: int = 300

    # --- Human approval (Phase 7, ADR-006). approval_router_node mirrors
    # spring-api's ApprovalGate exactly — same threshold names/defaults — to
    # decide whether to suspend the graph via interrupt(). Java's gate,
    # reading the same decision.completed payload independently, remains
    # authoritative for the actual approval record. ---
    hitl_escalate_on_risk: str = "HIGH"
    hitl_min_confidence: float = 0.75

    # --- Observability (ADR-007). Full collector pipeline + dashboards are
    # Phase 8; the collector already accepts and logs real OTLP spans today
    # (infrastructure/docker/otel/collector-config.yaml: "Phase 0: prove the
    # pipeline works"), which Phase 5 needs to prove parallel node execution
    # via span overlap (roadmap acceptance criterion 6). ---
    otel_exporter_otlp_endpoint: str = "http://localhost:4327"
    otel_service_name: str = "nexusiq-ai-service"

    @property
    def async_database_url(self) -> str:
        return (
            f"postgresql+asyncpg://{self.postgres_user}:{self.postgres_password}"
            f"@{self.postgres_host}:{self.postgres_port}/{self.postgres_db}"
        )

    @property
    def redis_url(self) -> str:
        return f"redis://{self.redis_host}:{self.redis_port}/0"

    @property
    def langgraph_database_url(self) -> str:
        """psycopg (not asyncpg) connection string for the LangGraph
        checkpointer, with search_path pointed at the dedicated `langgraph`
        schema — the one documented exception to Flyway owning every table
        (.claude/rules/database.md, ADR-005). Flyway never touches this
        schema; the checkpointer creates its own tables in it via .setup()."""
        return (
            f"postgresql://{self.postgres_user}:{self.postgres_password}"
            f"@{self.postgres_host}:{self.postgres_port}/{self.postgres_db}"
            "?options=-csearch_path%3Dlanggraph"
        )


@lru_cache
def get_settings() -> Settings:
    return Settings()
