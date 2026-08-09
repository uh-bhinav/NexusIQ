# ai-service

Python 3.11+ · FastAPI · LangGraph. Owns ingestion (extract/chunk/embed), retrieval, the agent
graph, guardrails and evaluation. Authenticates nobody, authorises nothing, runs no migrations.

Engineering rules for this module — read before changing code here:

@../.claude/rules/ai-service.md

Also relevant: `.claude/rules/database.md` (writes `document_chunks` only),
`.claude/rules/security.md` (prompt injection, trust boundaries), `.claude/rules/testing.md`.

Design references: `docs/AI/` — `ARCHITECTURE.md`, `AGENTS.md`, `RAG.md`,
`CONTEXT_ENGINEERING.md`, `GUARDRAILS.md`, `EVALUATION.md`, `PROMPTS.md`, `MODEL_STRATEGY.md`.

*Empty until Phase 2.*
