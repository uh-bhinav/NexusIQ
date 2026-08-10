# Rules: Testing

Strategy narrative: `docs/TESTING/STRATEGY.md`.

## Principles

- Tests are part of the feature. "Done" without tests is not done.
- Test **behaviour**, not implementation. Refactoring must not break a good test.
- A test that cannot fail is worse than no test. Prove new tests fail before the fix exists.
- **Never** weaken an assertion, add a sleep, skip a test, or mock the thing under test just to get
  green. If a test is wrong, fix or delete it deliberately and say so.
- Deterministic. No real network, no real LLM, no wall-clock races in unit/integration tests.

## The pyramid here

| Level | Count | Speed | What |
|---|---|---|---|
| Unit | Most | ms | Services, validators, policy gate, chunker, context builder, parsers, mappers |
| Integration | Many | seconds | Spring + Testcontainers (Postgres/Kafka/Redis); FastAPI + Postgres |
| E2E | Few | minutes | Upload → ingest → decide → validate → approve → audit |
| Evaluation | Its own suite | minutes | RAG + agent quality against a labelled dataset |

## Java

- JUnit 5 + Mockito + AssertJ.
- Unit: every service method containing a branch. Mock repositories, not the DB engine's semantics.
- `@DataJpaTest` + Testcontainers Postgres for every custom query and every migration.
- `@SpringBootTest` + Testcontainers for each controller: happy path, validation failure,
  unauthenticated, **and cross-tenant denial**.
- Kafka consumers: test idempotency explicitly — deliver the same `event_id` twice, assert one effect.
- **No H2.** Test against real Postgres with pgvector; H2 lies about SQL.
- Coverage is a signal, not a target. Meaningful branches covered beats a percentage.
- **Naming decides which plugin runs a class — this is a real footgun.** `*Test.java` (mocked,
  no Docker) runs under Surefire via `./mvnw test`. `*IT.java` (Testcontainers-backed) runs under
  Failsafe via `./mvnw verify` — **not** `./mvnw test`, which silently skips them with exit 0.
  `pom.xml` binds `maven-failsafe-plugin`'s `integration-test`/`verify` goals for exactly this
  reason. `make test` runs `mvn verify` so both suites always execute; `make test-unit` runs only
  the fast Surefire suite. When adding a new integration test class, name it `*IT` and confirm it
  shows up in a `verify` run's test count — a class that compiles but never executes is worse than
  a missing one, because it looks covered.

## Python

- `pytest` + `pytest-asyncio`. Fixtures over setup duplication.
- **LLM calls in tests always use the `mock` provider** with recorded fixtures. Never hit a real
  API in unit/integration tests.
- Test every Pydantic schema against a malformed-output fixture — invalid JSON, missing field,
  wrong enum, hallucinated citation id.
- Test each graph node in isolation with a constructed `DecisionState`.
- Test the graph's routing: which edge is taken given which state. Assert termination.
- Retrieval: seed a known corpus, assert ranking and that `workspace_id` filtering excludes
  another tenant's chunks.
- `mypy --strict` and `ruff check` are part of the test gate.

## Frontend

- Vitest + React Testing Library. Test what a user sees and does.
- MSW for API mocking; mocks mirror the real contract (keep them honest against OpenAPI).
- Required per page: loading, empty, error, and populated states.
- No snapshot-only test suites.

## AI evaluation (mandatory gate for AI changes)

Dataset: ≥ 30 labelled cases in `ai-service/evaluation/datasets/`, covering the failure scenarios
below. Metrics:

- Retrieval: recall@5, recall@10, precision@5, MRR.
- Generation: groundedness (claims with valid evidence / total claims), citation validity rate,
  hallucination rate.
- Decision: recommendation accuracy, policy-status accuracy, escalation precision/recall.

Rules: record baseline before changing prompts/model/chunking/retrieval; report before→after; a
regression beyond the documented threshold blocks the change. Evaluation runs against the `mock`
provider in CI (deterministic) and optionally against the real provider locally.

## Failure scenarios that must have tests

Each of these is a named test, not a hope:

1. Zero retrieval results → `INSUFFICIENT_INFORMATION`, never a fabricated answer.
2. Contradictory documents → conflict identified, escalated to human.
3. Two policy versions → newer version preferred, and it says why.
4. Document containing prompt injection → instruction ignored, `PROMPT_INJECTION_ATTEMPT` finding raised.
5. Confidence below threshold → human approval required.
6. LLM timeout → bounded retry, then clean `FAILED` with reason.
7. LLM returns invalid JSON → one repair attempt, then node failure. No crash.
8. Duplicate Kafka event → exactly one decision run.
9. Kafka consumer failure ×3 → message in DLQ, visible.
10. Validator failure ×2 → escalated, not looped.
11. Cost/token budget exceeded → workflow stopped.
12. User from workspace B requests workspace A's decision → `404`.
13. Requester tries to approve their own decision → `403`.
14. Redis unavailable → requests still succeed from Postgres.

## Running

Focused verification beats a full suite. Run what the change touched (`/test-and-verify` decides),
then the full suite before declaring a phase complete. CI runs everything.

## Test data

Synthetic only. The sample enterprise corpus in `docs/sample-enterprise/` is deliberately
imperfect: satisfied requirements, real violations, ambiguity, conflicting versions, missing
information, and one injection attempt. Never commit real proprietary documents.
