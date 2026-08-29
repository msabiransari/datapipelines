#!/usr/bin/env bash
# scripts/sample-data/pin.sh — MAINTAINER TOOL. Regenerates the derived pin
# lines of sources.lock from its `param` block.
#
#   ./scripts/sample-data/pin.sh            # download, hash, rewrite sources.lock
#
# This is deliberately a SEPARATE command from download.sh. download.sh only
# ever VERIFIES against the lock and fails on a mismatch; if it could also
# re-pin, the first re-published upstream file would silently change what we
# publish, and D8's "rebuildable artifact" claim would be worth nothing. Re-pinning
# is a human act with a diff to review — that diff is the record of what changed
# upstream, and it must be accompanied by an artifact-version bump (§4: version
# directories are immutable).
#
# The `param` block is NOT regenerated: it is the human-declared input this
# script derives URLs from.

set -euo pipefail
SD_SCRIPT=pin
SD_ROOT="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SD_ROOT/../.." && pwd)"
source "$SD_ROOT/lib/common.sh"

RAW="$SD_ROOT/work/raw"
OUT="$SOURCES_LOCK.new"

require_cmd curl "pin.sh downloads every pinned source to hash it"
require_cmd unzip "the DuckDB CLI ships as a zip"
require_cmd docker "the build engines are pinned container images"

months() { # <start YYYY-MM> <end YYYY-MM>
  python3 - "$1" "$2" <<'PY'
import sys
from datetime import date
s, e = sys.argv[1], sys.argv[2]
y, m = map(int, s.split('-')); ey, em = map(int, e.split('-'))
while (y, m) <= (ey, em):
    print(f"{y:04d}-{m:02d}")
    m += 1
    if m == 13: y, m = y + 1, 1
PY
}

WINDOW_START=$(lock_field param window_start)
WINDOW_END=$(lock_field param window_end)
GHCN_STATIONS=$(lock_field param ghcn_stations)
DUCKDB_VERSION=$(lock_field param duckdb_version)

# Everything above the derived-pins marker is preserved verbatim.
awk '/^# --- derived pins/ { print; exit } { print }' "$SOURCES_LOCK" > "$OUT"

pin_file() { # <id> <url>
  # One `local` declaration per line: all args of a single `local` are expanded
  # BEFORE any is assigned, so `local id="$1" dest="$RAW/$id"` yields "$RAW/"
  # (scripts/lib/scan-tools.sh records the same trap).
  local id="$1"
  local url="$2"
  local dest="$RAW/$id"
  [ -f "$dest" ] || { log "fetching $id"; fetch "$url" "$dest"; }
  printf 'url    %-28s %s\n' "$id" "$url" >> "$OUT"
  printf 'sha256 %-28s %s\n' "$id" "$(sha256_of "$dest")" >> "$OUT"
}

{
  echo
  echo "# NYC TLC yellow-taxi trip records — monthly Parquet, the pinned window."
  echo "# Immutable in practice (Last-Modified predates the pin by years) but TLC"
  echo "# HAS re-published historical months before; that is precisely what the"
  echo "# SHA pin catches."
} >> "$OUT"
for m in $(months "$WINDOW_START" "$WINDOW_END"); do
  pin_file "tlc-yellow-$m.parquet" "https://d37ci6vzurychx.cloudfront.net/trip-data/yellow_tripdata_$m.parquet"
done

{
  echo
  echo "# TLC taxi zone lookup (265 zones -> borough / service zone)."
} >> "$OUT"
pin_file "tlc-zone-lookup.csv" "https://d37ci6vzurychx.cloudfront.net/misc/taxi_zone_lookup.csv"

{
  echo
  echo "# NOAA GHCN-Daily, per-station Daily Summaries."
  echo "#"
  echo "# DELIBERATELY UNPINNED FILE (unpinned:appended-daily). NCEI regenerates"
  echo "# every access/ CSV nightly to append new observations — its Last-Modified"
  echo "# is always yesterday — and NCEI publishes no dated snapshot of this"
  echo "# product (the only archive/ asset is 'daily-summaries-latest.tar.gz')."
  echo "# A file-level SHA would therefore fail every single day for a reason that"
  echo "# is not drift, which trains everyone to re-pin on sight — the exact habit"
  echo "# the pin exists to prevent."
  echo "#"
  echo "# The determinism claim is kept at the level that actually matters: the"
  echo "# `window` lines below pin the SHA-256 of the CANONICAL EXTRACT this build"
  echo "# takes — the pinned elements, for the pinned station, inside the pinned"
  echo "# date window, sorted, formatted by transform.sh's ghcn_extract query."
  echo "# Appended future days do not touch it; a REVISION of historical"
  echo "# observations (NCEI does re-issue QC'd values) fails the build loudly,"
  echo "# which is the event worth failing on."
} >> "$OUT"
IFS=, read -r -a stations <<< "$GHCN_STATIONS"
for s in "${stations[@]}"; do
  id="noaa-ghcn-$s.csv"
  url="https://www.ncei.noaa.gov/data/global-historical-climatology-network-daily/access/$s.csv"
  dest="$RAW/$id"
  [ -f "$dest" ] || { log "fetching $id"; fetch "$url" "$dest"; }
  printf 'url    %-28s %s\n' "$id" "$url" >> "$OUT"
  printf 'sha256 %-28s %s\n' "$id" "unpinned:appended-daily" >> "$OUT"
done
{
  echo "#"
  echo "# GHCN station metadata. The per-station Daily Summaries CSVs carry EMPTY"
  echo "# LATITUDE/LONGITUDE/ELEVATION/NAME columns (verified against the files, not"
  echo "# assumed from the header), so the station table comes from the fixed-width"
  echo "# master list. Regenerated nightly like everything else under this product,"
  echo "# hence the same unpinned-file + content-pin treatment."
} >> "$OUT"
ghcn_stations_url="https://www.ncei.noaa.gov/pub/data/ghcn/daily/ghcnd-stations.txt"
[ -f "$RAW/ghcnd-stations.txt" ] || { log "fetching ghcnd-stations.txt"; fetch "$ghcn_stations_url" "$RAW/ghcnd-stations.txt"; }
printf 'url    %-28s %s\n' "ghcnd-stations.txt" "$ghcn_stations_url" >> "$OUT"
printf 'sha256 %-28s %s\n' "ghcnd-stations.txt" "unpinned:appended-daily" >> "$OUT"

{
  echo "#"
  echo "# The window pins are written by transform.sh --repin (it owns the extract"
  echo "# query, so it is the only thing that can compute them honestly)."
} >> "$OUT"
for s in "${stations[@]}"; do
  existing=$(lock_field window "noaa-ghcn-$s.csv")
  printf 'window %-28s %s\n' "noaa-ghcn-$s.csv" "${existing:-PENDING}" >> "$OUT"
done
existing=$(lock_field window "ghcnd-stations.txt")
printf 'window %-28s %s\n' "ghcnd-stations.txt" "${existing:-PENDING}" >> "$OUT"

{
  echo
  echo "# DuckDB CLI — BUILD-TIME ETL ONLY. Not a runtime dialect (design D2);"
  echo "# it is here because it reads TLC's Parquet natively. Pinned per platform"
  echo "# exactly like the scanner binaries (009/F1)."
} >> "$OUT"
for plat in osx-universal linux-amd64 linux-arm64; do
  url="https://github.com/duckdb/duckdb/releases/download/$DUCKDB_VERSION/duckdb_cli-$plat.zip"
  dest="$RAW/duckdb_cli-$plat.zip"
  [ -f "$dest" ] || { log "fetching duckdb $plat"; fetch "$url" "$dest"; }
  printf 'tool   %-28s %s %s %s\n' "duckdb-$plat" "$DUCKDB_VERSION" "$url" "$(sha256_of "$dest")" >> "$OUT"
done

{
  echo
  echo "# Throwaway build engines, and the images the demo compose profile runs."
  echo "# Pinned by DIGEST, not just tag: pg_dump's output format is tied to the"
  echo "# server major, and the demo restores with the same major."
} >> "$OUT"
pin_image() { # <name> <repo:tag>
  local name="$1" tag="$2" digest
  log "pinning image $tag"
  docker pull -q "$tag" >/dev/null
  digest=$(docker image inspect --format '{{index .RepoDigests 0}}' "$tag" | sed 's/.*@//')
  printf 'image  %-28s %s@%s\n' "$name" "$tag" "$digest" >> "$OUT"
}
pin_image postgres postgres:16-alpine
pin_image mysql mysql:8.4

mv "$OUT" "$SOURCES_LOCK"
log "sources.lock regenerated — review the diff before committing"
