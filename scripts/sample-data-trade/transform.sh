#!/usr/bin/env bash
# scripts/sample-data-trade/transform.sh — stage 2 of the trade/v1 build
# (mirrors ../sample-data/transform.sh, design §4.1): DuckDB ETL over the
# cached raw extracts, emitting TOTALLY ORDERED CSVs into work/csv/.
#
#   ./scripts/sample-data-trade/transform.sh
#
# Determinism: no sampling is needed for this family (the slice IS the
# parameters), so determinism rests on (a) the pinned upstream extracts and
# (b) every emitted CSV being sorted by its natural key — the same contract,
# minus the hash-sample step.

set -euo pipefail
SD_SCRIPT=transform
SD_ROOT="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SD_ROOT/../.." && pwd)"
source "$REPO_ROOT/scripts/sample-data/lib/common.sh"

RAW="$SD_ROOT/work/raw"
CSV="$SD_ROOT/work/csv"
mkdir -p "$CSV"
DUCKDB=$(duckdb_bin)

[ -f "$RAW/census-imports.jsonl" ] && [ -f "$RAW/census-exports.jsonl" ] \
  || die "raw extracts missing — run download.sh first"
[ -f "$RAW/comtrade-flows.jsonl" ] || die "comtrade extract missing — run download.sh first"

# The persistent ETL database: every statement below runs against it, because
# a bare `duckdb <<SQL` opens a THROWAWAY in-memory database that vanishes
# when the CLI exits — split invocations cannot share census_raw (measured
# 2026-09-04: the first build died on exactly that).
ETLDB="$SD_ROOT/work/etl.duckdb"
rm -f "$ETLDB"

# duckdb_run — reads SQL from STDIN (heredoc at the call site, so $RAW/$CSV
# expand normally), runs it against $ETLDB, and gates on the OUTPUT, not the
# exit code: the pinned CLI prints a failing statement's error and exits 0
# (its sqlite-style `.bail on` is a no-op — measured 2026-09-04). Any "Error"
# line in the output kills the build. The exit code being ignored here is
# deliberate and documented: it carries no signal at all.
duckdb_run() {
  local out="$SD_ROOT/work/duckdb.out"
  "$DUCKDB" "$ETLDB" > "$out" 2>&1 < /dev/stdin || true
  if grep -q "Error" "$out"; then
    cat "$out" >&2
    die "DuckDB ETL failed — output above (the CLI exited 0 despite it)"
  fi
}

step "ETL: census extracts -> HS-6 facts + lookups"

# Census facts: HS-6 grain only (the API returns every aggregation level;
# the 6-digit rows ARE the HS-6 aggregates — 10-digit children nest under
# them, so filtering to length 6 needs no re-aggregation, only dedup safety).
# Value coercion: null/'' -> NULL, not 0 — a missing month-of-no-trade is
# genuinely absent from the API response and must not be manufactured.
duckdb_run <<SQL
CREATE OR REPLACE TABLE census_raw AS
  SELECT * FROM read_json_auto('$RAW/census-imports.jsonl')
  UNION ALL BY NAME
  SELECT * FROM read_json_auto('$RAW/census-exports.jsonl');
SQL

duckdb_run <<SQL
COPY (
  SELECT flow, period, partner_code, partner_name, hs_code, hs_desc,
         TRY_CAST(value_usd AS DECIMAL(18,2)) AS value_usd
  FROM census_raw
  WHERE length(hs_code) = 6
  ORDER BY flow, period, partner_code, hs_code
) TO '$CSV/trade_monthly.csv' (FORMAT CSV, HEADER);
SQL

duckdb_run <<SQL
COPY (
  SELECT DISTINCT partner_code, partner_name
  FROM census_raw ORDER BY partner_code
) TO '$CSV/partners.csv' (FORMAT CSV, HEADER);
SQL

duckdb_run <<SQL
COPY (
  SELECT DISTINCT substr(hs_code, 1, 2) AS chapter, hs_desc AS chapter_name
  FROM census_raw WHERE length(hs_code) = 2
  ORDER BY chapter
) TO '$CSV/hs_chapters.csv' (FORMAT CSV, HEADER);
SQL

duckdb_run <<SQL
COPY (
  SELECT flow, period, SUM(TRY_CAST(value_usd AS DECIMAL(18,2))) AS total_value_usd,
         COUNT(*) AS hs6_cell_count
  FROM census_raw WHERE length(hs_code) = 6
  GROUP BY 1, 2 ORDER BY 1, 2
) TO '$CSV/trade_flow_monthly.csv' (FORMAT CSV, HEADER);
SQL

step "ETL: comtrade extract -> reconciliation table"
duckdb_run <<SQL
COPY (
  SELECT CAST(reporter_code AS INTEGER) AS reporter_code, flow, period,
         CAST(value_usd AS DECIMAL(20,2)) AS value_usd
  FROM read_json_auto('$RAW/comtrade-flows.jsonl')
  ORDER BY reporter_code, period, flow
) TO '$CSV/comtrade_annual.csv' (FORMAT CSV, HEADER);
SQL
# The reporters lookup is pinned reference data (data/reporters.csv) — passed
# through to work/csv so load-and-dump has one directory to load from.
cp "$SD_ROOT/data/reporters.csv" "$CSV/reporters.csv"
cp "$SD_ROOT/data/partner_iso_crosswalk.csv" "$CSV/partner_iso_crosswalk.csv"

step "ETL: binance zips -> hourly klines"
# DuckDB's read_csv cannot read the .zip archives directly (the sniffer
# fails on the compressed stream — measured 2026-09-04); the pinned zips are
# unzipped to a scratch dir first. Timestamps are milliseconds in this
# window (pre-2025 data; Binance moved to microseconds for 2025+ months —
# out of our pinned window, and the pin would catch a window extension).
# The month is derived from the timestamp, not the filename, so it survives
# any re-globbing.
require_cmd unzip "binance klines arrive as zips"
KUNZIP="$SD_ROOT/work/klines_unzipped"
rm -rf "$KUNZIP"; mkdir -p "$KUNZIP"
SYMBOLS=$(lock_field param binance_symbols 3)
for sym in ${SYMBOLS//,/ }; do
  for z in "$RAW"/binance/${sym}-*.zip; do
    # Info-ZIP takes ONE archive per invocation — a multi-archive argument
    # list is read as archive + member patterns ("filename not matched").
    unzip -o -q "$z" -d "$KUNZIP" || die "unzip failed for $z"
  done
  duckdb_run <<SQL
COPY (
  SELECT '${sym}' AS symbol,
         strftime(epoch_ms(open_time), '%Y-%m') AS month,
         epoch_ms(open_time) AS open_ts,
         TRY_CAST(open AS DOUBLE)  AS open_price,
         TRY_CAST(high AS DOUBLE)  AS high_price,
         TRY_CAST(low AS DOUBLE)   AS low_price,
         TRY_CAST(close AS DOUBLE) AS close_price,
         TRY_CAST(volume AS DOUBLE) AS base_volume,
         TRY_CAST(quote_volume AS DOUBLE) AS quote_volume,
         TRY_CAST(trade_count AS BIGINT) AS trade_count
  FROM read_csv('$KUNZIP/${sym}-*.csv', header = false, delim = ',', columns = {
    'open_time': 'BIGINT', 'open': 'VARCHAR', 'high': 'VARCHAR',
    'low': 'VARCHAR', 'close': 'VARCHAR', 'volume': 'VARCHAR',
    'close_time': 'BIGINT', 'quote_volume': 'VARCHAR',
    'trade_count': 'BIGINT', 'taker_buy_base': 'VARCHAR',
    'taker_buy_quote': 'VARCHAR', 'ignore': 'VARCHAR'})
  ORDER BY open_time
) TO '$CSV/klines_1h_${sym}.csv' (FORMAT CSV, HEADER);
SQL
done

for f in "$CSV"/*.csv; do
  log "$(basename "$f"): $(($(wc -l < "$f") - 1)) rows"
done
log "OK — CSVs in work/csv/"
