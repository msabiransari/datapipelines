#!/usr/bin/env bash
# shellcheck source-path=SCRIPTDIR
# scripts/sample-data-lake/transform.sh — stage 2 of the dp-lake build:
# work/raw/*.parquet -> work/tables/ (Parquet) and work/iceberg/ (Iceberg),
# using the pinned DuckDB CLI and, for the Iceberg table alone, pinned pyiceberg.
#
#   ./scripts/sample-data-lake/transform.sh                  # every month, then finalize
#   ./scripts/sample-data-lake/transform.sh --month 2024-12  # one month, no finalize
#   ./scripts/sample-data-lake/transform.sh --finalize       # the cross-month tables only
#
# Two stages because the window is ~11.6 GB of input:
#   PER MONTH   — the partitioned `hvfhv_trips` day partitions and that month's
#                 `hvfhv_trips_sample` file. Reads exactly one raw file, so the raw
#                 file can be deleted straight after (README "Low-disk build").
#   FINALIZE    — `hvfhs_companies`, the `hvfhv_zone_day` pre-aggregate (which needs
#                 every month), and the Iceberg copy of the sample.
#
# DETERMINISM — the same three rules the mobility family states, plus one this
# family has to add:
#   1. No RNG anywhere. The sample is `hash(<natural key columns>) % <modulus> = 0`,
#      so the same trips are selected on every machine, every run. DuckDB's hash()
#      is stable within a version and the version is pinned; bumping it changes the
#      sample, which is why a bump must bump the artifact version too.
#   2. Every emitted table has a declared TOTAL order (checksums.spec), and the
#      manifest pins a checksum of the stream in that order.
#   3. There is no surrogate key. A trip record has no id and none is invented: a
#      row_number() would depend on which months had been transformed when it ran.
#   4. **The contract is CONTENTS, not bytes** — and here it has to be, three times
#      over. A partitioned Parquet write does not promise a row order inside a file;
#      DuckDB may change its encoder between versions; and pyiceberg stamps a fresh
#      table UUID, snapshot id and timestamp on every run. So `manifest.json` pins
#      each published OBJECT's SHA-256 (so a consumer can prove it got the bytes we
#      published) AND each TABLE's row count and ordered-stream checksum (so a
#      rebuild can prove it produced the same data). Only the second survives a
#      rebuild. This is the mobility family's pg_dump argument, one step further.
#
# WHY THE ICEBERG TABLE IS WRITTEN WITH ITS FINAL s3:// PATHS — see iceberg_write.py.
# An Iceberg table records ABSOLUTE locations in its metadata and manifests, so it
# cannot be built locally and moved. Measured 2026-09-07 against the pinned DuckDB
# and this pyiceberg: a table whose metadata still names the staging directory is
# unreadable once that directory is gone, and `iceberg_scan(..., allow_moved_paths
# := true)` does not rescue it (it resolves the argument as a table DIRECTORY, so a
# metadata-file argument becomes `<file>/metadata/...`). The writer therefore builds
# the table already believing it lives at `s3://…/<version>/hvfhv_trips_iceberg`.

set -euo pipefail
SD_SCRIPT=transform
SD_ROOT="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SD_ROOT/../.." && pwd)"
# shellcheck source=../sample-data/lib/common.sh
source "$REPO_ROOT/scripts/sample-data/lib/common.sh"
# shellcheck source=lib/lake.sh
source "$SD_ROOT/lib/lake.sh"

ONLY_MONTH=""
DO_FINALIZE=1
DO_MONTHS=1
while [ $# -gt 0 ]; do
  case "$1" in
    --month) ONLY_MONTH="${2:-}"; DO_FINALIZE=0; shift 2 ;;
    --month=*) ONLY_MONTH="${1#--month=}"; DO_FINALIZE=0; shift ;;
    --finalize) DO_MONTHS=0; DO_FINALIZE=1; shift ;;
    *) die "unknown argument '$1' — usage: $0 [--month YYYY-MM | --finalize]" ;;
  esac
done

RAW="$SD_ROOT/work/raw"
TABLES="$SD_ROOT/work/tables"
ICE="$SD_ROOT/work/iceberg"
# <month>\t<clean rows>\t<sampled rows>, one line per transformed month, written by
# the writing session and cross-checked by manifest.sh (see transform_month).
MONTHLY_COUNTS="$TABLES/monthly-counts.tsv"
mkdir -p "$TABLES" "$ICE"

WINDOW_START=$(lock_field param window_start)
WINDOW_END=$(lock_field param window_end)
MODULUS=$(lock_field param trips_sample_modulus)
[ -n "$WINDOW_START" ] && [ -n "$WINDOW_END" ] && [ -n "$MODULUS" ] \
  || die "sources.lock is missing one of the param lines this stage needs"

# The window as inclusive DATE bounds. `window_end` names a MONTH, so its bound is
# the last day of that month — computed by the shared helper, never hand-written
# (lib/common.sh: window_day_end).
DAY_START="$WINDOW_START-01"
DAY_END=$(window_day_end "$WINDOW_END")

DUCKDB=$(duckdb_bin)

# The published column list, in ONE place: the SELECT list of the trip tables and
# the ORDER BY that makes their stream reproducible. checksums.spec states the same
# order for the verifier; they are compared below so the two cannot drift silently.
TRIP_COLUMNS="pickup_at, dropoff_at, pu_location_id, do_location_id, trip_miles, trip_time_s, base_fare, tips, driver_pay, shared_request, hvfhs_license_num"
spec_order=$(lake_spec_field hvfhv_trips 4)
[ "$spec_order" = "$TRIP_COLUMNS" ] \
  || die "checksums.spec's ORDER BY for hvfhv_trips has drifted from transform.sh's column list:
  checksums.spec : $spec_order
  transform.sh   : $TRIP_COLUMNS
  The verifier would then re-derive a checksum over a different stream than the build wrote."

# --- the shared view definitions -------------------------------------------
#
# Emitted into every per-month DuckDB invocation. Every filter is a data-quality
# fact about the HVFHV feed, not a taste preference:
#   * pickup_datetime strays outside the file's own month; the window filter is what
#     makes the "pinned 24-month window" claim true of the CONTENTS, not the inputs.
#   * a dropoff before its pickup is unusable for any duration question.
#   * PU/DO location ids outside 1..265 do not join to the TLC zone lookup, which is
#     what `sample-reference.zones` holds — a lake row that cannot join to a borough
#     is a row no example pipeline can use.
#   * negative money and negative distance are adjustments; they would make every
#     SUM a lie in a demo that does not explain them.
#   * trip_time = 0 with trip_miles = 0 is a cancelled/void record.
# The company mapping is NOT a filter: an unmapped licence number FAILS the build
# (the assert below), because dropping it would silently shrink the population.
views_sql() { # <raw-parquet-glob>
  cat <<SQL
CREATE OR REPLACE VIEW raw_trips AS
  SELECT * FROM read_parquet('$1', union_by_name = true);

-- union_by_name: PULocationID/DOLocationID are BIGINT in the 2023-01 file and
-- INTEGER from 2023-02 on (verified against the upstream footers, not assumed).
-- Both fold into the same CAST below, so the drift is invisible downstream — but
-- a positional union would have silently mixed columns, so it is stated.
CREATE OR REPLACE VIEW clean_trips AS
SELECT
    pickup_datetime                             AS pickup_at,
    dropoff_datetime                            AS dropoff_at,
    CAST(pickup_datetime AS DATE)               AS pickup_date,
    CAST(PULocationID AS SMALLINT)              AS pu_location_id,
    CAST(DOLocationID AS SMALLINT)              AS do_location_id,
    CAST(trip_miles AS DECIMAL(9,2))            AS trip_miles,
    CAST(trip_time AS INTEGER)                  AS trip_time_s,
    CAST(base_passenger_fare AS DECIMAL(10,2))  AS base_fare,
    CAST(tips AS DECIMAL(10,2))                 AS tips,
    CAST(driver_pay AS DECIMAL(10,2))           AS driver_pay,
    CASE shared_request_flag WHEN 'Y' THEN TRUE WHEN 'N' THEN FALSE END AS shared_request,
    hvfhs_license_num                           AS hvfhs_license_num
FROM raw_trips
WHERE pickup_datetime  >= DATE '$DAY_START'
  AND pickup_datetime  <  DATE '$DAY_END' + INTERVAL 1 DAY
  AND dropoff_datetime >= pickup_datetime
  AND PULocationID BETWEEN 1 AND 265
  AND DOLocationID BETWEEN 1 AND 265
  AND trip_miles          >= 0
  AND trip_time            > 0
  AND base_passenger_fare >= 0
  AND tips                >= 0
  AND driver_pay          >= 0
  AND shared_request_flag IN ('Y', 'N');

-- HASH SAMPLING, NOT RNG. The hash is over the natural key of a trip — which
-- company, when, where, how far, how much — so it is a property of the row itself
-- and survives any change in file order or partitioning. It is computed on the
-- CAST values, so the same row hashes identically whichever month's file it
-- arrives in (the 2023-01 BIGINT/INTEGER drift above would otherwise move rows in
-- and out of the sample).
CREATE OR REPLACE VIEW sampled_trips AS
SELECT * FROM clean_trips
WHERE hash(hvfhs_license_num, pickup_at, dropoff_at, pu_location_id, do_location_id,
           trip_miles, trip_time_s, base_fare, tips, driver_pay) % $MODULUS = 0;

CREATE OR REPLACE VIEW ref_companies AS
  SELECT * FROM read_csv('$SD_ROOT/data/hvfhs-companies.csv', header = true, comment = '#');
SQL
}

# The lookup-coverage assert, run per month against that month's rows: a licence
# number present in the data with no row in data/hvfhs-companies.csv would produce a
# NULL company in every aggregate. Fail the build instead.
assert_sql() {
  cat <<'SQL'
SELECT CASE WHEN count(*) = 0 THEN 'ok'
            ELSE error('hvfhs_license_num values in this month with no row in data/hvfhs-companies.csv: '
                       || string_agg(DISTINCT hvfhs_license_num, ',')) END
FROM clean_trips
WHERE hvfhs_license_num NOT IN (SELECT hvfhs_license_num FROM ref_companies);
SQL
}

# --- per-month stage --------------------------------------------------------

transform_month() { # <YYYY-MM>
  local month="$1" id raw sample_out
  id="tlc-hvfhv-$month.parquet"
  raw="$RAW/$id"
  [ -f "$raw" ] || die "$raw is missing — run ./scripts/sample-data-lake/download.sh --month $month first"
  sample_out="$TABLES/hvfhv_trips_sample/part-$month.parquet"
  mkdir -p "$TABLES/hvfhv_trips_sample"

  step "$month: partitioning the full table and writing the 1-in-$MODULUS sample"
  "$DUCKDB" -c "
$(views_sql "$raw")
$(assert_sql)

-- The big prunable table: one directory per pickup DAY, hive-style, so a query
-- with a date predicate reads only the days it names. pickup_date is the partition
-- column and therefore lives in the path, not in the file.
COPY (
  SELECT $TRIP_COLUMNS, pickup_date
  FROM clean_trips
  ORDER BY $TRIP_COLUMNS
) TO '$TABLES/hvfhv_trips'
  (FORMAT PARQUET, COMPRESSION ZSTD, PARTITION_BY (pickup_date),
   FILENAME_PATTERN 'part-{i}', OVERWRITE_OR_IGNORE);

-- The table a first agent question hits: small enough to scan whole, one file per
-- month so the glob is 24 objects rather than 730.
COPY (
  SELECT $TRIP_COLUMNS
  FROM sampled_trips
  ORDER BY $TRIP_COLUMNS
) TO '$sample_out' (FORMAT PARQUET, COMPRESSION ZSTD);
" >&2

  # The SOURCE-SIDE row counts, recorded by the session that did the write, from the
  # views the write read. manifest.sh derives its counts from the PUBLISHED objects
  # and dies if the two disagree — two observed derivations of the same number, from
  # opposite ends of the write. Without this, a partition write that silently
  # dropped rows would be recorded faithfully and verified happily.
  mkdir -p "$(dirname "$MONTHLY_COUNTS")"
  local counted
  counted=$("$DUCKDB" -noheader -list -c "
$(views_sql "$raw")
SELECT (SELECT count(*) FROM clean_trips) || '|' || (SELECT count(*) FROM sampled_trips);")
  # Replace any earlier line for this month rather than appending a second one.
  if [ -f "$MONTHLY_COUNTS" ]; then
    grep -v "^$month	" "$MONTHLY_COUNTS" > "$MONTHLY_COUNTS.tmp" || true
    mv "$MONTHLY_COUNTS.tmp" "$MONTHLY_COUNTS"
  fi
  printf '%s\t%s\t%s\n' "$month" "${counted%%|*}" "${counted##*|}" >> "$MONTHLY_COUNTS"

  log "$month: ${counted%%|*} trips, ${counted##*|} sampled — $(du -sh "$sample_out" | awk '{print $1}') sample file, full-table partitions now $(du -sh "$TABLES/hvfhv_trips" | awk '{print $1}')"
}

# --- finalize ---------------------------------------------------------------

finalize() {
  local months_expected months_present
  months_expected=$(lake_months "$WINDOW_START" "$WINDOW_END" | wc -l | tr -d ' ')
  months_present=$(find "$TABLES/hvfhv_trips_sample" -name 'part-*.parquet' 2>/dev/null | wc -l | tr -d ' ')
  # NON-VACUITY: finalize aggregates over whatever is on disk, so "it ran clean"
  # over half a window is the failure mode that produces a plausible, wrong
  # artifact. The count is checked against the LOCK, not against itself.
  [ "$months_present" = "$months_expected" ] \
    || die "finalize needs all $months_expected months of the pinned window; found $months_present sample file(s) in $TABLES/hvfhv_trips_sample.
  Run transform.sh --month for the missing ones first."

  step "companies lookup and the zone/day pre-aggregate over all $months_expected months"
  mkdir -p "$TABLES/hvfhs_companies" "$TABLES/hvfhv_zone_day"
  "$DUCKDB" -c "
CREATE OR REPLACE VIEW ref_companies AS
  SELECT * FROM read_csv('$SD_ROOT/data/hvfhs-companies.csv', header = true, comment = '#');

COPY (SELECT hvfhs_license_num, company, display_name FROM ref_companies ORDER BY hvfhs_license_num)
  TO '$TABLES/hvfhs_companies/part-0.parquet' (FORMAT PARQUET, COMPRESSION ZSTD);

-- Pre-aggregated trips and money per pickup zone x day x company. This is the
-- table the four-engine showcase pipeline joins: it answers 'rideshare share by
-- borough on rainy days' without any pipeline having to scan 400M trip rows.
-- Read back from the PUBLISHED partitioned table, not from the raw files: what is
-- aggregated is exactly what is published, so the two can never disagree.
COPY (
  SELECT t.pickup_date                                       AS pickup_date,
         t.pu_location_id                                    AS pu_location_id,
         c.company                                           AS company,
         count(*)                                            AS trip_count,
         CAST(sum(t.base_fare)    AS DECIMAL(16,2))          AS total_base_fare,
         CAST(sum(t.tips)         AS DECIMAL(16,2))          AS total_tips,
         CAST(sum(t.driver_pay)   AS DECIMAL(16,2))          AS total_driver_pay,
         CAST(sum(t.trip_miles)   AS DECIMAL(16,2))          AS total_miles,
         CAST(sum(t.trip_time_s)  AS BIGINT)                 AS total_trip_time_s,
         count(*) FILTER (WHERE t.shared_request)            AS shared_request_count
  FROM read_parquet('$TABLES/hvfhv_trips/**/*.parquet', hive_partitioning = true) t
  JOIN ref_companies c ON c.hvfhs_license_num = t.hvfhs_license_num
  GROUP BY 1, 2, 3
  ORDER BY $(lake_spec_field hvfhv_zone_day 4)
) TO '$TABLES/hvfhv_zone_day/part-0.parquet' (FORMAT PARQUET, COMPRESSION ZSTD);
" >&2

  step "Iceberg copy of the sample (pyiceberg $(lock_field param pyiceberg_version))"
  "$SD_ROOT/lib/iceberg-venv.sh" run "$SD_ROOT/lib/iceberg_write.py" \
    --sample-glob "$TABLES/hvfhv_trips_sample/part-*.parquet" \
    --stage "$ICE" \
    --table-uri "$(lake_publish_prefix)/hvfhv_trips_iceberg" > "$ICE/iceberg-write.json"
  log "Iceberg: $(python3 -c "import json;d=json.load(open('$ICE/iceberg-write.json'));print(d['row_count'],'rows,',d['data_files'],'data files at',d['metadata_location'])")"
}

# --- run --------------------------------------------------------------------

if [ "$DO_MONTHS" = 1 ]; then
  if [ -n "$ONLY_MONTH" ]; then
    lake_months "$WINDOW_START" "$WINDOW_END" | grep -qx "$ONLY_MONTH" \
      || die "--month $ONLY_MONTH is outside the pinned window $WINDOW_START..$WINDOW_END"
    transform_month "$ONLY_MONTH"
  else
    for month in $(lake_months "$WINDOW_START" "$WINDOW_END"); do
      transform_month "$month"
    done
  fi
fi

if [ "$DO_FINALIZE" = 1 ]; then
  finalize
fi

step "transform complete"
du -sh "$TABLES"/* "$ICE"/* 2>/dev/null >&2 || true
