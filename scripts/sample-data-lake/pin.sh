#!/usr/bin/env bash
# shellcheck source-path=SCRIPTDIR
# scripts/sample-data-lake/pin.sh — MAINTAINER TOOL. Regenerates the derived pin
# lines of sources.lock from its `param` block.
#
#   ./scripts/sample-data-lake/pin.sh          # stream, hash, rewrite sources.lock
#
# A separate command from download.sh for the reason the mobility family's pin.sh
# gives: download.sh only ever VERIFIES, so the first silently re-published
# upstream file fails the build loudly instead of changing what we publish.
# Re-pinning is a human act whose DIFF is the record of what moved upstream, and
# it must be accompanied by an artifact-version bump (version directories are
# immutable).
#
# ONE DIFFERENCE FROM ../sample-data/pin.sh, forced by the size of this dataset:
# the mobility pin.sh downloads each source into work/raw/ and hashes the file on
# disk. The HVFHV window is ~11.6 GB; a machine that can pin does not have to be a
# machine that can hold the corpus, and a pin run that fills a shared box's disk is
# its own incident. So this one STREAMS: `curl … | shasum -a 256`, never touching
# the filesystem. The trade-off is stated because it is real — a streamed hash
# proves what the server sent, and nothing is left behind to re-verify. download.sh
# re-verifies every byte against these pins on the machine that actually builds.
#
# The `param` block is NOT regenerated: it is the human-declared input this script
# derives URLs from.

set -euo pipefail
SD_SCRIPT=pin
SD_ROOT="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SD_ROOT/../.." && pwd)"
# The shared machinery (lock parsing, SHA verification, the pinned DuckDB install)
# lives with the mobility family; SD_ROOT above already points sources.lock at
# THIS family's lock. Same arrangement as scripts/sample-data-trade/*.
# shellcheck source=../sample-data/lib/common.sh
source "$REPO_ROOT/scripts/sample-data/lib/common.sh"
# shellcheck source=lib/lake.sh
source "$SD_ROOT/lib/lake.sh"

OUT="$SOURCES_LOCK.new"

require_cmd curl "pin.sh streams every pinned source to hash it"
require_cmd shasum "pin.sh hashes the streamed bytes"

WINDOW_START=$(lock_field param window_start)
WINDOW_END=$(lock_field param window_end)
DUCKDB_VERSION=$(lock_field param duckdb_version)
[ -n "$WINDOW_START" ] && [ -n "$WINDOW_END" ] && [ -n "$DUCKDB_VERSION" ] \
  || die "sources.lock is missing one of the param lines pin.sh derives from"

# Everything above the derived-pins marker is preserved verbatim, including the
# licence evidence block: pin.sh derives pins, never prose.
awk '/^# --- derived pins/ { print; exit } { print }' "$SOURCES_LOCK" > "$OUT"

cat >> "$OUT" <<'BANNER'

# NYC TLC High Volume For-Hire Vehicle (Uber / Lyft / Via / Juno) trip records —
# monthly Parquet, the pinned 24-month window, ~480 MB each (~11.6 GB total).
# Same publisher and same CloudFront host as the yellow-taxi months the mobility
# family pins (../sample-data/sources.lock:58-108). Immutable in practice
# (Last-Modified predates the pin by years) but TLC HAS re-published historical
# months before; that is precisely what the SHA pin catches.
BANNER

pinned=0
for month in $(lake_months "$WINDOW_START" "$WINDOW_END"); do
  id="tlc-hvfhv-$month.parquet"
  url="$(lake_hvfhv_url "$month")"
  log "hashing $id (streamed, nothing written to disk)"
  # PIPEFAIL, not a bare pipeline: `curl … | shasum` reports shasum's status, so a
  # 404 or a dropped connection would otherwise be recorded as a perfectly good
  # hash of a truncated body (MISTAKES.md, "Pipes Mask Exit Codes").
  hash=$(set -o pipefail; curl -sfL --retry 3 --retry-delay 2 --max-time "${SD_FETCH_TIMEOUT:-1800}" "$url" | shasum -a 256 | awk '{print $1}') \
    || die "could not stream $url — nothing was pinned for $id"
  [ -n "$hash" ] || die "empty hash for $id"
  printf 'url    %-28s %s\n' "$id" "$url" >> "$OUT"
  printf 'sha256 %-28s %s\n' "$id" "$hash" >> "$OUT"
  pinned=$((pinned + 1))
done

# The DuckDB CLI lines are copied from the CURRENT lock rather than re-derived:
# their SHAs come from the GitHub release assets and are already reviewed pins,
# and re-downloading three CLIs to re-hash them proves nothing this file does not
# already assert. A DuckDB bump is a param edit plus a hand-checked SHA — and an
# artifact-version bump, because DuckDB's hash() decides which rows the sample holds.
{
  echo
  awk '/^# DuckDB CLI/ { p = 1 } p' "$SOURCES_LOCK"
} >> "$OUT"

mv "$OUT" "$SOURCES_LOCK"
log "OK — $pinned monthly source(s) pinned. REVIEW THE DIFF: a changed sha256 means TLC
  re-published that month, which changes what this build publishes; bump
  'param artifact_version' in the same commit."
