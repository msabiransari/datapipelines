#!/usr/bin/env bash
# shellcheck source-path=SCRIPTDIR
# scripts/sample-data-lake/download.sh — stage 1 of the dp-lake build: fetch every
# pinned HVFHV month into work/raw/ and VERIFY it against sources.lock.
#
#   ./scripts/sample-data-lake/download.sh                 # the whole window
#   ./scripts/sample-data-lake/download.sh --month 2024-12 # one month
#
# THE SIZE IS THE DESIGN CONSTRAINT. The pinned window is 24 monthly Parquet files
# of ~460-520 MB each — **~11.6 GB in total** (measured 2026-09-07 from the
# upstream Content-Length headers; the exact per-month bytes are in the family
# README). The mobility family's whole corpus is ~1.2 GB, so "just download it all"
# is not the same request here. `--month` exists so a machine can work the window a
# month at a time and delete each raw file after transform.sh has consumed it; the
# README's "Low-disk build" recipe is that loop, and it needs ~1 GB of scratch
# rather than ~17 GB.
#
# This script only ever verifies. It cannot re-pin — that is pin.sh, a separate
# deliberate command — because a downloader that repaired its own checksums would
# make the rebuildable-artifact claim unfalsifiable: the first silently re-published
# upstream file would change what we publish and nothing would say so.
#
# Already-present files with the right hash are left alone, so a failed later stage
# re-runs without re-downloading. A file whose hash is WRONG is deleted by the
# verifier, so the next run re-fetches it — that is the only self-healing here, and
# it heals a truncated download, never a drifted pin.

set -euo pipefail
SD_SCRIPT=download
SD_ROOT="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SD_ROOT/../.." && pwd)"
# shellcheck source=../sample-data/lib/common.sh
source "$REPO_ROOT/scripts/sample-data/lib/common.sh"
# shellcheck source=lib/lake.sh
source "$SD_ROOT/lib/lake.sh"

ONLY_MONTH=""
while [ $# -gt 0 ]; do
  case "$1" in
    --month) ONLY_MONTH="${2:-}"; shift 2 ;;
    --month=*) ONLY_MONTH="${1#--month=}"; shift ;;
    *) die "unknown argument '$1' — usage: $0 [--month YYYY-MM]" ;;
  esac
done

RAW="$SD_ROOT/work/raw"
mkdir -p "$RAW"
require_cmd curl "download.sh fetches the pinned sources over HTTPS"

WINDOW_START=$(lock_field param window_start)
WINDOW_END=$(lock_field param window_end)
[ -n "$WINDOW_START" ] && [ -n "$WINDOW_END" ] || die "sources.lock is missing window_start/window_end"

if [ -n "$ONLY_MONTH" ]; then
  lake_months "$WINDOW_START" "$WINDOW_END" | grep -qx "$ONLY_MONTH" \
    || die "--month $ONLY_MONTH is outside the pinned window $WINDOW_START..$WINDOW_END"
  months="$ONLY_MONTH"
else
  months=$(lake_months "$WINDOW_START" "$WINDOW_END")
fi

verified=0 fetched=0
for month in $months; do
  id="tlc-hvfhv-$month.parquet"
  url=$(lock_field url "$id")
  want=$(lock_field sha256 "$id")
  dest="$RAW/$id"
  [ -n "$url" ] || die "sources.lock declares no 'url $id' — run pin.sh"
  [ -n "$want" ] || die "sources.lock declares 'url $id' with no matching sha256 line"
  # PENDING is the pre-pin state, and it is a REFUSAL, not a skip: a build that
  # treated it as "nothing to check" would publish bytes nobody pinned.
  [ "$want" != "PENDING" ] \
    || die "sources.lock has sha256 $id PENDING — run ./scripts/sample-data-lake/pin.sh and review its diff before building"

  if [ -f "$dest" ] && [ "$(sha256_of "$dest")" = "$want" ]; then
    verified=$((verified + 1))
    continue
  fi
  log "fetching $id (~480 MB)"
  fetch "$url" "$dest"
  sha256_expect "$dest" "$want" "$id"
  verified=$((verified + 1))
  fetched=$((fetched + 1))
done

[ "$verified" -gt 0 ] || die "no month was verified — that is not the same as a clean run"

log "OK — $verified pinned month(s) verified against sources.lock ($fetched newly downloaded)
  raw corpus now in $RAW: $(du -sh "$RAW" 2>/dev/null | awk '{print $1}')"
