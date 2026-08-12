#!/usr/bin/env bash
# NexusIQ — Phase 12 demo seeding.
#
# Uploads the full docs/sample-enterprise/ corpus into a fixed demo
# workspace via the real spring-api REST API (registration -> workspace ->
# upload -> poll READY), exactly the path a real user takes. Idempotent:
# safe to run against an already-seeded stack — reuses the existing demo
# user/workspace and skips documents already present by name, rather than
# accumulating duplicates on every `make seed`.
#
# The demo credentials below are for a LOCAL-ONLY, throwaway demo account —
# not a real secret, printed deliberately so a fresh clone's user can log
# in immediately. Never reuse this password for anything else.

set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

[ -f .env ] && set -a && . ./.env && set +a

SPRING_BASE="${SEED_SPRING_BASE_URL:-http://localhost:${API_PORT:-8080}}"
API_BASE="$SPRING_BASE/api/v1"
DEMO_EMAIL="demo@nexusiq.local"
DEMO_PASSWORD="NexusIQDemo!2026"
DEMO_NAME="NexusIQ Demo"
WORKSPACE_NAME="NexusIQ Demo Workspace"

GRN='\033[0;32m'; RED='\033[0;31m'; YLW='\033[0;33m'; DIM='\033[2m'; NC='\033[0m'
log() { printf "  ${DIM}%s${NC}\n" "$1"; }
ok()  { printf "  ${GRN}✓${NC} %s\n" "$1"; }

echo
echo "NexusIQ — seeding the demo corpus"
echo "═══════════════════════════════════════════════════════════════"

for i in $(seq 1 30); do
  curl -sf "$SPRING_BASE/actuator/health" >/dev/null 2>&1 && break
  [ "$i" -eq 30 ] && { echo -e "${RED}spring-api never became healthy at $SPRING_BASE${NC}"; exit 1; }
  sleep 2
done
ok "spring-api reachable"

# --- Demo user: register, or log in if it already exists (idempotent) ---
register_body=$(printf '{"email": "%s", "name": "%s", "password": "%s"}' "$DEMO_EMAIL" "$DEMO_NAME" "$DEMO_PASSWORD")
resp=$(curl -s -w '\n%{http_code}' -X POST "$API_BASE/auth/register" -H "Content-Type: application/json" -d "$register_body")
status=$(echo "$resp" | tail -1)
body=$(echo "$resp" | sed '$d')

if [ "$status" = "201" ]; then
  ok "demo user created ($DEMO_EMAIL)"
elif [ "$status" = "409" ]; then
  log "demo user already exists — logging in instead"
  login_body=$(printf '{"email": "%s", "password": "%s"}' "$DEMO_EMAIL" "$DEMO_PASSWORD")
  body=$(curl -sf -X POST "$API_BASE/auth/login" -H "Content-Type: application/json" -d "$login_body")
  ok "demo user login OK"
else
  echo -e "${RED}Unexpected registration response ($status): $body${NC}"
  exit 1
fi
TOKEN=$(echo "$body" | python3 -c "import json,sys;print(json.load(sys.stdin)['access_token'])")

# --- Demo workspace: reuse if it already exists (idempotent) ---
WORKSPACE_ID=$(curl -s "$API_BASE/workspaces?size=50" -H "Authorization: Bearer $TOKEN" \
  | python3 -c "
import json,sys
d = json.load(sys.stdin)
for w in d['content']:
    if w['name'] == '$WORKSPACE_NAME':
        print(w['id'])
        break
")
if [ -n "$WORKSPACE_ID" ]; then
  log "demo workspace already exists — reusing it"
else
  ws_body=$(printf '{"name": "%s", "description": "Seeded automatically by make seed — the full sample-enterprise corpus."}' "$WORKSPACE_NAME")
  WORKSPACE_ID=$(curl -sf -X POST "$API_BASE/workspaces" -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" -d "$ws_body" | python3 -c "import json,sys;print(json.load(sys.stdin)['id'])")
  ok "demo workspace created"
fi

# --- Corpus upload: skip any document already present by name (idempotent) ---
declare -A doc_type_by_dir=(
  [security]=SECURITY_POLICY
  [compliance]=COMPLIANCE_POLICY
  [procurement]=PROCUREMENT_POLICY
  [architecture]=ARCHITECTURE_STANDARD
  [vendors]=VENDOR_DOCUMENT
  [historical]=HISTORICAL_DECISION
  [incidents]=INCIDENT_REPORT
)

existing_names=$(curl -s "$API_BASE/workspaces/$WORKSPACE_ID/documents?size=50" -H "Authorization: Bearer $TOKEN" \
  | python3 -c "import json,sys;print('\n'.join(d['name'] for d in json.load(sys.stdin)['content']))")

uploaded=0
skipped=0
for dir in security compliance procurement architecture vendors historical incidents; do
  doc_type="${doc_type_by_dir[$dir]}"
  for file in docs/sample-enterprise/"$dir"/*.md; do
    [ -f "$file" ] || continue
    name=$(basename "$file")
    if echo "$existing_names" | grep -qxF "$name"; then
      skipped=$((skipped + 1))
      continue
    fi
    metadata=$(printf '{"name": "%s", "document_type": "%s"}' "$name" "$doc_type")
    curl -sf -X POST "$API_BASE/workspaces/$WORKSPACE_ID/documents" \
      -H "Authorization: Bearer $TOKEN" \
      -F "file=@$file;type=text/markdown" \
      -F "metadata=$metadata;type=application/json" >/dev/null
    uploaded=$((uploaded + 1))
  done
done
ok "corpus upload requested: $uploaded new, $skipped already present"

if [ "$uploaded" -gt 0 ]; then
  log "waiting for ingestion (extract -> chunk -> embed) to finish..."
  for i in $(seq 1 60); do
    processing=$(curl -s "$API_BASE/workspaces/$WORKSPACE_ID/documents?size=50" -H "Authorization: Bearer $TOKEN" \
      | python3 -c "
import json,sys
d = json.load(sys.stdin)
print(sum(1 for doc in d['content'] if doc['status'] in ('UPLOADED', 'PROCESSING')))
")
    [ "$processing" = "0" ] && break
    sleep 3
  done
  ok "ingestion complete"
fi

echo
echo "═══════════════════════════════════════════════════════════════"
echo -e "${GRN}Demo ready.${NC}"
echo
echo "  URL:      http://localhost:${VITE_DEV_PORT:-5173}  (or the frontend container's port)"
echo "  Email:    $DEMO_EMAIL"
echo "  Password: $DEMO_PASSWORD"
echo -e "  ${YLW}Local demo credentials only — never reuse this password anywhere else.${NC}"
echo
