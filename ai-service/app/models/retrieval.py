"""Pydantic models for the retrieval pipeline (docs/AI/RAG.md "Result contract").
`trust_level` defaults to `SUPPORTING` when a document has no `knowledge_sources`
row — nothing populates that table yet (Phase 1's note: "not populated until
Phase 3" was aspirational; no ingestion step extracts source provenance), so
this is an honest LEFT JOIN default, not fabricated data.
"""

import uuid
from datetime import datetime

from pydantic import BaseModel, Field


class SearchFilters(BaseModel):
    # document_type doubles as "policy category" — our schema has no narrower
    # category concept than document_type (SECURITY_POLICY, COMPLIANCE_POLICY, ...).
    document_types: list[str] | None = None
    created_after: datetime | None = None
    created_before: datetime | None = None


class SearchRequest(BaseModel):
    workspace_id: uuid.UUID
    query: str
    filters: SearchFilters = Field(default_factory=SearchFilters)
    top_k: int | None = None


class RetrievalResult(BaseModel):
    chunk_id: uuid.UUID
    document_id: uuid.UUID
    document_name: str
    document_type: str
    document_version: int
    is_current: bool
    section: str | None = None
    subsection: str | None = None
    page_number: int | None = None
    content: str
    similarity_score: float
    rerank_score: float | None = None
    trust_level: str
    is_flagged: bool
    citation_reference: str
    # Set by agents/retrieval.py (Phase 5) when this result came from a
    # ContextPlan task rather than a standalone search call — optional so
    # Phase 3's direct-search callers are unaffected.
    source_domain: str | None = None


class SearchResponse(BaseModel):
    results: list[RetrievalResult]
    query: str
    cached: bool = False
    latency_ms: float


class ContextAssembly(BaseModel):
    """The <retrieved_evidence> block (docs/AI/CONTEXT_ENGINEERING.md) — one
    piece of a reasoning node's full prompt, not the whole prompt. The
    surrounding SYSTEM RULES/OUTPUT SCHEMA/etc. structure belongs to the agent
    nodes built in Phase 4/5."""

    evidence_block: str
    included_chunk_ids: list[uuid.UUID]
    dropped_count: int
