-- Phase 7: the human approval queue (ADR-006, docs/DATABASE/SCHEMA.md).
-- One row per decision run that the deterministic gate in Java (ApprovalGate)
-- escalated. Written only by Java — the gate reads decision.completed's
-- validated fields, never a model-produced boolean
-- (.claude/rules/architecture.md).

CREATE TABLE approvals (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id      UUID NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    decision_run_id   UUID NOT NULL UNIQUE REFERENCES decision_runs (id) ON DELETE CASCADE,
    status            VARCHAR(10) NOT NULL DEFAULT 'PENDING'
                      CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    reasons           TEXT[],
    requested_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_by       UUID REFERENCES users (id),
    resolved_at       TIMESTAMPTZ,
    resolution_notes  TEXT
);

-- Tenant-scoped table: every index leads with workspace_id
-- (.claude/rules/database.md). Powers the approval queue view
-- (list PENDING approvals for a workspace, newest first).
CREATE INDEX idx_approvals_ws_status ON approvals (workspace_id, status, requested_at DESC);
