# Runbook

Diagnosis and recovery for the local stack. Fill in real symptoms as they are encountered — an
imagined runbook is worthless, an accumulated one is the most useful file in the repository.

Setup: `LOCAL_DEV.md`. Observability design: `OBSERVABILITY.md`.

---

## First moves for any problem

```bash
docker compose ps                    # what is up, what is restarting
docker compose logs --tail=100 <svc> # what it said before it broke
curl -s localhost:8080/actuator/health | jq
curl -s localhost:8000/ready | jq
```

Then find the `correlation_id` for the affected request and follow it: API logs → Kafka event →
AI service logs → the trace in Jaeger. One id, the whole path. That is what it is for.

---

## Symptoms

### A container will not start / restarts in a loop

`docker compose logs <svc>` first. Usual causes: a port already bound on the host, a missing or
placeholder value in `.env`, or a dependency that is not healthy yet. Compose healthchecks should
sequence startup — if they do not, fix the healthcheck rather than adding a sleep.

**Port already allocated.** NexusIQ uses a non-default host block (5434 / 6380 / 29093 / 8091 /
4327-4328 / 13134) precisely to avoid this, but other stacks move around. Find the offender with
`lsof -nP -iTCP:<port> -sTCP:LISTEN` and change the mapping in `.env`.

**Kafka: `AccessDeniedException` formatting the log dir.** The `kafka-init` service is missing or
failed. Docker creates a named volume's mount point owned by root; the Kafka image runs as uid
1000. `kafka-init` chowns it first — check that it exited 0 (`docker compose ps -a`).

**otel-collector shows no health status.** Expected. The image is distroless, so it cannot run a
container healthcheck. Probe it from the host: `curl http://localhost:13134/`.

### Postgres: `extension "vector" is not available`

The image is not a pgvector-enabled one. Check the image tag in `docker-compose.yml`; `V1` runs
`CREATE EXTENSION IF NOT EXISTS vector` and will fail loudly against a plain `postgres` image.

### Flyway: migration checksum mismatch

An already-applied migration was edited. **Never edit an applied migration.** Locally: `make clean`
and re-run. Otherwise: revert the edit and add a new migration.

### Flyway: migration failed, schema locked

`SELECT * FROM flyway_schema_history WHERE success = false;` — remove the failed row, fix the SQL,
re-run. If a lock is stuck, restart the API container.

### Kafka: consumer lag grows / events are not processed

```bash
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --all-groups
```
Consumer down, consumer erroring in a loop, or messages going to the DLQ. Check kafka-ui (:8091)
for the DLQ topic and read `failure_reason` on the envelope.

### Documents stay in `PROCESSING`

The ingestion consumer failed. Check `ai-service` logs for the document id. Likely: extraction
failed on a malformed PDF, the embedding model is not loaded (`/ready` will say), or an exception
before the completion event was published. Check the DLQ.

### `/ready` returns not-ready on the AI service

Either the database is unreachable or the embedding model has not finished loading — on a cold
first run the model download can take minutes. Check logs for the download progress before
assuming a fault.

### Decisions stay in `PROCESSING`

Order of checks: is `ai-service` up and ready → did it consume `decision.requested` (consumer lag)
→ is it stuck on an LLM call (timeout should fire; if it does not, the timeout is not wired) → did
it exceed a budget and fail without emitting the terminal event → is Java consuming
`decision.completed`. The trace will show which node it stopped at.

### Every decision escalates to human review

The gate is doing its job or the thresholds are wrong. Check `escalation_reasons` on the decision.
If coverage is consistently low, the problem is retrieval, not the gate — do not fix it by lowering
`HITL_MIN_EVIDENCE_COVERAGE`. Run the evaluation harness before changing any threshold.

### Retrieval returns nothing

Are chunks present (`SELECT count(*) FROM document_chunks WHERE workspace_id = ...`)? Is the
document `READY`? Is `RETRIEVAL_MIN_SIMILARITY` too high? **Is the query embedded with the same
model and prefix convention as the chunks?** A model or prefix mismatch produces plausible-looking
vectors and near-random results — check `embedding_model` on the chunk rows.

### Retrieval returns another workspace's content

Stop. This is a security defect, not a bug. Find the query missing its `workspace_id` predicate,
or the cache key missing its tenant component. Write the failing test first, then fix.

### LLM calls fail with rate-limit errors

Free-tier quota (ADR-008). Wait, reduce evaluation concurrency, or switch to `LLM_PROVIDER=mock`
for the work in hand. Do not raise retry counts to push through a quota.

### SSE stream stops updating

Did the server close on a terminal event (correct)? Did the client fail to reconnect? Is a proxy
buffering? The client must reconcile with `GET /decisions/{id}` on reconnect and fall back to
polling — if it does not, that is the bug.

### Traces are broken at the Kafka boundary

The standard failure. Trace context must be carried **explicitly in the event envelope**;
automatic propagation does not cross a broker. Check that the producer injects it and the consumer
extracts it.

### The stack is eating all available memory

`docker stats`. Kafka, Postgres and the AI service (torch + model) are the heavy ones. For a
lighter loop, run only the infrastructure in Docker and the services on the host
(`LOCAL_DEV.md` § Development loop). `docker-compose.prod.yml` (Phase 12) sets a `deploy.resources.
limits.memory` ceiling per service — `ai-service` is deliberately the most generous (3g) since torch
+ the loaded embedding model are the heaviest single thing in the stack.

### Docker builds fail with "no space left on device" even though `docker system df` looks fine

Confirmed for real during Phase 12 (2026-08-12), not theoretical: `docker system df` can report a
modest amount of space in use while the *actual* Docker Desktop VM disk file is nearly full — on
macOS, that file grows from heavy build churn (this session rebuilt the same ~8GB `ai-service` image
close to a dozen times across Phase 11/12) and does **not** automatically shrink back down, even
after `docker builder prune -af && docker image prune -af` (those only reclaim space in Docker's own
accounting, not the VM disk file's on-disk size). Check the actual allocation vs. Docker Desktop's
own settings: `cat ~/Library/Group\ Containers/group.com.docker/settings-store.json | python3 -c
"import json,sys; print(json.load(sys.stdin)['DiskSizeMiB'])"`.

If a disk-full error happens *during an active write*, it can leave the daemon in a genuinely broken
state afterward (not just low on space) — confirmed this session: the daemon started returning
`Internal Server Error` on every single API call (`docker version`, `docker info`), and a normal
restart didn't fix it. `osascript -e 'tell application "Docker" to quit'` also did **not** actually
quit the app — Docker Desktop backgrounds on the dock icon rather than fully exiting by default,
confirmed by unchanged process start times after "quitting." A full `killall -9 "Docker Desktop"
com.docker.backend` followed by `open -a Docker` (genuine cold start) got the daemon responding on
its socket again but still `Internal Server Error`, not actually healthy.

**The real fix needs manual action in Docker Desktop's own UI**, not something to script around:
either Settings → Resources → Advanced → increase the disk image size, or (if that doesn't help)
Settings → Troubleshoot → "Clean / Purge data" — the latter is destructive to *all* local Docker
state (every image, container, and volume, not just one project's), so treat it as a last resort and
make sure nothing else on the machine depends on what's there first.

---

## Recovery

```bash
docker compose restart <svc>          # single service
make down && make up                  # full restart, data preserved
make clean && make up && make migrate && make seed   # nuke and rebuild — DESTROYS all local data
make backup                           # pg_dump the running database to backups/ (Phase 12)
make restore FILE=backups/nexusiq-....sql.gz   # restore from a backup — asks for confirmation
```

---

## Escalation to a code change

If the same symptom recurs, it is not an operational issue — it is a missing guard, a missing
test, or a missing healthcheck. Add the fix and record it here.
