#!/usr/bin/env bash
# shellcheck source-path=SCRIPTDIR
# scripts/sample-data-lake/verify.sh — the dp-lake artifact proof.
#
#   ./scripts/sample-data-lake/verify.sh [publish-dir]   # default work/publish/lake/<version>
#
# Re-derives, from the RENDERED TREE ALONE, everything manifest.json asserts:
#
#   1. every listed object's SHA-256 and byte size — and that the tree contains no
#      object the manifest does NOT list (an unlisted file would be uploaded, served
#      forever and checksummed by nobody);
#   2. every table's row count and ordered-stream content checksum, re-read from the
#      published Parquet through the pinned DuckDB;
#   3. every PARTITION's row count, plus the structural claim a total cannot see:
#      each row's pickup_at falls on the day its partition names;
#   4. the Iceberg table, opened THROUGH ITS OWN METADATA (not by globbing its data
#      directory), and its content proved identical to the Parquet sample — the two
#      shapes are supposed to be the same rows;
#   5. the licence gate: how many provenance rows still ship license_verified: null.
#
# It does NOT need Docker, an engine or a network: there is nothing to restore. That
# is the one way this family's verifier is easier than the mobility family's, and it
# is a consequence of the whole point — the lake is read in place.
#
# Still deliberately NOT a gate task. It reads the whole published corpus, which for
# the full window is several GB; wiring it into `./gradlew build` would make the
# standard gate non-hermetic to prove something about an artifact that changes only
# when somebody rebuilds it. It is a documented, runnable procedure whose output goes
# in the build record — see README.md.

set -euo pipefail
SD_SCRIPT=verify
SD_ROOT="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SD_ROOT/../.." && pwd)"
# shellcheck source=../sample-data/lib/common.sh
source "$REPO_ROOT/scripts/sample-data/lib/common.sh"
# shellcheck source=lib/lake.sh
source "$SD_ROOT/lib/lake.sh"

VERSION=$(lock_field param artifact_version)
ART="${1:-$SD_ROOT/work/publish/lake/$VERSION}"
[ -f "$ART/manifest.json" ] || die "no manifest.json in '$ART' — run publish-layout.sh then manifest.sh first"

DUCKDB=$(duckdb_bin)
PREFIX=$(lake_publish_prefix)

FAILURES=0
fail() { printf '%s: FAIL — %s\n' "$SD_SCRIPT" "$*" >&2; FAILURES=$((FAILURES + 1)); }
pass() { printf '%s: ok   — %s\n' "$SD_SCRIPT" "$*" >&2; }

mjson() { python3 -c "
import json,sys
m=json.load(open('$ART/manifest.json'))
$1"; }

# --- 1. object integrity ----------------------------------------------------
step "object integrity"
listed=0
while IFS=$'\t' read -r key want bytes; do
  p="$ART/$key"
  listed=$((listed + 1))
  if [ ! -f "$p" ]; then fail "manifest lists '$key' but it is not in $ART"; continue; fi
  got=$(sha256_of "$p")
  gotb=$(wc -c < "$p" | tr -d ' ')
  if [ "$got" != "$want" ]; then fail "$key sha256 mismatch (manifest=$want actual=$got)"
  elif [ "$gotb" != "$bytes" ]; then fail "$key size mismatch (manifest=$bytes actual=$gotb)"; fi
done < <(mjson "
for o in m['objects']: print('\t'.join([o['key'], o['sha256'], str(o['bytes'])]))")
[ "$listed" -gt 0 ] || fail "the manifest lists no objects at all"
pass "$listed object(s) checked against the manifest"

# The other direction, which a per-object loop cannot see: a file in the tree that
# the manifest never mentions. manifest.json itself is the one exemption — it cannot
# carry its own hash.
extra=$(comm -23 \
  <(cd "$ART" && find . -type f | sed 's|^\./||' | grep -v '^manifest\.json$' | sort) \
  <(mjson "
for o in sorted(m['objects'], key=lambda x: x['key']): print(o['key'])" | sort))
if [ -n "$extra" ]; then
  fail "the tree contains object(s) the manifest does not list:
$extra"
else
  pass "the tree contains nothing the manifest does not list"
fi

# --- 2/3/4. per-table re-derivation ----------------------------------------
step "table fingerprints, re-derived from the published objects"
compared=0
expected=$(mjson "print(len(m['tables']))")
for table in $(lake_tables); do
  format=$(lake_spec_field "$table" 2)
  order=$(lake_spec_field "$table" 4)
  want_n=$(mjson "print(next((t['row_count'] for t in m['tables'] if t['name']=='$table'), ''))")
  want_ck=$(mjson "print(next((t['checksum'] for t in m['tables'] if t['name']=='$table'), ''))")
  if [ -z "$want_n" ]; then fail "$table is in checksums.spec but the manifest has no fingerprint for it"; continue; fi
  compared=$((compared + 1))

  case "$format" in
    parquet)
      glob="$ART/$(lake_spec_field "$table" 3)"
      n=$(lake_count_parquet "$glob")
      ck=$(lake_checksum_parquet "$glob" "$order")
      ;;
    iceberg)
      # See manifest.sh: process substitution hides the producer's exit status, so
      # an empty reading is treated as the failure it is rather than compared.
      read -r n ck _loc < <("$SD_ROOT/lib/iceberg-venv.sh" run "$SD_ROOT/lib/iceberg_stat.py" \
        --stage "$ART" --prefix "$PREFIX" --table-uri "$PREFIX/$table" --order "$order" --duckdb "$DUCKDB")
      if [ -z "$n" ] || [ -z "$ck" ]; then
        fail "$table: iceberg_stat.py produced no reading — see its output above"
        continue
      fi
      ;;
    *) fail "checksums.spec row '$table' has unknown format '$format'"; continue ;;
  esac

  if [ "$n" != "$want_n" ]; then fail "$table row count: manifest=$want_n re-derived=$n"
  elif [ "sha256:$ck" != "$want_ck" ]; then fail "$table content checksum: manifest=$want_ck re-derived=sha256:$ck"
  else pass "$table  $n rows  $want_ck"; fi
done

# NON-VACUITY (the 023/F4 lesson the mobility verifier records): comparing fewer
# tables than the manifest declares is a FAILURE, whatever the individual results
# said. Without this, a checksums.spec that lost a row would verify "clean".
if [ "$compared" -lt "$expected" ]; then
  fail "only $compared of $expected manifest table(s) were compared — a subset comparison is a failure"
fi

# --- 3b. partitions ---------------------------------------------------------
step "partition integrity"
part_table=$(mjson "print(next((t['name'] for t in m['tables'] if 'partition_column' in t), ''))")
if [ -z "$part_table" ]; then
  fail "no table in the manifest declares a partition_column — the big table is supposed to be partitioned"
else
  part_col=$(mjson "print(next(t['partition_column'] for t in m['tables'] if t['name']=='$part_table'))")
  glob="$ART/$(lake_spec_field "$part_table" 3)"
  declared=$(mjson "print(len(next(t.get('partitions', {}) for t in m['tables'] if t['name']=='$part_table')))")
  diff_out=$(diff \
    <(mjson "
for d, n in sorted(next(t.get('partitions', {}) for t in m['tables'] if t['name']=='$part_table').items()): print(d, n)") \
    <("$DUCKDB" -noheader -list -c "
SELECT $part_col || ' ' || count(*) FROM read_parquet('$glob', hive_partitioning = true) GROUP BY $part_col ORDER BY $part_col;" < /dev/null) \
    || true)
  if [ -n "$diff_out" ]; then
    fail "$part_table partition row counts differ from the manifest (manifest < / re-derived >):
$diff_out"
  else
    pass "$part_table  $declared partition(s), every row count matches the manifest"
  fi
  # The claim a total row count can never make: a row is IN the day it says it is.
  # A partitioned write that mis-routed rows keeps the table total exactly right.
  stray=$("$DUCKDB" -noheader -list -c "
SELECT count(*) FROM read_parquet('$glob', hive_partitioning = true)
WHERE CAST(pickup_at AS DATE) <> $part_col;" < /dev/null)
  if [ "$stray" != "0" ]; then
    fail "$stray row(s) sit in a $part_col partition that does not match their own pickup_at"
  else
    pass "every row's pickup_at falls on the day its partition names"
  fi
fi

# --- 5. licence gate --------------------------------------------------------
step "licence gate"
unverified=$(mjson "print(sum(1 for p in m['provenance'] if p['license_verified'] is None))")
total=$(mjson "print(len(m['provenance']))")
if [ "$unverified" != "0" ]; then
  log "NOT PUBLISHABLE — $unverified of $total provenance rows have license_verified: null.
  This is the design §8 go-live gate, not a defect: the owner verifies each source's
  current terms and records the date in sources.lock. The dossier of record is
  ../datapipelines-orchestration/notes/2026-09-07-lake-license-dossier.md.
  verify.sh reports it and does NOT fail on it, because an unpublished build is
  expected to be in this state."
else
  pass "every provenance row carries a license_verified date"
fi

step "verify complete"
if [ "$FAILURES" -gt 0 ]; then die "$FAILURES check(s) failed"; fi
log "OK — every object matched its manifest, every table's row count and content
  checksum was re-derived from the published objects, every partition was counted,
  and the Iceberg table was read through its own metadata."
