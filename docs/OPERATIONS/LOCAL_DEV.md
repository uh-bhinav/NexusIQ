# Local Development

The **only** supported deployment target (ADR-010). Everything runs locally, at $0 recurring cost.

---

## Prerequisites

| Tool | Required | Verify |
|---|---|---|
| Java | **21 (LTS)** | `java -version` |
| Maven | 3.9+ | `mvn -version` (must report Java 21) |
| Python | 3.11+ | `python3 --version` |
| Node | 20+ | `node --version` |
| Docker | 24+ | `docker --version` |
| Docker Compose | v2 | `docker compose version` |
| RAM | 16 GB (full stack is heavy) | |
| Disk | ~15 GB (images + model weights + volumes) | |

### Current machine status (checked 2026-08-09) — **action required**

| Tool | Found | Status |
|---|---|---|
| Java | 1.8.0_392 (Corretto 8) is the default; JDK 23 present via Homebrew | ✗ Java 21 needed |
| Maven | not installed | ✗ |
| Python | 3.10.18 default; 3.13.1 via Homebrew; `uv` installed | ✗ default too old |
| Node | 22.13.0 | ✓ |
| Docker / Compose | 27.3.1 / v2.30.3 | ✓ |

**To fix (macOS / Homebrew):**

```bash
brew install openjdk@21 maven
sudo ln -sfn /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk \
             /Library/Java/JavaVirtualMachines/openjdk-21.jdk

# Make Java 21 active for this project (add to ~/.zshrc to persist):
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"

java -version   # must show 21
mvn -version    # must report Java 21

# Python 3.13 is already installed; uv manages the venv:
cd ai-service && uv venv --python 3.13 && source .venv/bin/activate
```

Java 21 is chosen over the installed JDK 23 because it is the current LTS with the broadest
Spring Boot support. `scripts/check-prereqs.sh` (Phase 0) verifies all of the above and fails
loudly with these instructions.

## Quickstart

```bash
git clone <repo> && cd NexusIQ
cp .env.example .env          # fill in LLM_API_KEY when you reach Phase 4
make setup                    # verify prerequisites, install dependencies
make up                       # start the stack
make migrate                  # apply Flyway migrations
make seed                     # load the sample corpus
```

## Services and ports

NexusIQ uses a **deliberately non-default host port block** so it can run alongside other local
stacks. Container-internal ports are always standard; only the host mappings shift. Override any
of them in `.env`.

| Service | Image | Host port | Container | Notes |
|---|---|---|---|---|
| PostgreSQL | `pgvector/pgvector:pg16` | **5434** | 5432 | PG 16.14, pgvector 0.8.6 |
| Redis | `redis:7-alpine` | **6380** | 6379 | LRU cache, no persistence |
| Kafka | `apache/kafka:4.3.1` | **29093** | 29092 (EXTERNAL) / 9092 (internal) | KRaft, single node |
| Kafka UI | `kafbat/kafka-ui:v1.5.0` | **8091** | 8080 | Topics, consumer lag, DLQ |
| OTel Collector | `otel/opentelemetry-collector-contrib:0.158.0` | **4327** gRPC / **4328** HTTP / **13134** health | 4317 / 4318 / 13133 | |
| API | `spring-api` | 8080 | 8080 | Phase 1 — Swagger at `/swagger-ui.html` |
| AI service | `ai-service` | 8000 | 8000 | Phase 2 — not internet-facing |
| Frontend | `frontend` | 5173 | 5173 | Phase 9 — Vite |
| Jaeger/Tempo | | 16686 | | Phase 8 |
| Prometheus | | 9090 | | Phase 8 |
| Grafana | | 3000 | | Phase 8 |

Image versions are pinned and were verified available for `linux/arm64` and `linux/amd64` on
2026-08-09.

### Two implementation notes worth knowing

- **`kafka-init`** is a one-shot busybox service that `chown`s the Kafka volume to uid 1000 before
  the broker starts. Docker creates a named volume's mount point owned by root when the path does
  not exist in the image, and the Kafka image runs as `appuser` — without this the broker cannot
  format its log directory.
- **`otel-collector` has no container healthcheck.** The image is distroless: no shell, no
  `wget`/`curl`, so every `CMD`/`CMD-SHELL` probe fails regardless of the collector's real state.
  It is probed from the host instead, by `scripts/verify-stack.sh` against the `health_check`
  extension on 13134.

## Make targets

```
make help       list targets
make env        create .env from .env.example
make setup      .env + prerequisite check
make check      verify build prerequisites (java, maven, python, node)
make up         start the stack (checks infra prerequisites first)
make down       stop (volumes preserved)
make restart    down + up
make clean      stop AND delete volumes — destroys all local data (confirms first)
make verify     run the Phase 0 acceptance checks
make logs       tail all service logs
make ps         container status
make psql       psql shell
make redis-cli  redis-cli shell
make topics     list Kafka topics
make migrate    Flyway migrations                    (Phase 1)
make seed       load docs/sample-enterprise/         (Phase 2)
make demo       up + migrate + seed + demo user      (Phase 12)
make test       all test suites
make lint       all linters
make eval       AI evaluation harness                (Phase 10)
```

Targets for phases that are not yet implemented print a clear message rather than failing
cryptically.

## Verifying the stack

```bash
make verify
```

Runs 19 checks: container health, Postgres connectivity, pgvector availability and an actual
`vector` round trip, pgcrypto, Redis round trip, Kafka produce/consume, external listener
reachability, kafka-ui broker visibility, and an OTLP span accepted and observed in the collector
pipeline. Exit code is non-zero if anything fails.

## Development loop

Backend and AI service run in Docker for infrastructure parity, or on the host for a faster
inner loop:

```bash
# Java on host, infrastructure in Docker
docker compose up -d postgres redis kafka otel-collector
cd backend/spring-api && ./mvnw spring-boot:run

# Python on host
cd ai-service && uv run uvicorn app.main:app --reload --port 8000

# Frontend
cd frontend/web && npm run dev
```

When running on the host, point `POSTGRES_HOST=localhost`, `POSTGRES_PORT=5434`,
`KAFKA_BOOTSTRAP_SERVERS=localhost:29093` in your local `.env`.

## Useful commands

```bash
psql -h localhost -p 5434 -U nexusiq -d nexusiq
redis-cli -h localhost -p 6380 ping
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
curl -s localhost:8080/actuator/health | jq
curl -s localhost:8000/ready | jq
docker stats --no-stream                      # memory footprint
```

## Notes

- **First run downloads the embedding model** (~130 MB for bge-small, plus torch in the image).
  Phase 12 decides whether to pre-bake it into the image or cache it in a volume — until then,
  expect a slow first `make up`.
- `make clean` deletes volumes and therefore all documents, chunks and decisions. `make down`
  does not.
- The host port block (5434 / 6380 / 29093 / 8091 / 4327-4328 / 13134) is deliberately
  non-default; adjust in `.env` if any of them clash.
- Never commit `.env`.

## Troubleshooting

Runbook: `docs/OPERATIONS/RUNBOOK.md`.
