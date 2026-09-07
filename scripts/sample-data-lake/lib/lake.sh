#!/usr/bin/env bash
# scripts/sample-data-lake/lib/lake.sh — the small amount of machinery that is
# THIS family's and not the mobility family's. SOURCED after
# ../sample-data/lib/common.sh, which supplies everything shared: logging,
# sha256_of / sha256_expect, sources.lock parsing, fetch, the pinned DuckDB CLI,
# window_day_end.
#
#   source "$REPO_ROOT/scripts/sample-data/lib/common.sh"
#   source "$SD_ROOT/lib/lake.sh"
#
# Why not a full lib/common.sh of its own: the trade family (the most recent
# sibling, scripts/sample-data-trade/*.sh) already reuses the mobility library and
# points it at its own lock via SD_ROOT. Two copies of the SHA-verification and
# tool-install code would be two things to fix when one of them is wrong.

# lake_months <start YYYY-MM> <end YYYY-MM> — one month per line, inclusive.
# python3 for the same reason lib/common.sh's window_day_end uses it: BSD and GNU
# date disagree on the arithmetic flags.
lake_months() {
  python3 - "$1" "$2" <<'PY'
import sys
s, e = sys.argv[1], sys.argv[2]
y, m = map(int, s.split('-')); ey, em = map(int, e.split('-'))
while (y, m) <= (ey, em):
    print(f"{y:04d}-{m:02d}")
    m += 1
    if m == 13:
        y, m = y + 1, 1
PY
}

# lake_hvfhv_url <YYYY-MM> — the upstream URL for one HVFHV month. ONE derivation,
# used by pin.sh (which writes the `url` lines) and by nothing else: download.sh
# reads the URL back out of sources.lock, so the lock stays the authority for what
# was actually fetched rather than a cache of what this function would say today.
lake_hvfhv_url() {
  echo "https://d37ci6vzurychx.cloudfront.net/trip-data/fhvhv_tripdata_$1.parquet"
}

# lake_publish_prefix — the S3 URI of the version directory, from the lock's own
# params. Baked into the Iceberg table's metadata (an Iceberg table records
# ABSOLUTE paths, so it must be written knowing where it will live — see
# transform.sh), and printed by publish-layout.sh as the upload target.
lake_publish_prefix() {
  local bucket version
  bucket=$(lock_field param bucket_uri)
  version=$(lock_field param artifact_version)
  [ -n "$bucket" ] && [ -n "$version" ] || die "sources.lock is missing 'param bucket_uri' or 'param artifact_version'"
  echo "$bucket/$version"
}

# lake_tables — the published table names, in manifest order. ONE list: manifest.sh
# builds the tables[] block from it, verify.sh re-derives every entry from it, and
# publish-layout.sh refuses to render a tree that is missing one. A second list
# would drift and a green verify would prove nothing (checksums.spec makes the same
# argument for the mobility family).
lake_tables() {
  awk '/^[[:space:]]*#/ { next } NF == 0 { next } { print $1 }' "$SD_ROOT/checksums.spec"
}

# lake_spec_field <table> <n> — a field of the checksums.spec row for <table>.
# n = 1 name, 2 format, 3 path/glob, 4 = the ORDER BY expression (the rest of the
# line, which contains spaces and commas and is therefore never a fixed field).
lake_spec_field() {
  awk -v t="$1" -v n="$2" '
    /^[[:space:]]*#/ { next }
    NF == 0          { next }
    $1 == t {
      if (n + 0 <= 3) { print $(n + 0) }
      else { s = ""; for (i = 4; i <= NF; i++) s = s (i > 4 ? " " : "") $i; print s }
      exit
    }
  ' "$SD_ROOT/checksums.spec"
}

# lake_publish_key — the bucket KEY of the version directory (no scheme, no
# bucket): `sample-data/lake/v1`. The Iceberg writer stages the bucket key space as
# a directory tree, so this is where its output sits inside work/iceberg/.
lake_publish_key() {
  local prefix
  prefix=$(lake_publish_prefix)
  echo "${prefix#s3://*/}"
}

# lake_publish_bucket — the bucket name alone (`datapipelines-co`).
lake_publish_bucket() {
  local prefix rest
  prefix=$(lake_publish_prefix)
  rest="${prefix#s3://}"
  echo "${rest%%/*}"
}

# lake_count_parquet <glob> / lake_checksum_parquet <glob> <order-by>
#
# The Parquet analogue of ../sample-data/lib/engines.sh's checksum_duckdb, and
# deliberately the same shape: `-noheader -list` streams `|`-separated values
# through the PINNED CLI, and the hashing happens OUTSIDE the engine so no engine's
# own hash function is part of the contract. DECIMAL and TIMESTAMP render
# deterministically inside one pinned CLI version, which is the determinism
# boundary this whole family already claims.
lake_count_parquet() {
  "$(duckdb_bin)" -noheader -list -c "SELECT count(*) FROM read_parquet('$1', union_by_name = true, hive_partitioning = true);" < /dev/null
}

lake_checksum_parquet() {
  # SELECT *, not the ORDER BY list: the checksum has to cover EVERY column, and
  # the ordering key is only a prefix of them for the aggregate tables. (For
  # hvfhv_trips, `*` under hive_partitioning also brings pickup_date back in from
  # the path — harmless, and it means the partition a row landed in is inside the
  # fingerprint too.)
  "$(duckdb_bin)" -noheader -list -c \
    "SELECT * FROM read_parquet('$1', union_by_name = true, hive_partitioning = true) ORDER BY $2;" < /dev/null \
    | shasum -a 256 | awk '{print $1}'
}
