-- Document metadata. Phase 1 provides CRUD + workspace-scoped authorization only;
-- actual file upload, storage and ingestion arrive in Phase 2 (ADR-003, ADR-004).
-- storage_path/checksum/chunk_count stay nullable until that phase populates them.
CREATE TABLE documents (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id          UUID NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    name                  VARCHAR(500) NOT NULL,
    original_filename     VARCHAR(500),
    document_type         VARCHAR(30) NOT NULL
                          CHECK (document_type IN (
                              'SECURITY_POLICY', 'COMPLIANCE_POLICY', 'PROCUREMENT_POLICY',
                              'ARCHITECTURE_STANDARD', 'VENDOR_DOCUMENT',
                              'HISTORICAL_DECISION', 'INCIDENT_REPORT', 'OTHER'
                          )),
    version               INT NOT NULL DEFAULT 1,
    supersedes_document_id UUID REFERENCES documents (id),
    is_current            BOOLEAN NOT NULL DEFAULT true,
    storage_path          VARCHAR(1000),
    content_type          VARCHAR(255),
    size_bytes            BIGINT,
    checksum_sha256       VARCHAR(64),
    status                VARCHAR(20) NOT NULL DEFAULT 'UPLOADED'
                          CHECK (status IN ('UPLOADED', 'PROCESSING', 'READY', 'FAILED')),
    failure_reason        TEXT,
    chunk_count           INT NOT NULL DEFAULT 0,
    uploaded_by           UUID NOT NULL REFERENCES users (id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_documents_ws_status ON documents (workspace_id, status);
CREATE INDEX idx_documents_ws_created ON documents (workspace_id, created_at DESC);
CREATE INDEX idx_documents_ws_type_current ON documents (workspace_id, document_type, is_current);

-- Knowledge source metadata (trust level, provenance). Not populated until the
-- retrieval work in Phase 3, but owned by the same migration as its parent table.
CREATE TABLE knowledge_sources (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id      UUID NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    source_type      VARCHAR(50) NOT NULL,
    source_reference VARCHAR(500),
    trust_level      VARCHAR(20) NOT NULL DEFAULT 'SUPPORTING'
                     CHECK (trust_level IN ('AUTHORITATIVE', 'SUPPORTING', 'INFORMATIONAL')),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_knowledge_sources_document ON knowledge_sources (document_id);

-- Append-only audit trail (ADR-001, .claude/rules/database.md). workspace_id is
-- nullable for global events (e.g. a failed login before any workspace context
-- exists).
CREATE TABLE audit_events (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id  UUID REFERENCES workspaces (id) ON DELETE CASCADE,
    actor_id      UUID REFERENCES users (id),
    event_type    VARCHAR(100) NOT NULL,
    resource_type VARCHAR(50) NOT NULL,
    resource_id   UUID,
    correlation_id UUID,
    metadata      JSONB,
    ip_address    VARCHAR(45),
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_ws_time ON audit_events (workspace_id, occurred_at DESC);
CREATE INDEX idx_audit_resource ON audit_events (resource_type, resource_id);

-- Enforced in the database, not just by convention: an audit trail that can be
-- edited after the fact is not an audit trail.
CREATE FUNCTION prevent_audit_event_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only: % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_events_no_update
    BEFORE UPDATE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_event_mutation();

CREATE TRIGGER trg_audit_events_no_delete
    BEFORE DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_event_mutation();
