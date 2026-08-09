# backend/spring-api

Java 21 + Spring Boot. Owns identity, RBAC, workspaces, documents, the decision lifecycle,
approvals, audit, SSE fan-out, and **all Flyway migrations**. Never calls an LLM.

Engineering rules for this module — read before changing code here:

@../../.claude/rules/backend-java.md

Also relevant: `.claude/rules/database.md` (migrations, queries), `.claude/rules/security.md`
(auth, tenancy), `.claude/rules/testing.md`.

Design references: `docs/API/API_DESIGN.md`, `docs/DATABASE/SCHEMA.md`, `docs/ARCHITECTURE.md`.

*Empty until Phase 1. Scaffold from start.spring.io and record the chosen Spring Boot version in
`docs/OPERATIONS/LOCAL_DEV.md` — do not pin it from memory.*
