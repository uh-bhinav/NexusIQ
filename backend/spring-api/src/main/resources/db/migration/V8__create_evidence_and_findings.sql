-- Phase 5: evidence, findings and the final decision (docs/DATABASE/SCHEMA.md).
-- evidence/findings are written once, from decision.completed — Python never
-- writes these tables directly (.claude/rules/architecture.md).

CREATE TABLE evidence (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    decision_run_id    UUID NOT NULL REFERENCES decision_runs (id) ON DELETE CASCADE,
    document_id        UUID NOT NULL REFERENCES documents (id),
    chunk_id           UUID NOT NULL REFERENCES document_chunks (id),
    claim              TEXT,
    evidence_text      TEXT NOT NULL,
    relevance_score    NUMERIC(5, 4),
    citation_reference VARCHAR(500),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_evidence_run ON evidence (decision_run_id);

CREATE TABLE findings (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    decision_run_id UUID NOT NULL REFERENCES decision_runs (id) ON DELETE CASCADE,
    category        VARCHAR(30) NOT NULL
                    CHECK (category IN ('POLICY', 'RISK', 'GAP', 'CONFLICT', 'PROMPT_INJECTION_ATTEMPT')),
    policy_name     VARCHAR(500),
    status          VARCHAR(30)
                    CHECK (status IS NULL OR status IN (
                        'SATISFIED', 'PARTIALLY_SATISFIED', 'VIOLATED', 'UNKNOWN'
                    )),
    severity        VARCHAR(10)
                    CHECK (severity IS NULL OR severity IN ('INFO', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    title           VARCHAR(500) NOT NULL,
    description     TEXT NOT NULL,
    confidence      NUMERIC(4, 3) NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_findings_run ON findings (decision_run_id);

-- A finding may cite several pieces of evidence, and the same evidence may
-- support several findings (docs/DATABASE/SCHEMA.md).
CREATE TABLE findings_evidence (
    finding_id  UUID NOT NULL REFERENCES findings (id) ON DELETE CASCADE,
    evidence_id UUID NOT NULL REFERENCES evidence (id) ON DELETE CASCADE,
    PRIMARY KEY (finding_id, evidence_id)
);

CREATE TABLE decisions (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    decision_run_id        UUID NOT NULL UNIQUE REFERENCES decision_runs (id) ON DELETE CASCADE,
    recommendation         VARCHAR(30) NOT NULL
                           CHECK (recommendation IN (
                               'APPROVE', 'CONDITIONAL_APPROVAL', 'REJECT', 'INSUFFICIENT_INFORMATION'
                           )),
    reasoning_summary      TEXT NOT NULL,
    confidence             NUMERIC(4, 3) NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    risk_level             VARCHAR(10) NOT NULL
                           CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    evidence_coverage      NUMERIC(4, 3),
    validation_passed      BOOLEAN,
    validation_details     JSONB,
    -- Written by the deterministic gate in Java (ADR-006), never copied from
    -- model output (docs/DATABASE/SCHEMA.md). Phase 7 builds that gate; until
    -- then Java sets this true unconditionally on every completed run — the
    -- safe default, since nothing may auto-approve itself
    -- (CLAUDE.md non-negotiable #2).
    requires_human_approval BOOLEAN NOT NULL,
    escalation_reasons     TEXT[],
    required_actions       TEXT[],
    -- Not in docs/DATABASE/SCHEMA.md's original design (written before
    -- docs/AI/AGENTS.md's Recommendation schema was finalized) — the model
    -- produces these as distinct fields from required_actions (conditions:
    -- specific to CONDITIONAL_APPROVAL; unresolved_questions: follow-up for
    -- a human reviewer) and dropping them would lose real information.
    -- SCHEMA.md updated to match in the same change.
    conditions             TEXT[],
    unresolved_questions   TEXT[],
    final_status           VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                           CHECK (final_status IN (
                               'PENDING', 'AUTO_APPROVED', 'HUMAN_APPROVED', 'HUMAN_REJECTED'
                           )),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
