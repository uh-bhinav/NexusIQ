---
name: test-and-verify
description: Run focused verification of NexusIQ after a change — pick the right tests, check services start, migrations apply, Kafka flows, APIs respond, AI workflow completes — without running unrelated suites. Use after implementing anything, and before declaring a phase complete.
---

# Test and verify

Goal: prove the change works, with evidence, at the lowest cost. Focused verification first,
full suite before a phase is declared complete.

---

## 1. Scope the verification

Look at what actually changed (`git status`, `git diff --stat`) and pick the matching column.
Do **not** run everything by reflex.

| Changed | Run |
|---|---|
| Java service/controller logic | `./mvnw test -Dtest='<Class>*'` then that module's ITs |
| Flyway migration | Migration test + `@DataJpaTest` for affected repos + inspect schema |
| Kafka producer/consumer | Integration test with Testcontainers Kafka + **duplicate-delivery test** |
| Python agent / graph node | `pytest tests/agents/test_<node>.py` + graph routing tests |
| Retrieval / chunking / embeddings | Retrieval tests **+ the evaluation harness** (mandatory) |
| Prompts / model config | **Evaluation harness with before/after numbers** (mandatory) |
| Frontend component/page | `npm test -- <file>` + `npm run build` (type errors surface here) |
| Config / env / compose | Full stack boot + health checks |
| Anything cross-service | The E2E flow (§4) |

## 2. Static gates (fast, always run for touched languages)

```
# Java
./mvnw -q compile

# Python
ruff check app tests && mypy app

# Frontend
npm run lint && npx tsc --noEmit
```

Type/lint failures are failures. Do not proceed past them.

## 3. Service-level checks (when infrastructure or startup changed)

```
docker compose ps                      # all services healthy, none restarting
curl -s localhost:8080/actuator/health # {"status":"UP"}
curl -s localhost:8000/ready           # AI service: DB reachable + embedding model loaded
docker compose logs --tail=50 <svc>    # no stack traces on boot
```

Migrations: confirm Flyway applied cleanly and `flyway_schema_history` has no failed row.
Kafka: confirm topics exist and consumer groups have no growing lag.

## 4. End-to-end flow (before declaring a phase complete, and for cross-service changes)

Only the portions that exist yet:

1. Register → login → JWT returned.
2. Create workspace → add member.
3. Upload a sample document → `document.uploaded` published.
4. Ingestion consumes → chunks + embeddings persisted → status `READY`.
5. Semantic search returns cited chunks with scores, scoped to the workspace.
6. Submit a decision request → `202` + `PROCESSING`.
7. SSE stream emits progress for each agent node.
8. Run completes → recommendation + confidence + risk + evidence + findings.
9. Validator result recorded; low confidence/high risk → approval required.
10. Approver approves → final status set → audit events written.
11. Traces visible with a single correlation id spanning all services.

## 5. Verify against acceptance criteria

Open the phase in `docs/IMPLEMENTATION/ROADMAP.md` and go criterion by criterion. Each one needs
**evidence**: command output, HTTP response, a row from a query, a log line. "Should work" is not
verification.

## 6. Negative checks (do not skip these)

For any change touching data access or auth, verify at least:
- A user from workspace B cannot read workspace A's resource (expect `404`).
- An unauthenticated request is rejected.
- A duplicate Kafka `event_id` produces exactly one effect.
- An LLM/schema failure path degrades cleanly rather than crashing.

## 7. Report

```
RAN:        <commands>
RESULT:     X passed, Y failed, Z skipped   (real numbers)
CRITERIA:   each acceptance criterion → met / not met + evidence
FAILURES:   what failed, why, and whether it blocks
NOT RUN:    what was deliberately skipped and why
```

**Report failures plainly.** Never present a partially verified change as verified. Never disable,
skip, or weaken a test to produce green — if a test is genuinely wrong, fix it deliberately and
say that you did.

---

## Cost discipline

- Don't boot the entire stack to test one pure function.
- Don't run the evaluation harness for a frontend change.
- Do run the evaluation harness for **any** change to prompts, models, chunking, or retrieval.
- Reuse a running stack rather than tearing it down; never `docker compose down -v` casually — it
  destroys the volume and the seeded corpus.
