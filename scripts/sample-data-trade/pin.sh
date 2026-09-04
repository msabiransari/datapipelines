#!/usr/bin/env bash
# scripts/sample-data-trade/pin.sh — MAINTAINER tool (mirrors the philosophy
# of ../sample-data/pin.sh): record the derived pins for the extracts
# download.sh just built. Never automatic, never a reflex on a failed
# checksum: a mismatch means upstream CHANGED, which changes what we publish.
#
#   ./scripts/sample-data-trade/pin.sh          # bootstrap: add pins that
#                                               # are absent; REFUSE to
#                                               # change an existing pin
#   ./scripts/sample-data-trade/pin.sh --repin  # overwrite existing pins
#                                               # (reviewed change only)
#
# Reads work/pins.candidates (written by download.sh) and rewrites the
# derived-pins section of sources.lock in place.

set -euo pipefail
SD_SCRIPT=pin
SD_ROOT="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SD_ROOT/../.." && pwd)"
source "$REPO_ROOT/scripts/sample-data/lib/common.sh"

CAND="$SD_ROOT/work/pins.candidates"
[ -f "$CAND" ] || die "work/pins.candidates is missing — run download.sh first"

force=0
[ "${1:-}" = --repin ] && force=1

WINDOW_START=$(lock_field param window_start 3)
WINDOW_END=$(lock_field param window_end 3)
FX_DAILY_PKG=$(lock_field param fx_daily_package 3)
FX_MONTHLY_PKG=$(lock_field param fx_monthly_package 3)
# The DDP window bounds, derived exactly as download.sh derives them — the
# URL a pin documents must be the URL that was fetched.
FX_FROM=$(python3 -c "import sys;y,m=sys.argv[1].split('-');print(f'{m}/01/{y}')" "$WINDOW_START")
FX_TO=$(python3 -c "import sys;y,m,d=sys.argv[1].split('-');print(f'{m}/{d}/{y}')" "$(window_day_end "$WINDOW_END")")

# url_for <id> — the source URL a pin documents. API extracts document the
# QUERY, not a fetchable file (the Census URL additionally needs the key,
# which never lives here); the H.10 pins name the exact DDP query.
url_for() {
  local id="$1"
  case "$id" in
    census-imports)
      echo "https://api.census.gov/data/timeseries/intltrade/imports/hs?get=CTY_CODE,CTY_NAME,I_COMMODITY,I_COMMODITY_LDESC,GEN_VAL_MO&time=${WINDOW_START}..${WINDOW_END}&CTY_CODE=<census_partners> (per partner-month calls merged; key appended at download)" ;;
    census-exports)
      echo "https://api.census.gov/data/timeseries/intltrade/exports/hs?get=CTY_CODE,CTY_NAME,E_COMMODITY,E_COMMODITY_LDESC,ALL_VAL_MO&time=${WINDOW_START}..${WINDOW_END}&CTY_CODE=<census_partners> (per partner-month calls merged; key appended at download)" ;;
    comtrade-flows)
      echo "https://comtradeapi.un.org/public/v1/preview/C/A/HS?reporterCode=<comtrade_reporters>&period=2022..2024&partnerCode=842&flowCode=X|M&cmdCode=TOTAL (per reporter-year-flow calls merged)" ;;
    fed-h10-daily)
      echo "https://www.federalreserve.gov/datadownload/Output.aspx?rel=H10&series=${FX_DAILY_PKG}&lastobs=&from=${FX_FROM}&to=${FX_TO}&filetype=csv&label=include&layout=seriescolumn" ;;
    fed-h10-monthly)
      echo "https://www.federalreserve.gov/datadownload/Output.aspx?rel=H10&series=${FX_MONTHLY_PKG}&lastobs=&from=${FX_FROM}&to=${FX_TO}&filetype=csv&label=include&layout=seriescolumn" ;;
    *) die "no url mapping for pin id '$id'" ;;
  esac
}

added=0 skipped=0
while IFS=$'\t' read -r _ id hash; do
  existing=$(lock_field sha256 "$id" 3)
  if [ -n "$existing" ] && [ "$existing" != "$hash" ]; then
    if [ "$force" = 1 ]; then
      log "re-pinning $id (reviewed change): $existing -> $hash"
    else
      log "SKIP $id — pinned $existing, candidate $hash differs. Re-pinning changes the artifact; pass --repin if reviewed."
      skipped=$((skipped + 1)); continue
    fi
  elif [ -n "$existing" ]; then
    skipped=$((skipped + 1)); continue
  fi
  # replace a commented placeholder line if present, else append under the
  # derived-pins marker
  if grep -q "^# url/sha256 ${id} " "$SD_ROOT/sources.lock"; then
    python3 - "$SD_ROOT/sources.lock" "$id" "$(url_for "$id")" "$hash" <<'PY'
import sys
path, pid, url, sha = sys.argv[1:5]
lines = open(path).read().splitlines(keepends=True)
out = []
for ln in lines:
    if ln.startswith(f"# url/sha256 {pid} "):
        out.append(f"url    {pid}   {url}\n")
        out.append(f"sha256 {pid}   {sha}\n")
    else:
        out.append(ln)
open(path, "w").write("".join(out))
PY
  else
    printf '\nurl    %s   %s\nsha256 %s   %s\n' "$id" "$(url_for "$id")" "$id" "$hash" >> "$SD_ROOT/sources.lock"
  fi
  added=$((added + 1))
done < "$CAND"

log "pins: $added written, $skipped unchanged/skipped — sources.lock is the record; commit the diff"
