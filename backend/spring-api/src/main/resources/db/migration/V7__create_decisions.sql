-- Phase 5: decision lifecycle tables (docs/DATABASE/SCHEMA.md, ADR-004).
-- Java owns these end to end; the AI service only ever emits Kafka events —
-- Java persists them, keeping one writer per aggregate and the audit trail
-- authoritative (.claude/rules/architecture.md).

CREATE TABLE decision_requests (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id   UUID NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    requested_by   UUID NOT NULL REFERENCES users (id),
    title          VARCHAR(500) NOT NULL,
    question       TEXT NOT NULL,
    decision_type  VARCHAR(50),
    priority       VARCHAR(10) NOT NULL DEFAULT 'NORMAL'
                   CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    status         VARCHAR(30) NOT NULL DEFAULT 'PENDING'
                   CHECK (status IN (
                       'PENDING', 'PROCESSING', 'WAITING_FOR_APPROVAL',
                       'APPROVED', 'REJECTED', 'FAILED'
                   )),
    correlation_id UUID,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_requests_ws_status ON decision_requests (workspace_id, status, created_at DESC);

-- Immutable once terminal (COMPLETED/FAILED) — enforced at the application
-- layer (.claude/rules/database.md: "decision_runs and agent_executions are
-- immutable once completed — write terminal state once"), not a DB trigger,
-- since a run legitimately transitions through several non-terminal states
-- first (QUEUED -> RUNNING -> ...).
CREATE TABLE decision_runs (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    decision_request_id UUID NOT NULL REFERENCES decision_requests (id) ON DELETE CASCADE,
    workflow_version    VARCHAR(20) NOT NULL,
    prompt_version      VARCHAR(20),
    llm_model           VARCHAR(100),
    embedding_model     VARCHAR(200),
    status              VARCHAR(20) NOT NULL DEFAULT 'QUEUED'
                        CHECK (status IN ('QUEUED', 'RUNNING', 'SUSPENDED', 'COMPLETED', 'FAILED')),
    confidence          NUMERIC(4, 3) CHECK (confidence IS NULL OR (confidence BETWEEN 0 AND 1)),
    total_input_tokens  INT NOT NULL DEFAULT 0,
    total_output_tokens INT NOT NULL DEFAULT 0,
    estimated_cost_usd  NUMERIC(10, 6) NOT NULL DEFAULT 0,
    latency_ms          INT,
    iteration_count     INT NOT NULL DEFAULT 0,
    failure_reason      TEXT,
    started_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ
);

CREATE INDEX idx_runs_request ON decision_runs (decision_request_id);

CREATE TABLE agent_executions (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    decision_run_id  UUID NOT NULL REFERENCES decision_runs (id) ON DELETE CASCADE,
    agent_name       VARCHAR(50) NOT NULL,
    sequence_index   INT NOT NULL,
    status           VARCHAR(20) NOT NULL
                     CHECK (status IN ('SUCCESS', 'FAILED', 'SKIPPED', 'RETRIED')),
    model            VARCHAR(100),
    input_tokens     INT NOT NULL DEFAULT 0,
    output_tokens    INT NOT NULL DEFAULT 0,
    latency_ms       INT NOT NULL,
    estimated_cost_usd NUMERIC(10, 6) NOT NULL DEFAULT 0,
    output           JSONB,
    error            TEXT,
    trace_id         VARCHAR(64),
    started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_agent_exec_run ON agent_executions (decision_run_id, sequence_index);
