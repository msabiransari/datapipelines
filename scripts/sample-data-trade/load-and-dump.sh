#!/usr/bin/env bash
# scripts/sample-data-trade/load-and-dump.sh — stage 3 of the trade/v1 build
# (mirrors ../sample-data/load-and-dump.sh, design §4.1): work/csv/ ->
# artifacts. The three engines of this family:
#
#   us_trade.duckdb      built in-process with the pinned DuckDB CLI — the
#                        file IS the artifact (same as SQLite), no container
#   mysql-trade.sql.gz   throwaway pinned MySQL container + mysqldump | gzip
#   crypto_market.db     sqlite3 .import, the file IS the artifact
#
# Row counts are asserted against the CSVs, content checksums recorded to
# work/artifacts/checksums.tsv (consumed by manifest.sh, re-derived by
# verify.sh through the shared lib/engines.sh — one implementation).

set -euo pipefail
SD_SCRIPT=load-and-dump
SD_ROOT="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SD_ROOT/../.." && pwd)"
source "$REPO_ROOT/scripts/sample-data/lib/common.sh"
source "$REPO_ROOT/scripts/sample-data/lib/engines.sh"

CSV="$SD_ROOT/work/csv"
ART="$SD_ROOT/work/artifacts"
[ -d "$CSV" ] || die "work/csv/ is missing — run transform.sh first"
mkdir -p "$ART"

require_cmd docker "the MySQL build engine is a pinned container image"
require_cmd sqlite3 "the crypto artifact IS a SQLite file, built with the sqlite3 CLI"
require_cmd gzip "the MySQL artifact is a gzipped mysqldump"

trap sd_cleanup_engines EXIT

CHECKSUMS="$ART/checksums.tsv"
: > "$CHECKSUMS"

assert_rows() {
  [ "$1" = "$2" ] || die "$3: loaded $1 row(s), expected $2 from work/csv/ — the load lost or duplicated data"
}
csv_rows() { echo $(( $(wc -l < "$1") - 1 )); }

DUCKDB=$(duckdb_bin)

# --- DUCKDB -------------------------------------------------------------------
step "duckdb: building us_trade.duckdb"
DUCK="$ART/us_trade.duckdb"
rm -f "$DUCK"
"$DUCKDB" "$DUCK" < "$SD_ROOT/ddl/duckdb-us-trade.sql"
for t in trade_monthly partners hs_chapters trade_flow_monthly partner_iso_crosswalk; do
  log "  COPY $t"
  "$DUCKDB" "$DUCK" -c "COPY $t FROM '$CSV/$t.csv' (FORMAT CSV, HEADER);" < /dev/null
  assert_rows "$(count_duckdb "$DUCK" "$t")" "$(csv_rows "$CSV/$t.csv")" "duckdb.$t"
done
# CHECKPOINT so every buffer reaches the file before it is hashed/published.
"$DUCKDB" "$DUCK" -c "CHECKPOINT;" < /dev/null

log "  checksums"
checksum_rows duckdb "$DUCK" >> "$CHECKSUMS"

# --- MYSQL --------------------------------------------------------------------
step "mysql: loading dp_sample_trade into a throwaway $(image_ref mysql)"
MY=$(engine_start_mysql_db trade dp_sample_trade)
mysql_in_db "$MY" dp_sample_trade < "$SD_ROOT/ddl/mysql-world-trade.sql"

for t in comtrade_annual reporters; do
  log "  LOAD DATA $t"
  docker cp "$CSV/$t.csv" "$MY:/tmp/$t.csv" >/dev/null
  mysql_in_db "$MY" dp_sample_trade --local-infile=1 -e "
    LOAD DATA LOCAL INFILE '/tmp/$t.csv' INTO TABLE $t
    FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '\"'
    LINES TERMINATED BY '\n' IGNORE 1 LINES;" < /dev/null
  assert_rows "$(docker exec -e MYSQL_PWD=build "$MY" mysql --protocol=TCP -h 127.0.0.1 -uroot -N --batch -D dp_sample_trade -e "SELECT count(*) FROM $t" < /dev/null)" "$(csv_rows "$CSV/$t.csv")" "mysql.$t"
done

log "  checksums"
# checksum_rows needs the mobility-shaped mysql_in wrapper's -N stream; use
# the generic handle by pointing the shared code at this container/db pair
# via a thin wrapper indirection.
checksum_mysql_trade() {
  local t="$1" o="$2"
  docker exec -e MYSQL_PWD=build "$MY" mysql --protocol=TCP -h 127.0.0.1 -uroot -N --batch -D dp_sample_trade -e "SELECT * FROM $t ORDER BY $o" < /dev/null | shasum -a 256 | awk '{print $1}'
}
count_mysql_trade() {
  docker exec -e MYSQL_PWD=build "$MY" mysql --protocol=TCP -h 127.0.0.1 -uroot -N --batch -D dp_sample_trade -e "SELECT count(*) FROM $1" < /dev/null
}
while read -r e t o; do
  [ "$e" = mysql ] || continue
  printf 'mysql\t%s\t%s\t%s\n' "$t" "$(count_mysql_trade "$t")" "$(checksum_mysql_trade "$t" "$o")"
done <<< "$(grep -v '^[[:space:]]*#' "$SD_ROOT/checksums.spec" | grep -v '^[[:space:]]*$')" >> "$CHECKSUMS"

log "  mysqldump | gzip"
docker exec -e MYSQL_PWD=build "$MY" mysqldump -uroot --no-tablespaces --skip-dump-date \
  --databases dp_sample_trade | gzip -n > "$ART/mysql-trade.sql.gz"

# --- SQLITE -------------------------------------------------------------------
step "sqlite: building crypto_market.db"
CRYPTO="$ART/crypto_market.db"
rm -f "$CRYPTO"
sqlite3 "$CRYPTO" < "$SD_ROOT/ddl/sqlite-crypto.sql"

SYMBOLS=$(lock_field param binance_symbols 3)
expected_klines=0
for sym in ${SYMBOLS//,/ }; do
  log "  .import klines_1h ($sym)"
  sqlite3 "$CRYPTO" <<SQL
.mode csv
.import --skip 1 '$CSV/klines_1h_${sym}.csv' klines_1h
SQL
  expected_klines=$(( expected_klines + $(csv_rows "$CSV/klines_1h_${sym}.csv") ))
done
assert_rows "$(count_sqlite "$CRYPTO" klines_1h)" "$expected_klines" "sqlite.klines_1h"

sqlite3 "$CRYPTO" <<SQL
.mode csv
.import --skip 1 '$SD_ROOT/data/symbols.csv' symbols
SQL
assert_rows "$(count_sqlite "$CRYPTO" symbols)" "$(csv_rows "$SD_ROOT/data/symbols.csv")" "sqlite.symbols"
sqlite3 "$CRYPTO" "VACUUM;"

log "  checksums"
checksum_rows sqlite "$CRYPTO" >> "$CHECKSUMS"

# --- examples -----------------------------------------------------------------
if [ -f "$SD_ROOT/content/examples.json" ]; then
  cp "$SD_ROOT/content/examples.json" "$ART/examples.json"
fi

step "artifacts"
ls -l "$ART" >&2
echo >&2
column -t -s "$(printf '\t')" "$CHECKSUMS" >&2
