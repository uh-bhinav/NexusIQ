#!/usr/bin/env bash
# NexusIQ — Phase 0 acceptance verification.
#
# Proves the local stack actually works, with evidence, rather than assuming it.
# Criteria are defined in docs/IMPLEMENTATION/ROADMAP.md § Phase 0.

set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

[ -f .env ] && set -a && . ./.env && set +a

PG_USER="${POSTGRES_USER:-nexusiq}"
PG_DB="${POSTGRES_DB:-nexusiq}"
PG_PORT="${POSTGRES_EXPOSED_PORT:-5434}"
OTEL_HTTP="${OTEL_HTTP_PORT:-4328}"
OTEL_HEALTH="${OTEL_HEALTH_PORT:-13134}"
KAFKA_UI="${KAFKA_UI_PORT:-8091}"
KAFKA_PORT="${KAFKA_EXPOSED_PORT:-29093}"

PASS=0; FAIL=0
GRN='\033[0;32m'; RED='\033[0;31m'; DIM='\033[2m'; NC='\033[0m'

check() { # check <name> <command...>
  local name="$1"; shift
  local out
  if out=$("$@" 2>&1); then
    printf "  ${GRN}✓${NC} %s\n" "$name"
    [ -n "$out" ] && printf "    ${DIM}%s${NC}\n" "$(echo "$out" | head -3 | tr '\n' ' ')"
    PASS=$((PASS+1))
  else
    printf "  ${RED}✗${NC} %s\n" "$name"
    printf "    ${DIM}%s${NC}\n" "$(echo "$out" | head -5)"
    FAIL=$((FAIL+1))
  fi
}

echo
echo "NexusIQ — Phase 0 stack verification"
echo "═══════════════════════════════════════════════════════════════"

# --- 1. All containers healthy -------------------------------------------
echo
echo "1. Container health"
# otel-collector is distroless and cannot run a container healthcheck, so
# "running" is the expected state for it; it is probed over HTTP in section 5.
for svc in postgres redis kafka kafka-ui otel-collector; do
  status=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
           "nexusiq-${svc}" 2>/dev/null || echo "missing")
  if [ "$svc" = "otel-collector" ] && [ "$status" = "running" ]; then
    printf "  ${GRN}✓${NC} %-16s %s ${DIM}(no healthcheck: distroless)${NC}\n" "$svc" "$status"; PASS=$((PASS+1)); continue
  fi
  if [ "$status" = "healthy" ]; then
    printf "  ${GRN}✓${NC} %-16s %s\n" "$svc" "$status"; PASS=$((PASS+1))
  else
    printf "  ${RED}✗${NC} %-16s %s\n" "$svc" "$status"; FAIL=$((FAIL+1))
  fi
done

# --- 2. PostgreSQL + pgvector --------------------------------------------
echo
echo "2. PostgreSQL + pgvector"
check "postgres accepts connections" \
  docker compose exec -T postgres psql -U "$PG_USER" -d "$PG_DB" -tAc "select version();"
check "pgvector extension is available" \
  docker compose exec -T postgres psql -U "$PG_USER" -d "$PG_DB" -tAc \
    "select name||' '||default_version from pg_available_extensions where name='vector';"
check "vector type works end to end" \
  docker compose exec -T postgres psql -U "$PG_USER" -d "$PG_DB" -tAc \
    "create extension if not exists vector;
     create temp table _v(e vector(3));
     insert into _v values ('[1,0,0]'),('[0,1,0]');
     select round((1 - (e <=> '[1,0,0]'))::numeric, 3) from _v order by e <=> '[1,0,0]' limit 1;"
check "pgcrypto available (gen_random_uuid)" \
  docker compose exec -T postgres psql -U "$PG_USER" -d "$PG_DB" -tAc \
    "create extension if not exists pgcrypto; select gen_random_uuid();"
check "postgres reachable from host on $PG_PORT" \
  bash -c "(echo > /dev/tcp/127.0.0.1/$PG_PORT) 2>/dev/null && echo 'port open'"

# --- 3. Redis -------------------------------------------------------------
echo
echo "3. Redis"
check "redis PING" docker compose exec -T redis redis-cli ping
check "redis set/get round trip" \
  bash -c "docker compose exec -T redis redis-cli set _verify ok >/dev/null && \
           docker compose exec -T redis redis-cli get _verify && \
           docker compose exec -T redis redis-cli del _verify >/dev/null"

# --- 4. Kafka -------------------------------------------------------------
echo
echo "4. Kafka"
check "broker responds (internal listener)" \
  docker compose exec -T kafka /opt/kafka/bin/kafka-broker-api-versions.sh \
    --bootstrap-server localhost:9092 --version
check "topic create / produce / consume" \
  bash -c "
    docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
      --create --if-not-exists --topic _verify --partitions 1 --replication-factor 1 >/dev/null 2>&1
    echo 'nexusiq-verify' | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
      --bootstrap-server localhost:9092 --topic _verify >/dev/null 2>&1
    got=\$(docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
      --bootstrap-server localhost:9092 --topic _verify --from-beginning --max-messages 1 \
      --timeout-ms 15000 2>/dev/null | tr -d '\r\n')
    docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
      --delete --topic _verify >/dev/null 2>&1
    [ \"\$got\" = 'nexusiq-verify' ] && echo \"round trip ok: \$got\"
  "
check "external listener reachable from host" \
  bash -c "(echo > /dev/tcp/127.0.0.1/$KAFKA_PORT) 2>/dev/null && echo 'port open'"
check "kafka-ui sees the broker" \
  bash -c "curl -sf http://localhost:$KAFKA_UI/api/clusters | head -c 200"

# --- 5. OTel Collector ----------------------------------------------------
echo
echo "5. OpenTelemetry Collector"
check "health endpoint" bash -c "curl -sf http://localhost:$OTEL_HEALTH/ -o /dev/null && echo 'healthy'"
check "accepts an OTLP/HTTP trace" \
  bash -c "
    code=\$(curl -s -o /dev/null -w '%{http_code}' -X POST \
      http://localhost:$OTEL_HTTP/v1/traces \
      -H 'Content-Type: application/json' \
      -d '{\"resourceSpans\":[{\"resource\":{\"attributes\":[{\"key\":\"service.name\",\"value\":{\"stringValue\":\"nexusiq-verify\"}}]},\"scopeSpans\":[{\"spans\":[{\"traceId\":\"5b8efff798038103d269b633813fc60c\",\"spanId\":\"eee19b7ec3c1b174\",\"name\":\"verify-span\",\"kind\":1,\"startTimeUnixNano\":\"1700000000000000000\",\"endTimeUnixNano\":\"1700000001000000000\"}]}]}]}')
    [ \"\$code\" = '200' ] && echo \"HTTP \$code\"
  "
check "span appears in collector output" \
  bash -c "docker compose logs --tail=200 otel-collector 2>/dev/null | grep -q 'nexusiq-verify' && echo 'span received'"

# --- Summary --------------------------------------------------------------
echo
echo "═══════════════════════════════════════════════════════════════"
if [ $FAIL -eq 0 ]; then
  printf "${GRN}Phase 0 verification: %d checks passed.${NC}\n\n" "$PASS"
  exit 0
fi
printf "${RED}Phase 0 verification: %d passed, %d FAILED.${NC}\n\n" "$PASS" "$FAIL"
exit 1
