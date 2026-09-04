#!/usr/bin/env bash
# scripts/sample-data-trade/transform.sh — stage 2 of the trade/v2 build
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

step "ETL: Federal Reserve H.10 packages -> fx_daily / fx_monthly"
# python3, not DuckDB: the DDP package is a wide "series column" CSV with a
# five-line metadata header, one column per currency and 'ND' for a day a rate
# was not published. Reshaping it to long form, dropping ND (never zero-filling
# — a rate that does not exist must not be invented) and inverting the
# reciprocally-quoted series is a dozen lines of Python and an unreadable pivot
# in SQL.
#
# WHICH DIRECTION A SERIES IS QUOTED IN is the one thing here worth getting
# wrong quietly, so it is established three ways: the `RXI$US` prefix on the
# Board's own series id (the reciprocal, US$-per-unit, series — the euro is
# ours); the monthly package's `Unit: Currency:_Per_<X>` row, which must agree;
# and — the check no metadata row can fool — every monthly average must land
# within 2% of the mean of that month's daily rates, which it cannot if either
# package was stored upside down. (The DAILY package's "Currency:" row is NOT
# usable for this: it names the pair currency, EUR, for a series whose values
# are US dollars. Measured 2026-09-04.)
require_cmd python3 "reshaping the H.10 CSV packages"
python3 - "$RAW/fx/fed-h10-daily.csv" "$RAW/fx/fed-h10-monthly.csv" "$SD_ROOT/data/currencies.csv" "$CSV" <<'PY'
import csv, os, statistics, sys

daily_path, monthly_path, currencies_path, outdir = sys.argv[1:5]

with open(currencies_path, newline="") as f:
    currencies = list(csv.DictReader(f))

def load(path, series_key):
    """Long-form (period, currency, per_usd, usd_per) rows from one DDP package."""
    with open(path, newline="") as f:
        rows = list(csv.reader(f))
    meta = {r[0].strip().rstrip(":").strip(): r for r in rows[:5]}
    header = rows[5]
    if header[0] != "Time Period":
        raise SystemExit(f"{path}: row 6 is not the 'Time Period' header — the DDP layout changed")
    out, dropped = [], 0
    for c in currencies:
        sid = c[series_key]
        if sid not in header:
            raise SystemExit(f"{path}: series '{sid}' ({c['currency']}) is not in the package")
        col = header.index(sid)
        if meta["Multiplier"][col] != "1":
            raise SystemExit(f"{path}: series '{sid}' has multiplier {meta['Multiplier'][col]}, not 1 — the values would be scaled")
        reciprocal = sid.startswith("RXI$US")
        # Where the package DOES carry the direction in its metadata, it must
        # agree. Only the monthly (G.5) package does: its "Unit:" row reads
        # `Currency:_Per_<X>`, X=USD for a directly-quoted series. The daily
        # package's "Unit:" is a bare "Currency" and its "Currency:" row names
        # the PAIR currency (EUR for the reciprocal euro series, whose values
        # are US dollars) — measured 2026-09-04, so it says nothing about
        # direction and is deliberately not consulted.
        unit = meta.get("Unit", [""] * (col + 1))[col]
        if unit.startswith("Currency:_Per_") and (unit != "Currency:_Per_USD") != reciprocal:
            raise SystemExit(f"{path}: series '{sid}' — the 'Unit:' row says {unit} but the id "
                             f"{'does' if reciprocal else 'does not'} carry the RXI$US reciprocal "
                             "prefix; refusing to guess the direction")
        for r in rows[6:]:
            if not r or not r[0]:
                continue
            raw = r[col].strip()
            if raw in ("ND", ""):     # not published that period — dropped, never zero-filled
                dropped += 1
                continue
            v = float(raw)
            if v <= 0:
                raise SystemExit(f"{path}: series '{sid}' has a non-positive rate {raw} at {r[0]}")
            usd_per, per_usd = (v, 1.0 / v) if reciprocal else (1.0 / v, v)
            out.append((r[0], c["currency"], per_usd, usd_per))
    out.sort(key=lambda t: (t[0], t[1]))
    return out, dropped

def write(rows, name, period_col):
    with open(os.path.join(outdir, name), "w", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow([period_col, "currency", "per_usd", "usd_per"])
        for period, cur, per_usd, usd_per in rows:
            w.writerow([period, cur, f"{per_usd:.10g}", f"{usd_per:.10g}"])

d_rows, d_dropped = load(daily_path, "series_daily")
m_rows, m_dropped = load(monthly_path, "series_monthly")

# THE DIRECTION CHECK THAT CANNOT BE FOOLED BY EITHER PACKAGE'S METADATA: the
# G.5 monthly figure IS the average of the H.10 daily figures, so a series
# stored upside down in one package and not the other is off by its own square.
# Every currency-month must agree within 2% (the residual is the Jensen gap on
# the inverted series plus the Board's own rounding).
daily_by_month = {}
for period, cur, per_usd, _ in d_rows:
    daily_by_month.setdefault((period[:7], cur), []).append(per_usd)
checked = 0
for month, cur, per_usd, _ in m_rows:
    sample = daily_by_month.get((month, cur))
    if not sample:
        raise SystemExit(f"monthly {cur} {month} has no daily observations to check it against")
    ratio = per_usd / statistics.fmean(sample)
    if abs(ratio - 1.0) > 0.02:
        raise SystemExit(f"{cur} {month}: the monthly average is {ratio:.3f}x the mean of that "
                         "month's daily rates — one of the two packages is quoted the other way "
                         "round from what this build assumed")
    checked += 1

write(d_rows, "fx_daily.csv", "rate_date")
write(m_rows, "fx_monthly.csv", "month")
print(f"fx_daily: {len(d_rows)} rows ({d_dropped} ND observations dropped, never zero-filled)", file=sys.stderr)
print(f"fx_monthly: {len(m_rows)} rows ({m_dropped} ND observations dropped)", file=sys.stderr)
print(f"monthly-vs-daily direction check: {checked} currency-months agree within 2%", file=sys.stderr)
PY

# Pinned reference data, passed through so load-and-dump has one directory.
cp "$SD_ROOT/data/currencies.csv" "$CSV/currencies.csv"
cp "$SD_ROOT/data/partner_currency.csv" "$CSV/partner_currency.csv"

for f in "$CSV"/*.csv; do
  log "$(basename "$f"): $(($(wc -l < "$f") - 1)) rows"
done
log "OK — CSVs in work/csv/"
