#!/usr/bin/env bash
# shellcheck source-path=SCRIPTDIR
# scripts/sample-data-lake/publish-layout.sh — stage 3 of the dp-lake build: render
# work/publish/lake/<version>/ as the EXACT tree that must land in the bucket.
#
#   ./scripts/sample-data-lake/publish-layout.sh
#   PUBLISH_COPY=1 ./scripts/sample-data-lake/publish-layout.sh   # real copies, not hard links
#
# There is no load-and-dump stage in this family. Nothing is restored anywhere: the
# lake is read IN PLACE over HTTPS, so the artifact is not a dump to be loaded, it
# is the object layout itself. This script is where that layout is decided, once, so
# the manifest, the verifier, the upload command and 089's datasource all describe
# the same thing.
#
#   s3://datapipelines-co/sample-data/lake/<version>/
#     hvfhv_trips/pickup_date=YYYY-MM-DD/part-0.parquet   the big prunable table
#     hvfhv_trips_sample/part-YYYY-MM.parquet             the 1-in-N sample, 1 file/month
#     hvfhv_zone_day/part-0.parquet                       the pre-aggregate
#     hvfhs_companies/part-0.parquet                      the licence-number lookup
#     hvfhv_trips_iceberg/{metadata,data}/…               the sample again, as Iceberg
#     manifest.json                                       written by manifest.sh, next
#
# VERSION DIRECTORIES ARE IMMUTABLE (README "Changing the data"). This script always
# renders into a directory named by `param artifact_version`, and it WIPES that
# directory first — a publish tree assembled on top of a previous run's leftovers is
# how a check that asserts "the output contains X" passes on a file two runs ago put
# there (MISTAKES.md, 2026-09-04). Changing the data means bumping the param, not
# editing a rendered tree.

set -euo pipefail
SD_SCRIPT=publish-layout
SD_ROOT="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SD_ROOT/../.." && pwd)"
# shellcheck source=../sample-data/lib/common.sh
source "$REPO_ROOT/scripts/sample-data/lib/common.sh"
# shellcheck source=lib/lake.sh
source "$SD_ROOT/lib/lake.sh"

TABLES="$SD_ROOT/work/tables"
ICE="$SD_ROOT/work/iceberg"
VERSION=$(lock_field param artifact_version)
[ -n "$VERSION" ] || die "sources.lock has no 'param artifact_version'"
OUT="$SD_ROOT/work/publish/lake/$VERSION"

# link_tree <src> <dest> — HARD-LINK the tree rather than copy it, falling back to a
# copy where the filesystem will not (a different volume, or --copy).
#
# Not a micro-optimisation: the full window's tables are several GB, and a plain
# `cp -R` needs that much again for a tree whose files are byte-identical to the ones
# it came from and are never modified afterwards — transform.sh writes work/tables/,
# this script only arranges it, and manifest.sh adds one NEW file. On a build box
# with 11 GB free (which is what the 088 lane had) the copy alone is the difference
# between a build that finishes and one that fills the disk. `--copy` exists for the
# case where the caller wants two independent trees on purpose.
link_tree() {
  local src="$1" dest="$2"
  if [ "${PUBLISH_COPY:-0}" = "1" ]; then
    cp -R "$src" "$dest"
    return
  fi
  cp -Rl "$src" "$dest" 2>/dev/null || cp -R "$src" "$dest"
}

step "rendering $OUT"
rm -rf "$OUT"
mkdir -p "$OUT"

# Every table checksums.spec declares must arrive, and it must arrive at the path
# checksums.spec names. The spec is the one list; a table added there and forgotten
# here fails LOUDLY at the copy rather than quietly at the upload.
copied=0
for table in $(lake_tables); do
  format=$(lake_spec_field "$table" 2)
  case "$format" in
    parquet)
      src="$TABLES/$table"
      [ -d "$src" ] || die "checksums.spec declares table '$table' but $src does not exist — run transform.sh"
      link_tree "$src" "$OUT/$table"
      ;;
    iceberg)
      # The Iceberg table was written by iceberg_write.py directly into the bucket
      # KEY space under work/iceberg/, because its metadata records absolute s3://
      # paths and it cannot be relocated afterwards. So this is a copy of a tree that
      # already believes it lives at its final URI — which is exactly why the copy is
      # safe.
      src="$ICE/$(lake_publish_key)/$table"
      [ -d "$src" ] || die "checksums.spec declares Iceberg table '$table' but $src does not exist — run transform.sh --finalize"
      link_tree "$src" "$OUT/$table"
      ;;
    *) die "checksums.spec row '$table' has unknown format '$format'" ;;
  esac
  copied=$((copied + 1))
done
[ "$copied" -gt 0 ] || die "checksums.spec declared no tables — nothing was rendered, which is not the same as a clean run"

# Nothing that is not a published table may be in the tree. The throwaway catalog
# the Iceberg writer used, a stray .DS_Store, an editor backup: each would be
# uploaded, checksummed by nobody, and served forever.
strays=$(find "$OUT" -type f \( -name '.*' -o -name '*.tmp' -o -name '*.db' \) | sort)
if [ -n "$strays" ]; then
  die "the rendered tree contains files that are not published artifacts:
$strays"
fi

# The tree, per table: object count and bytes. A full per-object listing is 700+
# lines once the window is partitioned by day, which is a listing nobody reads —
# SHOW_TREE=1 prints it when someone actually wants it.
step "rendered tree — $(lake_publish_prefix)/"
python3 "$SD_ROOT/lib/tree-report.py" "$OUT" "${SHOW_TREE:-0}" >&2

log "OK — $copied table(s) rendered into $OUT
  next: ./scripts/sample-data-lake/manifest.sh writes manifest.json into this tree
  (re-run with SHOW_TREE=1 for the per-object listing)"
