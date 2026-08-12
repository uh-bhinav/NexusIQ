-- Written exclusively by the AI service (.claude/rules/database.md, ADR-004);
-- Java owns the migration but never writes rows here. workspace_id is
-- denormalised from documents so vector queries filter without a join
-- (docs/DATABASE/SCHEMA.md).
CREATE TABLE document_chunks (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id       UUID NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    workspace_id      UUID NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    chunk_index       INT NOT NULL,
    content           TEXT NOT NULL,
    embedding         vector(384),
    token_count       INT,
    page_number       INT,
    section           VARCHAR(500),
    subsection        VARCHAR(500),
    heading_path      TEXT[],
    embedding_model   VARCHAR(100) NOT NULL,
    embedding_version INT NOT NULL,
    is_flagged        BOOLEAN NOT NULL DEFAULT false,
    flag_reason       VARCHAR(100),
    metadata          JSONB,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_document_chunks_document_index UNIQUE (document_id, chunk_index)
);

CREATE INDEX idx_document_chunks_document ON document_chunks (document_id, chunk_index);
CREATE INDEX idx_document_chunks_ws_flagged ON document_chunks (workspace_id, is_flagged);

-- HNSW cosine index (.claude/rules/database.md). Built now, on an empty table —
-- bulk backfill only happens via seeding/ingestion in this phase, so there is no
-- "build after bulk load" step to sequence separately yet.
CREATE INDEX idx_document_chunks_embedding
    ON document_chunks USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
