#!/usr/bin/env bash
# scripts/sample-data-trade/verify.sh — the proof stage (mirrors
# ../sample-data/verify.sh): re-derive EVERYTHING the manifest claims from
# the artifacts alone, with no access to work/raw or work/csv.
#
#   ./scripts/sample-data-trade/verify.sh [artifact-dir]
#
# Default artifact-dir is work/artifacts. Exit 0 only if every artifact
# hash, every row count and every table checksum re-derives exactly.

set -euo pipefail
SD_SCRIPT=verify
SD_ROOT="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SD_ROOT/../.." && pwd)"
source "$REPO_ROOT/scripts/sample-data/lib/common.sh"
source "$REPO_ROOT/scripts/sample-data/lib/engines.sh"

ART="${1:-$SD_ROOT/work/artifacts}"
[ -f "$ART/manifest.json" ] || die "no manifest.json in '$ART' — nothing to verify"
require_cmd sqlite3 "verifying fx_rates.db"
require_cmd python3 "reading manifest.json"
trap sd_cleanup_engines EXIT

failures=0
check() { # <label> <status>
  if [ "$2" = 0 ]; then log "  ok    $1"; else log "  FAIL  $1"; failures=$((failures + 1)); fi
}

python3 - "$ART" <<'PY' > "$SD_ROOT/work/verify-expected.txt"
import json, sys
m = json.load(open(f"{sys.argv[1]}/manifest.json"))
for a in m["artifacts"]:
    print(f"{a['file']}\t{a['sha256']}\t{a['bytes']}")
for t in m["tables"]:
    print(f"TABLE\t{t['engine']}\t{t['table']}\t{t['rows']}\t{t['sha256']}")
PY

expected_rows() { awk -F'\t' -v e="$1" -v t="$2" '$1=="TABLE" && $2==e && $3==t {print $4}' "$SD_ROOT/work/verify-expected.txt"; }
expected_ck()   { awk -F'\t' -v e="$1" -v t="$2" '$1=="TABLE" && $2==e && $3==t {print $5}' "$SD_ROOT/work/verify-expected.txt"; }

verify_table() { # <engine> <table> <count> <checksum>
  local e="$1" t="$2" n="$3" ck="$4" want_n want_ck
  want_n=$(expected_rows "$e" "$t"); want_ck=$(expected_ck "$e" "$t")
  if [ -n "$want_n" ] && [ "$n" = "$want_n" ] && [ "$ck" = "$want_ck" ]; then
    log "  ok    $e.$t ($n rows)"
  else
    log "  FAIL  $e.$t — got $n rows / $ck, manifest says ${want_n:-<missing>} / ${want_ck:-<missing>}"
    failures=$((failures + 1))
  fi
}

step "artifact hashes + sizes"
while IFS=$'\t' read -r name want wantbytes rest; do
  if [ "$name" = TABLE ]; then continue; fi
  got=$(sha256_of "$ART/$name")
  gotbytes=$(stat -f %z "$ART/$name" 2>/dev/null || stat -c %s "$ART/$name")
  if [ "$got" = "$want" ] && [ "$gotbytes" = "$wantbytes" ]; then
    check "$name ($wantbytes bytes)" 0
  else
    check "$name (hash ${got:0:12}…, $gotbytes bytes — manifest says ${want:0:12}…, $wantbytes bytes)" 1
  fi
done < "$SD_ROOT/work/verify-expected.txt"

step "table contents (row counts + ordered-stream checksums)"

DUCK="$ART/us_trade.duckdb"
FX="$ART/fx_rates.db"

# MySQL: restore the dump into a throwaway engine, then fingerprint there.
MY=$(engine_start_mysql_db verify dp_sample_trade)
gunzip -c "$ART/mysql-trade.sql.gz" | docker exec -i -e MYSQL_PWD=build "$MY" \
  mysql --protocol=TCP -h 127.0.0.1 -uroot || die "restoring mysql-trade.sql.gz into the throwaway engine failed"

# Process substitution (not `grep | while`): the loop must run in THIS shell
# or every verify_table failure would be lost to the subshell and a broken
# artifact would verify green.
while read -r e t o; do
  case "$e" in
    duckdb)
      verify_table "$e" "$t" "$(count_duckdb "$DUCK" "$t")" "$(checksum_duckdb "$DUCK" "$t" "$o")" ;;
    sqlite)
      verify_table "$e" "$t" "$(count_sqlite "$FX" "$t")" "$(checksum_sqlite "$FX" "$t" "$o")" ;;
    mysql)
      n=$(docker exec -e MYSQL_PWD=build "$MY" mysql --protocol=TCP -h 127.0.0.1 -uroot -N --batch -D dp_sample_trade -e "SELECT count(*) FROM $t" < /dev/null)
      ck=$(docker exec -e MYSQL_PWD=build "$MY" mysql --protocol=TCP -h 127.0.0.1 -uroot -N --batch -D dp_sample_trade -e "SELECT * FROM $t ORDER BY $o" < /dev/null | shasum -a 256 | awk '{print $1}')
      verify_table "$e" "$t" "$n" "$ck" ;;
  esac
done < <(grep -v '^[[:space:]]*#' "$SD_ROOT/checksums.spec" | grep -v '^[[:space:]]*$')

nulls=$(python3 -c "
import json
m = json.load(open('$ART/manifest.json'))
print(sum(1 for p in m['provenance'] if not p['license_verified']))")
log "provenance rows with license_verified=null: $nulls (publishing with any null blocks go-live, design §8)"

[ "$failures" = 0 ] || die "$failures verification failure(s)"
log "OK — every artifact and table re-derived exactly from the artifacts"
