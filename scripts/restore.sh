#!/usr/bin/env bash
# NexusIQ — Phase 12 Postgres restore.
#
# Usage: ./scripts/restore.sh backups/nexusiq-20260812-140000.sql.gz
#
# Restores a dump produced by backup.sh into the running Postgres. The
# dump was taken with --clean --if-exists, so it drops and recreates
# objects itself — this script does not truncate anything first.

set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

[ -f .env ] && set -a && . ./.env && set +a

PG_USER="${POSTGRES_USER:-nexusiq}"
PG_DB="${POSTGRES_DB:-nexusiq}"

GRN='\033[0;32m'; RED='\033[0;31m'; YLW='\033[0;33m'; NC='\033[0m'

FILE="${1:-}"
if [ -z "$FILE" ] || [ ! -f "$FILE" ]; then
  echo -e "${RED}Usage: $0 <path-to-backup.sql.gz>${NC}"
  echo "Available backups:"
  ls -1 backups/*.sql.gz 2>/dev/null || echo "  (none found in backups/)"
  exit 1
fi

if ! docker compose ps postgres --format '{{.Health}}' 2>/dev/null | grep -qx healthy; then
  echo -e "${RED}postgres is not up/healthy — 'make up' or 'make demo' first.${NC}"
  exit 1
fi

echo -e "${YLW}This overwrites the current database ($PG_DB) with the contents of $FILE.${NC}"
read -p "Type 'yes' to continue: " confirm
[ "$confirm" = "yes" ] || { echo "Aborted."; exit 1; }

gunzip -c "$FILE" | docker compose exec -T postgres psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1

echo -e "${GRN}✓${NC} Restored from $FILE"
