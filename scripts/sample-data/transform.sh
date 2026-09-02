#!/usr/bin/env bash
# scripts/sample-data/transform.sh — stage 2 of the sample-data build
# (design §4.1): work/raw/ -> work/csv/, using the pinned DuckDB CLI.
#
#   ./scripts/sample-data/transform.sh
#   ./scripts/sample-data/transform.sh --repin   # rewrite the NOAA window pins
#
# DuckDB is a BUILD tool here, never a runtime dialect (D2). It is used because
# it reads TLC's Parquet natively and emits CSV, which is the whole of this stage.
#
# DETERMINISM (D8) — three rules, each of which the rest of the build depends on:
#   1. No RNG anywhere. The trips sample is `hash(<natural key columns>) %
#      <modulus> = 0`, so the SAME rows are selected on every machine, every run.
#      DuckDB's hash() is stable within a version and the version is pinned in
#      sources.lock; bumping it changes the sample, which is why a bump must bump
#      the artifact version too.
#   2. Every emitted CSV is ordered by a TOTAL order over its own columns, so the
#      byte stream is identical run to run. Where ties are possible the tied rows
#      are identical in every column, so the resulting table is the same set
#      either way.
#   3. `trip_id` is assigned by row_number() over that same total order, not by a
#      sequence — a sequence would depend on load order.
#
# NOAA CONTENT PINS: the GHCN source files are appended to nightly and NCEI
# publishes no dated snapshot, so sources.lock marks them `unpinned:appended-daily`
# and pins instead the SHA-256 of the canonical extract this script takes. That
# extract is what a re-published QC revision would change; an appended future day
# is outside the window and cannot.

set -euo pipefail
SD_SCRIPT=transform
SD_ROOT="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SD_ROOT/../.." && pwd)"
source "$SD_ROOT/lib/common.sh"

REPIN=0
[ "${1:-}" = "--repin" ] && REPIN=1

RAW="$SD_ROOT/work/raw"
CSV="$SD_ROOT/work/csv"
rm -rf "$CSV"
mkdir -p "$CSV"

WINDOW_START=$(lock_field param window_start)
WINDOW_END=$(lock_field param window_end)
MODULUS=$(lock_field param trips_sample_modulus)
STATIONS=$(lock_field param ghcn_stations)
[ -n "$WINDOW_START" ] && [ -n "$WINDOW_END" ] && [ -n "$MODULUS" ] && [ -n "$STATIONS" ] \
  || die "sources.lock is missing one of the param lines this stage needs"

# The window as inclusive DATE bounds. `window_end` names a MONTH, so its bound
# is the last day of that month — computed, never hand-written (the one shared
# derivation lives in lib/common.sh: window_day_end, 045 §B).
DAY_START="$WINDOW_START-01"
DAY_END=$(window_day_end "$WINDOW_END")

DUCKDB=$(duckdb_bin)
log "DuckDB $(basename "$DUCKDB"), window $DAY_START..$DAY_END, trips modulus $MODULUS"

# --- trips ------------------------------------------------------------------
#
# The parquet column `airport_fee` is spelled `Airport_fee` from 2024-01 on.
# DuckDB resolves identifiers case-insensitively and union_by_name matches on
# the folded name, so ONE column comes back — verified against both files
# rather than assumed. `union_by_name=true` is what makes that true; without it
# a positional union would silently mix the two.
step "trips: sampling and normalizing $( ls "$RAW"/tlc-yellow-*.parquet | wc -l | tr -d ' ' ) monthly parquet files"
"$DUCKDB" -c "
CREATE OR REPLACE VIEW raw_trips AS
SELECT * FROM read_parquet('$RAW/tlc-yellow-*.parquet', union_by_name = true);

-- The sampled, cleaned population. Every filter below is a data-quality fact
-- about TLC's files, not a taste preference:
--   * tpep_pickup_datetime strays outside the file's own month (TLC ships a
--     handful of 2001 and 2098 timestamps); the window filter is what makes the
--     'pinned 24-month window' claim true of the CONTENTS, not just the inputs.
--   * dropoff before pickup is unusable for any duration question.
--   * PU/DO location ids outside 1..265 do not join to the zone lookup.
--   * negative fares are refunds/adjustments, which would make every SUM a lie
--     in a demo that does not explain them.
CREATE OR REPLACE VIEW clean_trips AS
SELECT
    CAST(VendorID AS SMALLINT)                        AS vendor_id,
    tpep_pickup_datetime                              AS pickup_ts,
    tpep_dropoff_datetime                             AS dropoff_ts,
    CAST(tpep_pickup_datetime AS DATE)                AS pickup_date,
    CAST(passenger_count AS SMALLINT)                 AS passenger_count,
    CAST(trip_distance AS DECIMAL(9,2))               AS trip_distance_mi,
    CAST(RatecodeID AS SMALLINT)                      AS rate_code_id,
    CASE store_and_fwd_flag WHEN 'Y' THEN TRUE WHEN 'N' THEN FALSE END AS store_and_fwd,
    CAST(PULocationID AS SMALLINT)                    AS pu_location_id,
    CAST(DOLocationID AS SMALLINT)                    AS do_location_id,
    CAST(payment_type AS SMALLINT)                    AS payment_type_id,
    CAST(fare_amount AS DECIMAL(10,2))                AS fare_amount,
    CAST(extra AS DECIMAL(10,2))                      AS extra,
    CAST(mta_tax AS DECIMAL(10,2))                    AS mta_tax,
    CAST(tip_amount AS DECIMAL(10,2))                 AS tip_amount,
    CAST(tolls_amount AS DECIMAL(10,2))               AS tolls_amount,
    CAST(improvement_surcharge AS DECIMAL(10,2))      AS improvement_surcharge,
    CAST(congestion_surcharge AS DECIMAL(10,2))       AS congestion_surcharge,
    CAST(airport_fee AS DECIMAL(10,2))                AS airport_fee,
    CAST(total_amount AS DECIMAL(10,2))               AS total_amount
FROM raw_trips
WHERE tpep_pickup_datetime >= DATE '$DAY_START'
  AND tpep_pickup_datetime <  DATE '$DAY_END' + INTERVAL 1 DAY
  AND tpep_dropoff_datetime >= tpep_pickup_datetime
  AND PULocationID BETWEEN 1 AND 265
  AND DOLocationID BETWEEN 1 AND 265
  AND total_amount >= 0
  AND fare_amount  >= 0
  AND trip_distance >= 0;

-- HASH SAMPLING, NOT RNG (D8). The hash is over the natural key of a trip —
-- who, when, where, how far, how much — so it is a property of the row itself
-- and survives any change in file order or partitioning.
CREATE OR REPLACE VIEW sampled_trips AS
SELECT * FROM clean_trips
WHERE hash(vendor_id, pickup_ts, dropoff_ts, pu_location_id, do_location_id,
           trip_distance_mi, total_amount, tip_amount, payment_type_id) % $MODULUS = 0;

-- row_number() over the FULL column order: a total order up to rows that are
-- identical in every column, and for those the assignment is interchangeable.
COPY (
  SELECT
    row_number() OVER (
      ORDER BY pickup_ts, dropoff_ts, pu_location_id, do_location_id, total_amount,
               fare_amount, tip_amount, trip_distance_mi, payment_type_id, rate_code_id,
               vendor_id, passenger_count, extra, mta_tax, tolls_amount,
               improvement_surcharge, congestion_surcharge, airport_fee, store_and_fwd
    ) AS trip_id,
    vendor_id, pickup_ts, dropoff_ts, pickup_date, passenger_count, trip_distance_mi,
    rate_code_id, store_and_fwd, pu_location_id, do_location_id, payment_type_id,
    fare_amount, extra, mta_tax, tip_amount, tolls_amount, improvement_surcharge,
    congestion_surcharge, airport_fee, total_amount
  FROM sampled_trips
) TO '$CSV/trips.csv' (HEADER, DELIMITER ',', DATEFORMAT '%Y-%m-%d', TIMESTAMPFORMAT '%Y-%m-%d %H:%M:%S');

COPY (
  SELECT pickup_date,
         count(*)                                        AS trip_count,
         CAST(sum(total_amount) AS DECIMAL(14,2))         AS total_revenue,
         CAST(sum(trip_distance_mi) AS DECIMAL(14,2))     AS total_distance_mi,
         CAST(sum(tip_amount) AS DECIMAL(14,2))           AS total_tips
  FROM sampled_trips GROUP BY pickup_date ORDER BY pickup_date
) TO '$CSV/trips_daily.csv' (HEADER, DELIMITER ',', DATEFORMAT '%Y-%m-%d');

COPY (
  SELECT date_trunc('month', pickup_date)                 AS month_start,
         pu_location_id,
         count(*)                                        AS trip_count,
         CAST(sum(total_amount) AS DECIMAL(14,2))         AS total_revenue,
         CAST(sum(trip_distance_mi) AS DECIMAL(14,2))     AS total_distance_mi,
         CAST(sum(tip_amount) AS DECIMAL(14,2))           AS total_tips
  FROM sampled_trips GROUP BY 1, 2 ORDER BY 1, 2
) TO '$CSV/trips_monthly.csv' (HEADER, DELIMITER ',', DATEFORMAT '%Y-%m-%d');

-- Lookup-coverage assert (see data/tlc-*.csv): a code present in the sample but
-- absent from the committed dictionary transcription would produce orphan joins
-- in the shipped example pipelines. Fail the build instead.
CREATE OR REPLACE VIEW ref_rate_codes AS
  SELECT * FROM read_csv('$SD_ROOT/data/tlc-rate-codes.csv', header = true, comment = '#');
CREATE OR REPLACE VIEW ref_payment_types AS
  SELECT * FROM read_csv('$SD_ROOT/data/tlc-payment-types.csv', header = true, comment = '#');

SELECT CASE WHEN count(*) = 0 THEN 'ok'
            ELSE error('rate_code_id values in the sample with no row in data/tlc-rate-codes.csv: '
                       || string_agg(DISTINCT CAST(rate_code_id AS VARCHAR), ',')) END
FROM sampled_trips t
WHERE t.rate_code_id IS NOT NULL
  AND t.rate_code_id NOT IN (SELECT rate_code_id FROM ref_rate_codes);

SELECT CASE WHEN count(*) = 0 THEN 'ok'
            ELSE error('payment_type values in the sample with no row in data/tlc-payment-types.csv: '
                       || string_agg(DISTINCT CAST(payment_type_id AS VARCHAR), ',')) END
FROM sampled_trips t
WHERE t.payment_type_id IS NOT NULL
  AND t.payment_type_id NOT IN (SELECT payment_type_id FROM ref_payment_types);
" >&2

# --- weather ----------------------------------------------------------------
#
# The GHCN "Daily Summaries" CSV is already one row per station-day with one
# COLUMN per element; the melt is therefore wide -> long. Units are GHCN's
# native ones and are converted here, once: PRCP/TMAX/TMIN/AWND arrive in tenths
# (of mm, °C, °C, m/s), SNOW in whole millimetres. Getting this wrong is
# invisible in a row count and obvious in a chart, so the conversion is stated
# per element rather than applied as one blanket /10.
step "weather: melting GHCN daily summaries for ${STATIONS//,/ }"
station_sql=$(python3 -c "print(','.join(\"'\"+s+\"'\" for s in '$STATIONS'.split(',')))")
station_count=$(python3 -c "print(len('$STATIONS'.split(',')))")
"$DUCKDB" -c "
CREATE OR REPLACE VIEW raw_ghcn AS
SELECT * FROM read_csv('$RAW/noaa-ghcn-*.csv', header = true, union_by_name = true,
                       all_varchar = true, filename = false);

CREATE OR REPLACE VIEW ghcn_window AS
SELECT STATION AS station_id, CAST(DATE AS DATE) AS obs_date,
       LATITUDE, LONGITUDE, ELEVATION, NAME, PRCP, SNOW, TMAX, TMIN, AWND
FROM raw_ghcn
WHERE STATION IN ($station_sql)
  AND CAST(DATE AS DATE) BETWEEN DATE '$DAY_START' AND DATE '$DAY_END';

CREATE OR REPLACE VIEW observations AS
SELECT station_id, obs_date, element,
       CAST(round(raw_value * factor, 2) AS DECIMAL(10,2)) AS value, unit
FROM (
  SELECT station_id, obs_date, 'PRCP' AS element, TRY_CAST(PRCP AS DOUBLE) AS raw_value, 0.1 AS factor, 'mm'   AS unit FROM ghcn_window
  UNION ALL
  SELECT station_id, obs_date, 'SNOW', TRY_CAST(SNOW AS DOUBLE), 1.0,  'mm'   FROM ghcn_window
  UNION ALL
  SELECT station_id, obs_date, 'TMAX', TRY_CAST(TMAX AS DOUBLE), 0.1,  'degC' FROM ghcn_window
  UNION ALL
  SELECT station_id, obs_date, 'TMIN', TRY_CAST(TMIN AS DOUBLE), 0.1,  'degC' FROM ghcn_window
  UNION ALL
  SELECT station_id, obs_date, 'AWND', TRY_CAST(AWND AS DOUBLE), 0.1,  'm/s'  FROM ghcn_window
) m
WHERE raw_value IS NOT NULL;

COPY (SELECT * FROM observations ORDER BY station_id, obs_date, element)
  TO '$CSV/observations.csv' (HEADER, DELIMITER ',', DATEFORMAT '%Y-%m-%d');

-- Station metadata comes from the fixed-width MASTER LIST, not from the daily
-- summaries: those carry LATITUDE/LONGITUDE/ELEVATION/NAME as header columns
-- with EMPTY values on every row (verified against the downloaded files — the
-- header alone would have led straight to a table of NULLs). Field offsets are
-- the GHCN-Daily readme's: ID 1-11, LATITUDE 13-20, LONGITUDE 22-30,
-- ELEVATION 32-37, STATE 39-40, NAME 42-71.
COPY (
  SELECT trim(substr(line, 1, 11))                              AS station_id,
         trim(substr(line, 42, 30))                             AS name,
         CAST(trim(substr(line, 13, 8))  AS DECIMAL(9,4))        AS latitude,
         CAST(trim(substr(line, 22, 9))  AS DECIMAL(9,4))        AS longitude,
         CAST(trim(substr(line, 32, 6))  AS DECIMAL(7,1))        AS elevation_m
  FROM read_csv('$RAW/ghcnd-stations.txt', header = false, columns = {'line': 'VARCHAR'},
                delim = '\\x07', quote = '', escape = '')
  WHERE trim(substr(line, 1, 11)) IN ($station_sql)
  ORDER BY station_id
) TO '$CSV/stations.csv' (HEADER, DELIMITER ',');

SELECT CASE WHEN count(DISTINCT station_id) = $station_count
            THEN 'ok'
            ELSE error('expected every pinned GHCN station to have observations in the window; got '
                       || CAST(count(DISTINCT station_id) AS VARCHAR)) END
FROM observations;

-- Every station must also have metadata, or the MySQL foreign key would be the
-- thing that discovers a missing master-list row, three stages later.
SELECT CASE WHEN count(*) = $station_count THEN 'ok'
            ELSE error('expected ' || $station_count || ' rows in the station metadata extract; got '
                       || CAST(count(*) AS VARCHAR)) END
FROM read_csv('$CSV/stations.csv', header = true);
" >&2

# --- reference --------------------------------------------------------------
step "reference: zones, code lookups and the generated calendar"

# The calendar is DERIVED, then cross-checked against the committed OPM list.
# Deriving it from the statutory rules and comparing is what stops a
# transcription slip in data/us-federal-holidays.csv reaching the artifact
# (design §3 calls the calendar 'facts, generated by script' — this is the
# guard that makes that claim checkable).
python3 - "$DAY_START" "$DAY_END" "$SD_ROOT/data/us-federal-holidays.csv" "$CSV/calendar.csv" <<'PY'
import csv, sys
from datetime import date, timedelta

day_start, day_end, opm_path, out_path = sys.argv[1:5]
start = date.fromisoformat(day_start)
end = date.fromisoformat(day_end)

MON, THU = 0, 3
def nth_weekday(y, m, wd, n):
    d = date(y, m, 1)
    return d + timedelta(days=(wd - d.weekday()) % 7) + timedelta(weeks=n - 1)
def last_weekday(y, m, wd):
    d = date(y, m + 1, 1) - timedelta(days=1) if m < 12 else date(y, 12, 31)
    return d - timedelta(days=(d.weekday() - wd) % 7)
def statutory(y):
    return [
        (date(y, 1, 1),  "New Year's Day"),
        (nth_weekday(y, 1, MON, 3),  "Birthday of Martin Luther King, Jr."),
        (nth_weekday(y, 2, MON, 3),  "Washington's Birthday"),
        (last_weekday(y, 5, MON),    "Memorial Day"),
        (date(y, 6, 19), "Juneteenth National Independence Day"),
        (date(y, 7, 4),  "Independence Day"),
        (nth_weekday(y, 9, MON, 1),  "Labor Day"),
        (nth_weekday(y, 10, MON, 2), "Columbus Day"),
        (date(y, 11, 11), "Veterans Day"),
        (nth_weekday(y, 11, THU, 4), "Thanksgiving Day"),
        (date(y, 12, 25), "Christmas Day"),
    ]
def observed(d):
    if d.weekday() == 5: return d - timedelta(days=1)
    if d.weekday() == 6: return d + timedelta(days=1)
    return d

derived = {}
for y in range(start.year, end.year + 1):
    for d, name in statutory(y):
        o = observed(d)
        if start <= o <= end:
            derived[o.isoformat()] = name

pinned = {}
with open(opm_path, encoding="utf-8") as f:
    rows = csv.DictReader(line for line in f if not line.startswith("#"))
    for r in rows:
        if start.isoformat() <= r["observed_date"] <= end.isoformat():
            pinned[r["observed_date"]] = r["holiday_name"]

if derived != pinned:
    only_derived = sorted(set(derived.items()) - set(pinned.items()))
    only_pinned = sorted(set(pinned.items()) - set(derived.items()))
    sys.exit(
        "transform: FAIL — data/us-federal-holidays.csv disagrees with the statutory derivation.\n"
        f"  derived but not pinned: {only_derived}\n"
        f"  pinned but not derived: {only_pinned}\n"
        "  One of the two is wrong. Fix the CSV, or the rule, deliberately."
    )

with open(out_path, "w", newline="", encoding="utf-8") as f:
    w = csv.writer(f, lineterminator="\n")
    w.writerow(["cal_date", "day_of_week", "is_weekend", "is_holiday", "holiday_name"])
    d = start
    while d <= end:
        iso = d.isoformat()
        w.writerow([iso, d.isoweekday(), 1 if d.weekday() >= 5 else 0,
                    1 if iso in pinned else 0, pinned.get(iso, "")])
        d += timedelta(days=1)
PY

"$DUCKDB" -c "
COPY (
  SELECT CAST(LocationID AS INTEGER) AS location_id,
         Borough      AS borough,
         Zone         AS zone,
         service_zone AS service_zone
  FROM read_csv('$RAW/tlc-zone-lookup.csv', header = true)
  WHERE Borough IS NOT NULL AND Zone IS NOT NULL AND service_zone IS NOT NULL
  ORDER BY location_id
) TO '$CSV/zones.csv' (HEADER, DELIMITER ',');

COPY (SELECT * FROM read_csv('$SD_ROOT/data/tlc-rate-codes.csv', header = true, comment = '#') ORDER BY rate_code_id)
  TO '$CSV/rate_codes.csv' (HEADER, DELIMITER ',');
COPY (SELECT * FROM read_csv('$SD_ROOT/data/tlc-payment-types.csv', header = true, comment = '#') ORDER BY payment_type_id)
  TO '$CSV/payment_types.csv' (HEADER, DELIMITER ',');
" >&2

# --- NOAA window content pins ----------------------------------------------
#
# One SHA per station over exactly the rows this build takes from that station.
# Computed from observations.csv, i.e. from the extract itself: there is no
# second query that could drift from the one that produced the data.
step "weather: NOAA window content pins"
tmp_pins=$(mktemp)
IFS=, read -r -a station_list <<< "$STATIONS"
for s in "${station_list[@]}"; do
  h=$( { head -1 "$CSV/observations.csv"; grep "^$s," "$CSV/observations.csv"; } | shasum -a 256 | awk '{print $1}')
  printf '%s %s\n' "noaa-ghcn-$s.csv" "$h" >> "$tmp_pins"
done
printf '%s %s\n' "ghcnd-stations.txt" "$(shasum -a 256 < "$CSV/stations.csv" | awk '{print $1}')" >> "$tmp_pins"

if [ "$REPIN" = 1 ]; then
  python3 - "$SOURCES_LOCK" "$tmp_pins" <<'PY'
import sys
lock_path, pins_path = sys.argv[1:3]
pins = dict(line.split() for line in open(pins_path) if line.strip())
out = []
for line in open(lock_path):
    f = line.split()
    if len(f) >= 3 and f[0] == "window" and f[1] in pins:
        out.append("window %-28s %s\n" % (f[1], pins[f[1]]))
    else:
        out.append(line)
open(lock_path, "w").writelines(out)
PY
  log "sources.lock window pins rewritten — REVIEW THE DIFF: a changed pin means NCEI revised historical observations, which changes the artifact"
else
  fail=0
  while read -r id got; do
    want=$(lock_field window "$id")
    [ "$want" = "PENDING" ] && { log "window pin for $id is PENDING — run transform.sh --repin once, then review"; fail=1; continue; }
    if [ "$want" != "$got" ]; then
      log "window content pin MISMATCH for $id
  pinned:  $want
  derived: $got
  NCEI has revised historical observations inside the pinned window. Re-pin
  deliberately (transform.sh --repin) and bump the artifact version."
      fail=1
    fi
  done < "$tmp_pins"
  [ "$fail" = 0 ] || die "NOAA window content pins did not verify"
fi
rm -f "$tmp_pins"

step "transform complete"
wc -l "$CSV"/*.csv >&2
