#!/usr/bin/env bash
# scripts/sample-data/load-and-dump.sh — stage 3 of the sample-data build
# (design §4.1): work/csv/ -> throwaway engines -> work/artifacts/.
#
#   ./scripts/sample-data/load-and-dump.sh
#
# Loads each engine's CSVs into a THROWAWAY, project-scoped container (or, for
# SQLite, a throwaway file), asserts the row counts, records the content
# checksums, and dumps:
#
#   pg-trips.dump         pg_dump -Fc of dp_sample_trips
#   mysql-weather.sql.gz  mysqldump | gzip of dp_sample_weather
#   nyc_reference.db      the SQLite file itself
#
# The dump is taken from an engine of the SAME pinned major the demo restores
# into — pg_dump's custom format is not forward-compatible, and discovering that
# in someone's quickstart is exactly the failure this build exists to prevent.
#
# Row counts and checksums are written to work/artifacts/checksums.tsv, which
# manifest.sh embeds; verify.sh re-derives them from the dumps.

set -euo pipefail
SD_SCRIPT=load-and-dump
SD_ROOT="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SD_ROOT/../.." && pwd)"
source "$SD_ROOT/lib/common.sh"
source "$SD_ROOT/lib/engines.sh"

CSV="$SD_ROOT/work/csv"
ART="$SD_ROOT/work/artifacts"
[ -d "$CSV" ] || die "work/csv/ is missing — run transform.sh first"
mkdir -p "$ART"

require_cmd docker "the throwaway build engines are pinned container images"
require_cmd sqlite3 "the reference artifact IS a SQLite file, built with the sqlite3 CLI (design §4.1 stage 3)"
require_cmd gzip "the MySQL artifact is a gzipped mysqldump"

trap sd_cleanup_engines EXIT

CHECKSUMS="$ART/checksums.tsv"
: > "$CHECKSUMS"

# assert_rows <actual> <expected> <what>
assert_rows() {
  [ "$1" = "$2" ] || die "$3: loaded $1 row(s), expected $2 from work/csv/ — the load lost or duplicated data"
}
csv_rows() { echo $(( $(wc -l < "$1") - 1 )); }

# --- POSTGRES ---------------------------------------------------------------
step "postgres: loading dp_sample_trips into a throwaway $(image_ref postgres)"
PG=$(engine_start_postgres build)
psql_in "$PG" -q < "$SD_ROOT/ddl/postgres-trips.sql"

for t in trips trips_daily trips_monthly; do
  log "  COPY $t"
  # \copy streams from psql's stdin, so nothing is bind-mounted and no file has
  # to be readable by the container's postgres user.
  psql_in "$PG" -q -c "\copy $t FROM STDIN WITH (FORMAT csv, HEADER)" < "$CSV/$t.csv"
  assert_rows "$(count_pg "$PG" "$t")" "$(csv_rows "$CSV/$t.csv")" "postgres.$t"
done
# ANALYZE so the shipped dump restores with usable statistics: the example
# pipelines join a 5M-row table, and a fresh restore with no stats picks a plan
# that makes the demo look slow for a reason that has nothing to do with the product.
psql_in "$PG" -q -c "ANALYZE"

log "  checksums"
checksum_rows postgres "$PG" >> "$CHECKSUMS"

log "  pg_dump -Fc"
# --no-owner/--no-privileges: the demo restores as a different role than `build`.
docker exec "$PG" pg_dump -h 127.0.0.1 -U build -d dp_sample_trips -Fc --no-owner --no-privileges > "$ART/pg-trips.dump"

# --- MYSQL ------------------------------------------------------------------
step "mysql: loading dp_sample_weather into a throwaway $(image_ref mysql)"
MY=$(engine_start_mysql build)
mysql_in "$MY" < "$SD_ROOT/ddl/mysql-weather.sql"

for t in stations observations; do
  log "  LOAD DATA $t"
  # docker cp then LOCAL INFILE: `LOAD DATA INFILE` (no LOCAL) is bound by
  # secure_file_priv, and LOCAL from /dev/stdin is not portable across client
  # builds. Copying into the container makes the client-side read a plain file.
  docker cp "$CSV/$t.csv" "$MY:/tmp/$t.csv" >/dev/null
  mysql_in "$MY" --local-infile=1 -e "
    LOAD DATA LOCAL INFILE '/tmp/$t.csv' INTO TABLE $t
    FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '\"'
    LINES TERMINATED BY '\n' IGNORE 1 LINES;"
  assert_rows "$(count_mysql "$MY" "$t")" "$(csv_rows "$CSV/$t.csv")" "mysql.$t"
done

log "  checksums"
checksum_rows mysql "$MY" >> "$CHECKSUMS"

log "  mysqldump | gzip"
# --no-tablespaces: the restoring user is not granted PROCESS on the demo server.
# --skip-dump-date: a timestamp comment would make two identical builds differ
# in the dump bytes for no reason, which is noise in exactly the diff that has
# to be readable.
docker exec -e MYSQL_PWD=build "$MY" mysqldump -uroot --no-tablespaces --skip-dump-date \
  --databases dp_sample_weather | gzip -n > "$ART/mysql-weather.sql.gz"

# --- SQLITE -----------------------------------------------------------------
step "sqlite: building nyc_reference.db"
REF="$ART/nyc_reference.db"
rm -f "$REF"
sqlite3 "$REF" < "$SD_ROOT/ddl/sqlite-reference.sql"
for t in zones rate_codes payment_types calendar; do
  log "  .import $t"
  sqlite3 "$REF" <<SQL
.mode csv
.import --skip 1 '$CSV/$t.csv' $t
SQL
  assert_rows "$(count_sqlite "$REF" "$t")" "$(csv_rows "$CSV/$t.csv")" "sqlite.$t"
done
# VACUUM after the imports: the file IS the artifact, so its size is what every
# consumer downloads, and the import leaves free pages behind.
sqlite3 "$REF" "VACUUM;"

log "  checksums"
checksum_rows sqlite "$REF" >> "$CHECKSUMS"

# --- examples ---------------------------------------------------------------
# examples.json ships in the artifact set (design §4) and is authored content,
# not built content — copied here so manifest.sh has one directory to hash.
cp "$SD_ROOT/content/examples.json" "$ART/examples.json"

step "artifacts"
ls -l "$ART" >&2
echo >&2
column -t -s "$(printf '\t')" "$CHECKSUMS" >&2
