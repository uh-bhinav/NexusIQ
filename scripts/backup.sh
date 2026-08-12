#!/usr/bin/env bash
# NexusIQ — Phase 12 Postgres backup.
#
# Dumps the running Postgres database (schema + data, pgvector included —
# pg_dump handles extension-typed columns natively) to a timestamped file
# under backups/. Does NOT touch document_storage (the uploaded file
# blobs) — that volume is a straightforward `docker cp`/tar target if ever
# needed; the database is what actually needs point-in-time recovery.

set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

[ -f .env ] && set -a && . ./.env && set +a

PG_USER="${POSTGRES_USER:-nexusiq}"
PG_DB="${POSTGRES_DB:-nexusiq}"
BACKUP_DIR="backups"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
OUT_FILE="$BACKUP_DIR/nexusiq-${TIMESTAMP}.sql.gz"

GRN='\033[0;32m'; RED='\033[0;31m'; DIM='\033[2m'; NC='\033[0m'

if ! docker compose ps postgres --format '{{.Health}}' 2>/dev/null | grep -qx healthy; then
  echo -e "${RED}postgres is not up/healthy — 'make up' or 'make demo' first.${NC}"
  exit 1
fi

mkdir -p "$BACKUP_DIR"
echo -e "${DIM}Dumping $PG_DB...${NC}"
docker compose exec -T postgres pg_dump -U "$PG_USER" -d "$PG_DB" --clean --if-exists | gzip > "$OUT_FILE"

size=$(du -h "$OUT_FILE" | cut -f1)
echo -e "${GRN}✓${NC} Backup written: $OUT_FILE ($size)"
echo -e "${DIM}Restore with: ./scripts/restore.sh $OUT_FILE${NC}"
