#!/usr/bin/env bash
#
# Migration drill — validate the Flyway migrations against a REAL Postgres
# restored from a production backup, end to end, in a throwaway container.
#
# Why this exists: the JUnit suite is Mockito-only (no DB — see CLAUDE.md
# "Testing"), so nothing in `./gradlew build` actually runs the migrations.
# Schema/data bugs in a migration (a bad CHECK, a wrong UPDATE, a 0-price the
# constraint rejects) only surface at app startup. This script is the gate that
# catches them before push, and the seed for a future Testcontainers slice.
#
# It restores the backup, applies every migration newer than what the backup
# already has, then asserts the invariants that matter for the catalog/
# versioning contract. Any failure exits non-zero.
#
# Usage:
#   scripts/migrate-drill.sh [path-to-backup.sql.gz]
#
# Requires: Docker running, gunzip. Default backup path can be overridden by the
# arg or the BACKUP env var.

set -euo pipefail

BACKUP="${1:-${BACKUP:-/c/Users/AndriyKushka/Downloads/daily_majstr-db-2026-06-12-193550.sql.gz}}"
MIG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/src/main/resources/db/migration"
CONTAINER="majstr_migrate_drill"
DB="majstr_drill"
PG_IMAGE="postgres:18"

# Allowed enum values — keep in sync with the entity enums + CHECK constraints.
ALLOWED_TRADES="ELECTRICAL PLUMBING TILING BUILDER PAINTER DRYWALL FLOORING DEMOLITION GENERAL OTHER"
ALLOWED_UNITS="M2 M LINEAR_METER PIECE KG HOUR SET M3 T POINT PERCENT"

[ -f "$BACKUP" ] || { echo "FAIL: backup not found: $BACKUP" >&2; exit 1; }

cleanup() { docker rm -f "$CONTAINER" >/dev/null 2>&1 || true; }
trap cleanup EXIT

psql() { docker exec -i "$CONTAINER" psql -U postgres -d "$DB" "$@"; }
scalar() { psql -tAc "$1" | tr -d '[:space:]'; }

echo "=== boot $PG_IMAGE ==="
cleanup
docker run -d --name "$CONTAINER" -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB="$DB" "$PG_IMAGE" >/dev/null
for _ in $(seq 1 30); do
  docker exec "$CONTAINER" pg_isready -U postgres -d "$DB" >/dev/null 2>&1 && break
  sleep 1
done

echo "=== restore backup ($(basename "$BACKUP")) ==="
gunzip -c "$BACKUP" | psql -v ON_ERROR_STOP=1 -q >/dev/null

# Apply every migration whose Vnn number is greater than the max already in the
# restored schema-history — so the drill stays correct as new migrations land.
APPLIED_MAX="$(scalar "SELECT COALESCE(MAX(CAST(version AS INT)), 0) FROM flyway_schema_history WHERE version ~ '^[0-9]+$';")"
echo "=== backup is at V${APPLIED_MAX}; applying newer migrations ==="
for f in $(ls "$MIG_DIR"/V*.sql | sort -V); do
  ver="$(basename "$f" | sed -E 's/^V([0-9]+)__.*/\1/')"
  if [ "$ver" -gt "$APPLIED_MAX" ]; then
    echo "  -> V$ver"
    psql -v ON_ERROR_STOP=1 -q < "$f"
  fi
done

echo "=== assert invariants ==="
fail=0
check() { # name, actual, expected
  if [ "$2" = "$3" ]; then echo "  ok   $1 = $2"; else echo "  FAIL $1 = $2 (expected $3)"; fail=1; fi
}

# 1. BUG #1 GUARD: every pre-existing user stays at version 0 (the migration must
#    NOT bulk-set them to v1, or "Add new from catalog" wrongly says "nothing new").
bad_sync="$(scalar "SELECT COUNT(*) FROM users WHERE last_synced_catalog_version <> 0;")"
check "users not at version 0" "$bad_sync" "0"

# 2. Versioning columns exist and are sane.
maxver="$(scalar "SELECT COALESCE(MAX(added_in_version), 0) FROM catalog_templates;")"
[ "$maxver" -ge 1 ] && echo "  ok   max(added_in_version) = $maxver (>=1)" || { echo "  FAIL max(added_in_version) = $maxver (<1)"; fail=1; }
null_ver="$(scalar "SELECT COUNT(*) FROM catalog_templates WHERE added_in_version IS NULL;")"
check "templates with NULL added_in_version" "$null_ver" "0"

# 3. Every template trade/unit is within the allowed enum sets.
bad_trades="$(scalar "SELECT COUNT(*) FROM catalog_templates WHERE trade NOT IN ($(printf "'%s'," $ALLOWED_TRADES | sed 's/,$//'));")"
check "templates with unknown trade" "$bad_trades" "0"
bad_units="$(scalar "SELECT COUNT(*) FROM catalog_templates WHERE unit NOT IN ($(printf "'%s'," $ALLOWED_UNITS | sed 's/,$//'));")"
check "templates with unknown unit" "$bad_units" "0"

# 4. A 0-price catalog_item must insert (the relaxed >= 0 CHECK). The CHECK
#    rejecting 0 → psql exits non-zero under ON_ERROR_STOP; accepting → exit 0.
if psql -v ON_ERROR_STOP=1 -q -c "INSERT INTO catalog_items (id, owner_id, name, type, unit, default_price, created_at)
       SELECT gen_random_uuid(), id, 'drill 0-price probe', 'WORK', 'POINT', 0, now() FROM users LIMIT 1;" >/dev/null 2>&1; then
  echo "  ok   0-price catalog_item insert accepted"
else
  echo "  FAIL 0-price catalog_item insert rejected by CHECK"; fail=1
fi

# 5. V31 enrichment: prices filled, new positions added, templates expanded.
maxver2="$(scalar "SELECT COALESCE(MAX(added_in_version), 0) FROM catalog_templates;")"
check "max(added_in_version) after V31" "$maxver2" "2"
new_v2="$(scalar "SELECT COUNT(*) FROM catalog_templates WHERE added_in_version = 2;")"
check "new positions at version 2" "$new_v2" "62"
# After the price fill almost everything is priced; a handful may stay 0 only if a
# fill resolved to <= 0 (none do today) — assert no unpriced default remains.
unpriced="$(scalar "SELECT COUNT(*) FROM catalog_templates WHERE suggested_price = 0;")"
check "unpriced default templates" "$unpriced" "0"
def_tpls="$(scalar "SELECT COUNT(*) FROM estimate_templates WHERE is_default = TRUE;")"
check "default estimate templates" "$def_tpls" "102"
# Every default estimate_template has at least one item (no empty bundle shipped).
empty_tpls="$(scalar "SELECT COUNT(*) FROM estimate_templates t WHERE t.is_default = TRUE AND NOT EXISTS (SELECT 1 FROM estimate_template_items i WHERE i.template_id = t.id);")"
check "default templates with no items" "$empty_tpls" "0"

echo "=== summary (informational) ==="
psql -P pager=off -c "SELECT trade, COUNT(*), COUNT(*) FILTER (WHERE suggested_price > 0) AS priced
                      FROM catalog_templates GROUP BY trade ORDER BY 2 DESC;"
echo "  total templates: $(scalar "SELECT COUNT(*) FROM catalog_templates;")"
echo "  distinct units : $(scalar "SELECT string_agg(DISTINCT unit, ',' ORDER BY unit) FROM catalog_templates;")"

if [ "$fail" -ne 0 ]; then
  echo "=== DRILL FAILED ===" >&2
  exit 1
fi
echo "=== DRILL PASSED: migrations apply cleanly and invariants hold ==="
