# Rules: Java / Spring Boot backend

Scope: `backend/spring-api/**`.

## Versions

- **Java 21 (LTS).** Use records, sealed interfaces, pattern matching, virtual threads where they
  genuinely help.
- **Spring Boot**: verify the current stable release at scaffold time via `start.spring.io`
  (Spring Initializr) — **do not pin a version from memory or from a stale doc.** Record the chosen
  version in `docs/OPERATIONS/LOCAL_DEV.md` once fixed.
- Maven (with wrapper `./mvnw` committed). No Gradle.

## Package layout

Feature-first, not layer-first. Root `com.nexusiq`.

```
com.nexusiq
├── common/          # errors, correlation, pagination, base types
├── config/          # @Configuration only
├── security/        # JWT, filters, method security, workspace authz
├── auth/            # register/login/refresh
├── user/
├── workspace/       # workspace + membership
├── document/        # upload, metadata, storage abstraction
├── knowledge/       # search proxy to ai-service
├── decision/        # request lifecycle, runs, evidence, findings
├── approval/
├── audit/
├── messaging/       # Kafka producers/consumers, envelopes, idempotency
├── streaming/       # SSE emitters
└── observability/   # metrics, tracing helpers
```

Each feature package contains `Controller`, `Service`, `Repository`, `dto/`, `entity/`,
`mapper/`, `event/`. **Never** a global `services/` or `dtos/` package.

## Layer discipline

```
Controller  → Service → Repository → DB
(DTO only)    (domain)  (entity only)
```

- Controllers: HTTP only — validate, map, delegate, return. **No business logic. No repository
  access. No `@Transactional`.**
- Services: business logic, transaction boundaries, event publication, authorization checks.
- Repositories: Spring Data JPA. Custom queries via `@Query` or a `...RepositoryCustom` impl.
- **Entities never cross the controller boundary.** Ever. Map to DTOs.

## DTOs

- Java `record` for request and response DTOs. Immutable.
- Naming: `CreateDecisionRequest`, `DecisionResponse`, `DecisionSummaryResponse`.
- Explicit mappers (`DecisionMapper`) — plain code, no MapStruct/ModelMapper unless an ADR says so.
- Never expose `password_hash`, internal IDs of other tenants, or raw entity graphs.

## Validation

- Bean Validation on request DTOs (`@NotBlank`, `@Size`, `@Email`, `@Valid`).
- Semantic validation (does this workspace exist? may this user act on it?) belongs in the service.
- Validate at the boundary; assume valid inside the domain.

## Errors

One `@RestControllerAdvice`. Every error response:

```json
{
  "timestamp": "RFC3339",
  "status": 400,
  "error": "VALIDATION_ERROR",
  "message": "human readable",
  "path": "/api/v1/decisions",
  "request_id": "uuid",
  "details": [{"field": "question", "issue": "must not be blank"}]
}
```

Throw typed exceptions (`ResourceNotFoundException`, `WorkspaceAccessDeniedException`,
`ValidationException`, `ConflictException`, `UpstreamUnavailableException`). Never throw raw
`RuntimeException`. **Never leak stack traces, SQL, or internal paths to clients.**

Codes: `VALIDATION_ERROR` 400 · `UNAUTHORIZED` 401 · `FORBIDDEN` 403 · `NOT_FOUND` 404 ·
`CONFLICT` 409 · `PAYLOAD_TOO_LARGE` 413 · `RATE_LIMITED` 429 · `INTERNAL_ERROR` 500 ·
`SERVICE_UNAVAILABLE` 503.

## Transactions

- `@Transactional` on **service** methods only.
- Read paths use `@Transactional(readOnly = true)`.
- **Never publish a Kafka event inside a transaction and assume atomicity.** Either publish after
  commit (`@TransactionalEventListener(AFTER_COMMIT)`) or use an outbox table. Default to
  after-commit; use an outbox if the event must not be lost (ADR required).
- Keep transactions short. No HTTP calls, no LLM calls, no file I/O inside one.

## Authorization

- `@PreAuthorize` for role checks; a `WorkspaceAccessService` for membership checks.
- **Every** workspace-scoped repository method takes `workspaceId` as a parameter and filters on it
  in SQL. Never fetch-then-check in Java.
- Roles: `ADMIN`, `ANALYST`, `APPROVER`, `VIEWER` (global) + per-workspace membership role.
- A user may never approve a decision they requested. Enforce in the service, test it.

## API

- Base path `/api/v1`. Version in the path; never break v1 silently.
- Plural resource nouns, kebab-case paths, `snake_case` JSON (configure Jackson globally —
  pick one and never mix).
- Pagination: `?page=0&size=20&sort=created_at,desc` → `PageResponse<T>` with
  `content`, `page`, `size`, `total_elements`, `total_pages`.
- `POST` returns `201` + `Location`. `DELETE` returns `204`.
- Long-running work returns `202 Accepted` + resource id + `status`, never blocks.
- Idempotency: mutating endpoints that can be retried accept an `Idempotency-Key` header.

## Kafka

- Producers/consumers live in `messaging/`, one class per topic.
- Consumers are idempotent (see `.claude/rules/architecture.md`) and **manually acknowledge**.
- Serialization: JSON with an explicit envelope record. No Java serialization.
- Every consumer has a `@RetryableTopic` / error handler routing to `<topic>.dlq` after 3 attempts.

## Caching

- `@Cacheable` with Redis, explicit TTL per cache, never a default-forever.
- **Cache keys must include `workspaceId`** (and `userId` when the result is user-scoped).
  A cache key without a tenant component is a security bug.
- Never cache authorization decisions.

## Observability

- Actuator: `health`, `info`, `metrics`, `prometheus` exposed; everything else off.
- Micrometer counters/timers on every service method that matters. `@Timed` or explicit.
- Structured JSON logging with `correlation_id` in MDC via a servlet filter.
- **Never log**: passwords, JWTs, `LLM_API_KEY`, full document text, PII beyond user id/email hash.

## Testing

See `.claude/rules/testing.md`. Minimums for this module: unit tests for every service with logic;
`@DataJpaTest` + Testcontainers Postgres for custom queries; `@SpringBootTest` + Testcontainers
(Postgres, Kafka, Redis) for each controller's happy path + authz-denied path.

## Do not

- Field injection (`@Autowired` on fields) — constructor injection only.
- Lombok on entities' `@Data`/`@EqualsAndHashCode` (JPA identity hazard). `@Getter`/`@Builder` ok.
- `EAGER` fetching. Default everything to `LAZY`.
- Business logic in entities beyond invariants.
- `System.out.println`.
- Swallowing exceptions.
- New dependencies without checking Spring Boot already provides it.
