#!/usr/bin/env bash
# scripts/sample-data-trade/download.sh — stage 1 of the trade/v1 build
# (mirrors ../sample-data/download.sh, design §4.1).
#
#   CENSUS_API_KEY=<key> ./scripts/sample-data-trade/download.sh
#
# Differences from the mobility family, all forced by the sources:
#   - Census and Comtrade are LIVING APIs, not immutable files: the pinned
#     object (sha256 in sources.lock) is the MERGED CANONICAL EXTRACT this
#     script builds — same idea as the NOAA `window` content pins. A Census
#     April-revision changes the extract, fails the pin loudly: exactly the
#     event worth failing on.
#   - The API key NEVER lives in sources.lock or any file: it arrives via
#     CENSUS_API_KEY and is used only to build request URLs.
#   - Per-call responses are cached under work/raw/census/ etc., so a failed
#     call re-runs alone; the merged extract is rebuilt from the full cache.
#   - On first bootstrap sources.lock has no derived pins yet: every extract
#     whose pin is present is VERIFIED (and a mismatch dies, as ever); a
#     missing pin is reported as unpinned-for-bootstrap so pin.sh can record
#     it. That is the only "not verified" this script ever prints.

set -euo pipefail
SD_SCRIPT=download
SD_ROOT="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SD_ROOT/../.." && pwd)"
# The shared machinery (lock parsing, SHA verification, pinned DuckDB install)
# lives with the mobility family; SD_ROOT above already points sources.lock
# at THIS family's lock.
source "$REPO_ROOT/scripts/sample-data/lib/common.sh"

RAW="$SD_ROOT/work/raw"
mkdir -p "$RAW/census" "$RAW/comtrade" "$RAW/binance"

require_cmd python3 "canonicalising the API responses"
require_cmd curl "download.sh fetches the pinned sources over HTTPS"

[ -n "${CENSUS_API_KEY:-}" ] || die "CENSUS_API_KEY is not set — request one at
  https://api.census.gov/data/key_signup.html and export it; the key is never
  written to any file by this build"

WINDOW_START=$(lock_field param window_start 3)
WINDOW_END=$(lock_field param window_end 3)
PARTNERS=$(lock_field param census_partners 3)
REPORTERS=$(lock_field param comtrade_reporters 3)
SYMBOLS=$(lock_field param binance_symbols 3)
[ -n "$WINDOW_START" ] && [ -n "$WINDOW_END" ] && [ -n "$PARTNERS" ] \
  && [ -n "$REPORTERS" ] && [ -n "$SYMBOLS" ] \
  || die "sources.lock is missing a required param (window_start/window_end/census_partners/comtrade_reporters/binance_symbols)"

# months between WINDOW_START and WINDOW_END inclusive, one per line
months_list() {
  python3 - "$WINDOW_START" "$WINDOW_END" <<'PY'
import sys
s, e = sys.argv[1], sys.argv[2]
y, m = int(s[:4]), int(s[5:7])
ey, em = int(e[:4]), int(e[5:7])
while (y, m) <= (ey, em):
    print(f"{y:04d}-{m:02d}")
    y, m = (y + 1, 1) if m == 12 else (y, m + 1)
PY
}

# --- Census: imports/hs and exports/hs, per partner-month -------------------
#
# One call per (partner, month, flow): the unfiltered month is far too large
# for a single response (measured 2026-09-04: >120s and unbounded), while a
# partner-month is a few seconds / ~16k rows. Sequential is ~6 calls/min
# (server-side latency dominates — measured), which is 108 minutes for 720
# calls; four workers land around 24/min, still far under the per-key query
# budget, for a ~30-minute stage. Failures are COLLECTED, not died-on inside
# a worker (a subshell cannot die the parent); the parent dies if any call
# failed, so a partial cache never reaches the merge.

CENSUS_BASE="https://api.census.gov/data/timeseries/intltrade"
step "Census: 2 flows x $(echo "$PARTNERS" | tr ',' '\n' | wc -l | tr -d ' ') partners x $(months_list | wc -l | tr -d ' ') months (4 workers)"

CENSUS_FAIL="$SD_ROOT/work/census-failures.txt"
: > "$CENSUS_FAIL"
export SD_SCRIPT RAW CENSUS_BASE CENSUS_API_KEY CENSUS_FAIL

census_one() {
  local flow="$1" partner="$2" month="$3" get dest url body
  if [ "$flow" = imports ]; then
    get="CTY_CODE,CTY_NAME,I_COMMODITY,I_COMMODITY_LDESC,GEN_VAL_MO"
  else
    get="CTY_CODE,CTY_NAME,E_COMMODITY,E_COMMODITY_LDESC,ALL_VAL_MO"
  fi
  dest="$RAW/census/${flow}_${partner}_${month}.json"
  [ -s "$dest" ] && [ "$(head -c 2 "$dest")" = "[[" ] && return 0
  sleep 0.5
  url="${CENSUS_BASE}/${flow}/hs?get=${get}&time=${month}&CTY_CODE=${partner}&key=${CENSUS_API_KEY}"
  body=$(curl -sfL --retry 3 --retry-delay 5 --max-time 300 "$url") || {
    echo "$flow $partner $month (curl failed)" >> "$CENSUS_FAIL"; return 0; }
  case "$body" in
    \[\[*) printf '%s' "$body" > "$dest" ;;
    *) echo "$flow $partner $month (non-table response: ${body:0:120})" >> "$CENSUS_FAIL" ;;
  esac
}
export -f census_one

for flow in imports exports; do
  for partner in ${PARTNERS//,/ }; do
    months_list | sed "s/^/$flow $partner /"
  done
done | xargs -P 4 -L 1 bash -c 'census_one "$@"' _

[ -s "$CENSUS_FAIL" ] && die "$(wc -l < "$CENSUS_FAIL" | tr -d ' ') Census call(s) failed:
$(head -5 "$CENSUS_FAIL")
(cached successes are kept; re-run to retry the failures alone)"

CENSUS_EXPECTED=$(( $(echo "$PARTNERS" | tr ',' '\n' | wc -l) * $(months_list | wc -l) * 2 ))
CENSUS_HAVE=$(ls "$RAW"/census/*.json 2>/dev/null | wc -l | tr -d ' ')
[ "$CENSUS_HAVE" = "$CENSUS_EXPECTED" ] \
  || die "census cache has $CENSUS_HAVE file(s), expected $CENSUS_EXPECTED — the merge below must not run on a partial cache"
log "Census: $CENSUS_EXPECTED partner-month calls cached"

# Canonical merged extracts: JSONL, one row per line, columns normalised
# (flow, period, partner_code, partner_name, hs_code, hs_desc, value_usd),
# rows ordered by (partner, period, hs_code) — byte-stable for a given
# upstream response set, which is what the sources.lock sha256 pins.
for flow in imports exports; do
  python3 - "$RAW/census" "$RAW" "$flow" "$PARTNERS" "$(months_list | tr '\n' ' ')" <<'PY'
import json, os, sys
cachedir, outdir, flow, partners, months = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4].split(','), sys.argv[5].split()
valcol = 'GEN_VAL_MO' if flow == 'imports' else 'ALL_VAL_MO'
rows = []
for p in partners:
    for m in months:
        f = os.path.join(cachedir, f"{flow}_{p}_{m}.json")
        d = json.load(open(f))
        hdr = d[0]
        ci, ni, ki, di, vi = (hdr.index(x) for x in
            ('CTY_CODE', 'CTY_NAME', hdr[2], hdr[3], valcol))
        for r in d[1:]:
            rows.append((r[ci], m, r[ki], r[ni], r[di], r[vi]))
rows.sort(key=lambda t: (t[0], t[1], t[2]))
with open(os.path.join(outdir, f"census-{flow}.jsonl"), "w") as out:
    for code, period, hs, name, desc, val in rows:
        out.write(json.dumps({"flow": flow, "period": period,
            "partner_code": code, "partner_name": name,
            "hs_code": hs, "hs_desc": desc, "value_usd": val},
            separators=(',', ':'), sort_keys=True) + "\n")
print(f"{flow}: {len(rows)} rows", file=sys.stderr)
PY
done

# --- Comtrade: annual TOTAL-level flows between reporters and the USA ------
# The reconciliation slice: what each partner REPORTS trading with the US
# (mirror statistics) at the headline level, vs what the US reports (Census).
# Preview-tier friendly: a few dozen small calls.

step "Comtrade: $(echo "$REPORTERS" | tr ',' '\n' | wc -l | tr -d ' ') reporters x 3 years x 2 flows"
COMTRADE_BASE="https://comtradeapi.un.org/public/v1/preview/C/A/HS"
ci=0
for reporter in ${REPORTERS//,/ }; do
  for year in 2022 2023 2024; do
    for flow in X M; do
      dest="$RAW/comtrade/${reporter}_${year}_${flow}.json"
      if [ ! -s "$dest" ]; then
        sleep 1.5   # anonymous preview tier: stay well under its rate limit
        curl -sfL --retry 3 --retry-delay 5 --max-time 120 \
          "${COMTRADE_BASE}?reporterCode=${reporter}&period=${year}&partnerCode=842&flowCode=${flow}&cmdCode=TOTAL" \
          -o "$dest.part" || { rm -f "$dest.part"; die "Comtrade call failed: reporter=$reporter year=$year flow=$flow"; }
        mv "$dest.part" "$dest"
      fi
      ci=$((ci + 1))
    done
  done
done
log "Comtrade: $ci calls cached"
python3 - "$RAW/comtrade" "$RAW" "$REPORTERS" <<'PY'
import json, os, sys
cachedir, outdir, reporters = sys.argv[1], sys.argv[2], sys.argv[3].split(',')
rows = []
for r in reporters:
    for year in (2022, 2023, 2024):
        for flow in ("X", "M"):
            f = os.path.join(cachedir, f"{r}_{year}_{flow}.json")
            d = json.load(open(f))
            for rec in d.get("data", []):
                # Keep the ONE canonical row per reporter-year-flow:
                # cmdCode=TOTAL, partner=USA, annual (refMonth 52), all
                # modes (motCode 0), no second partner (partner2 0), and
                # customsCode C00 (the all-customs-regimes total — the
                # preview also returns per-regime rows C03/C04/C06/C07/C20
                # and mot breakdowns; measured 2026-09-04).
                if rec.get("cmdCode") != "TOTAL": continue
                if str(rec.get("partnerCode")) != "842": continue
                if rec.get("refMonth") != 52: continue
                if rec.get("motCode") != 0: continue
                if rec.get("partner2Code") not in (0, None): continue
                if rec.get("customsCode") not in ("C00", None): continue
                rows.append({
                    "reporter_code": rec.get("reporterCode"),
                    "flow": flow, "period": str(rec.get("refYear")),
                    "partner_iso": "USA", "cmd": "TOTAL",
                    "value_usd": rec.get("primaryValue"),
                })
seen = set()
for rec in rows:
    key = (rec["reporter_code"], rec["period"], rec["flow"])
    if key in seen:
        raise SystemExit(f"duplicate canonical Comtrade row for {key} — preview shape changed")
    seen.add(key)
# A reporter may legitimately not report one flow at partner level (Germany
# publishes no partner-detail imports — measured 2026-09-04), so a missing
# FLOW is tolerated. A reporter-year with NO data at all means the code is
# wrong or upstream broke — that is the 576-Germany mistake this guard
# exists to catch.
by_reporter_year = {}
for (r, y, f) in seen:
    by_reporter_year.setdefault((r, y), []).append(f)
expected_pairs = {(int(r), str(y)) for r in reporters for y in (2022, 2023, 2024)}
missing_pairs = expected_pairs - set(by_reporter_year)
if missing_pairs:
    raise SystemExit("reporter-years with NO data at all (wrong reporter code?): "
                     + ", ".join(sorted(str(m) for m in missing_pairs)))
rows.sort(key=lambda x: (x["reporter_code"], x["period"], x["flow"]))
with open(os.path.join(outdir, "comtrade-flows.jsonl"), "w") as out:
    for rec in rows:
        out.write(json.dumps(rec, separators=(',', ':'), sort_keys=True) + "\n")
print(f"comtrade: {len(rows)} rows", file=sys.stderr)
PY

# --- Binance: monthly 1h klines, immutable zips -----------------------------

step "Binance: $(echo "$SYMBOLS" | tr ',' '\n' | wc -l | tr -d ' ') symbols x $(months_list | wc -l | tr -d ' ') monthly zips"
for sym in ${SYMBOLS//,/ }; do
  for month in $(months_list); do
    dest="$RAW/binance/${sym}-${month}.zip"
    [ -s "$dest" ] || fetch "https://data.binance.vision/data/spot/monthly/klines/${sym}/1h/${sym}-1h-${month}.zip" "$dest"
  done
done

# --- verify whatever pins exist (bootstrap: report the unpinned) -----------

# extract_path <id> — where a built extract lives. API extracts land in
# $RAW/ directly; Binance zips keep their download subdir.
extract_path() {
  local id="$1"
  case "$id" in
    binance-*) echo "$RAW/binance/${id#binance-}.zip" ;;
    *)         echo "$RAW/${id}.jsonl" ;;
  esac
}

pinned=0 bootstrapped=0
for id in census-imports census-exports comtrade-flows $(for s in ${SYMBOLS//,/ }; do months_list | sed "s/^/binance-${s}-/"; done); do
  f=$(extract_path "$id")
  [ -f "$f" ] || die "expected extract '$id' was not built"
  want=$(lock_field sha256 "$id" 3)
  if [ -n "$want" ]; then
    sha256_expect "$f" "$want" "$id"
    pinned=$((pinned + 1))
  else
    bootstrapped=$((bootstrapped + 1))
  fi
done
log "OK — $pinned extract(s) verified against sources.lock, $bootstrapped unpinned (bootstrap: record with pin.sh)"

# Emit the candidate pins for pin.sh's bootstrap mode.
for id in census-imports census-exports comtrade-flows $(for s in ${SYMBOLS//,/ }; do months_list | sed "s/^/binance-${s}-/"; done); do
  printf 'sha256\t%s\t%s\n' "$id" "$(sha256_of "$(extract_path "$id")")"
done > "$SD_ROOT/work/pins.candidates"
log "candidate pins written to work/pins.candidates (consumed by pin.sh)"
