#!/usr/bin/env bash
# shellcheck source-path=SCRIPTDIR
# scripts/sample-data-lake/manifest.sh — stage 4 of the dp-lake build: write
# manifest.json into the rendered publish tree.
#
#   ./scripts/sample-data-lake/manifest.sh
#
# The manifest is what every consumer trusts. Unlike the mobility family's, nothing
# RESTORES from it — the lake is read in place — so it has one extra job: it is the
# registry seed. 089 reads its `tables[]` block to register the lake datasource's
# tables without anybody retyping a path, a format or a partition column.
#
# Three things it asserts, and the different weight each carries:
#   * per OBJECT: sha256 + bytes. Survives publication; a consumer can prove it got
#     the bytes we published. Does NOT survive a rebuild (Parquet encoders and the
#     Iceberg writer are not byte-stable — see transform.sh).
#   * per TABLE: row_count + a checksum of the ordered stream (checksums.spec).
#     THIS is the determinism contract: a rebuild must reproduce it exactly.
#   * provenance: where the rows came from, and `license_verified`.
#
# LICENCE GATE (design §8). Every provenance row's `license_verified` comes from
# sources.lock's `param license_verified` — set ONLY by the owner after checking the
# current terms. The dossier of record is
# ../datapipelines-orchestration/notes/2026-09-07-lake-license-dossier.md. When the
# param is absent or the literal "null" the rows ship null, and **publishing with any
# null still present blocks go-live**. This build ships null: research is not a stamp.

set -euo pipefail
SD_SCRIPT=manifest
SD_ROOT="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SD_ROOT/../.." && pwd)"
# shellcheck source=../sample-data/lib/common.sh
source "$REPO_ROOT/scripts/sample-data/lib/common.sh"
# shellcheck source=lib/lake.sh
source "$SD_ROOT/lib/lake.sh"

VERSION=$(lock_field param artifact_version)
OUT="$SD_ROOT/work/publish/lake/$VERSION"
[ -d "$OUT" ] || die "no rendered tree at $OUT — run publish-layout.sh first"

WINDOW_START=$(lock_field param window_start)
WINDOW_END=$(lock_field param window_end)
MODULUS=$(lock_field param trips_sample_modulus)
DUCKDB_VERSION=$(lock_field param duckdb_version)
PYICEBERG_VERSION=$(lock_field param pyiceberg_version)
DAY_START="$WINDOW_START-01"
DAY_END=$(window_day_end "$WINDOW_END")
PREFIX=$(lake_publish_prefix)

MONTHLY_COUNTS="$SD_ROOT/work/tables/monthly-counts.tsv"
ICE_SUMMARY="$SD_ROOT/work/iceberg/iceberg-write.json"

# --- per-table fingerprints, derived from the PUBLISHED objects --------------
#
# Not from work/tables/: the manifest must describe what is about to be uploaded.
step "fingerprinting $(lake_tables | wc -l | tr -d ' ') table(s) from the rendered tree"
FP="$SD_ROOT/work/.manifest-tables.tsv"
: > "$FP"
for table in $(lake_tables); do
  format=$(lake_spec_field "$table" 2)
  order=$(lake_spec_field "$table" 4)
  case "$format" in
    parquet)
      glob="$OUT/$(lake_spec_field "$table" 3)"
      n=$(lake_count_parquet "$glob")
      ck=$(lake_checksum_parquet "$glob" "$order")
      loc="$PREFIX/$(lake_spec_field "$table" 3)"
      extra=""
      # Per-PARTITION row counts for a partitioned table. A table total cannot see a
      # row that landed in the wrong day directory; these can, and verify.sh
      # re-derives them and additionally asserts every row's pickup_at agrees with
      # the partition it sits in.
      if [ "$table" = "hvfhv_trips" ]; then
        "$(duckdb_bin)" -noheader -list -c \
          "SELECT pickup_date || chr(9) || count(*) FROM read_parquet('$glob', hive_partitioning = true) GROUP BY pickup_date ORDER BY pickup_date;" \
          < /dev/null > "$SD_ROOT/work/.manifest-partitions.tsv"
        extra="$SD_ROOT/work/.manifest-partitions.tsv"
      fi
      ;;
    iceberg)
      # Read through the table's OWN metadata, not by globbing its data directory:
      # what is fingerprinted has to be what an Iceberg reader would see, or the
      # fingerprint says nothing about whether the metadata is coherent.
      # `read < <(...)` cannot report the producer's exit status, so a crashed
      # reader would leave $n empty and the manifest would record nothing rather
      # than fail (MISTAKES.md: an unread outcome is an assumed success). The
      # emptiness check is the status check.
      read -r n ck loc < <("$SD_ROOT/lib/iceberg-venv.sh" run "$SD_ROOT/lib/iceberg_stat.py" \
        --stage "$OUT" --prefix "$PREFIX" --table-uri "$PREFIX/$table" --order "$order" --duckdb "$(duckdb_bin)")
      [ -n "$n" ] && [ -n "$ck" ] && [ -n "$loc" ] \
        || die "iceberg_stat.py produced no reading for '$table' — see its output above"
      extra=""
      ;;
    *) die "checksums.spec row '$table' has unknown format '$format'" ;;
  esac
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$table" "$format" "$n" "$ck" "$loc" "$extra" >> "$FP"
  log "$table: $n rows, sha256:${ck:0:16}…"
done

# --- the source-side cross-check -------------------------------------------
#
# transform.sh recorded, per month, the row counts the WRITING session saw in its
# own views. The counts above come from the written objects. Two derivations of one
# number, from opposite ends of the write; a partition write that silently dropped
# rows shows up here and nowhere else (MISTAKES.md: a comparison's two sides must
# come from two OBSERVED events).
if [ -f "$MONTHLY_COUNTS" ]; then
  src_full=$(awk -F'\t' '{s += $2} END {print s+0}' "$MONTHLY_COUNTS")
  src_sample=$(awk -F'\t' '{s += $3} END {print s+0}' "$MONTHLY_COUNTS")
  out_full=$(awk -F'\t' '$1 == "hvfhv_trips" {print $3}' "$FP")
  out_sample=$(awk -F'\t' '$1 == "hvfhv_trips_sample" {print $3}' "$FP")
  [ "$src_full" = "$out_full" ] \
    || die "hvfhv_trips row count disagrees between the write and the published objects:
  transform.sh's views said : $src_full
  the published Parquet has : $out_full"
  [ "$src_sample" = "$out_sample" ] \
    || die "hvfhv_trips_sample row count disagrees between the write and the published objects:
  transform.sh's views said : $src_sample
  the published Parquet has : $out_sample"
  log "source-side cross-check OK — $src_full trips, $src_sample sampled, from $(wc -l < "$MONTHLY_COUNTS" | tr -d ' ') month(s)"
else
  die "$MONTHLY_COUNTS is missing — it is written by transform.sh and is the only
  independent record of what the build believed it wrote. A manifest without it
  would assert the published objects agree with themselves, which is not a check."
fi

# The sample and its Iceberg copy are the same rows in two shapes. If they are not,
# one of them is wrong and 089's "same answer either way" demo is a lie.
ck_sample=$(awk -F'\t' '$1 == "hvfhv_trips_sample" {print $4}' "$FP")
ck_iceberg=$(awk -F'\t' '$1 == "hvfhv_trips_iceberg" {print $4}' "$FP")
[ "$ck_sample" = "$ck_iceberg" ] \
  || die "hvfhv_trips_sample and hvfhv_trips_iceberg do not hold the same rows:
  parquet : $ck_sample
  iceberg : $ck_iceberg"
log "parquet/iceberg content identity OK — both sides checksum to ${ck_sample:0:16}…"

python3 "$SD_ROOT/lib/manifest_build.py" \
  --tree "$OUT" \
  --fingerprints "$FP" \
  --lock "$SOURCES_LOCK" \
  --prefix "$PREFIX" \
  --version "$VERSION" \
  --window-start "$WINDOW_START" --window-end "$WINDOW_END" \
  --day-start "$DAY_START" --day-end "$DAY_END" \
  --modulus "$MODULUS" \
  --duckdb-version "$DUCKDB_VERSION" \
  --pyiceberg-version "$PYICEBERG_VERSION" \
  --iceberg-summary "$ICE_SUMMARY" \
  --raw-dir "$SD_ROOT/work/raw"

rm -f "$FP" "$SD_ROOT/work/.manifest-partitions.tsv"
