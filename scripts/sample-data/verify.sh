#!/usr/bin/env bash
# scripts/sample-data/verify.sh — the artifact proof (design §9 "Build").
#
#   ./scripts/sample-data/verify.sh [artifacts-dir]     # default work/artifacts
#
# Re-derives, from the BUILT ARTIFACTS ALONE, everything manifest.json asserts:
#
#   1. every artifact's SHA-256 and byte size match the manifest;
#   2. pg-trips.dump restores into a throwaway Postgres of the pinned major and
#      yields the manifest's row counts and content checksums;
#   3. mysql-weather.sql.gz likewise into a throwaway MySQL;
#   4. nyc_reference.db likewise, opened directly;
#   5. examples.json is STRUCTURALLY what the app's seeder accepts, and carries
#      no pipeline ids (a pipelines.id is a GLOBAL primary key — an id-carrying
#      seed lets the first user's login claim it and breaks every later user's
#      provisioning). STRUCTURAL ONLY, by design: shape, ids, references. The
#      SEMANTIC validation — running every template and pipeline through the
#      app's own save-time validators (TemplateValidator, ReferenceRules, 042's
#      parameter_interpolated rule) — lives in the build, as the templates
#      module's SampleDataExamplesContentTest (049 C1). T70 happened precisely
#      because this step looked sufficient and was not: the v1 artifact passed
#      it while still carrying the interpolations the app refuses at seeding.
#      Published-vs-repo drift is check-published.sh's job (049 C2) — this
#      script never sees the published copy.
#
# This is deliberately NOT a gate task. It needs Docker, several GB and minutes;
# wiring it into `./gradlew build` would make the standard gate non-hermetic.
# It is a documented, runnable procedure whose output goes in the build record —
# see scripts/sample-data/README.md.

set -euo pipefail
SD_SCRIPT=verify
SD_ROOT="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SD_ROOT/../.." && pwd)"
source "$SD_ROOT/lib/common.sh"
source "$SD_ROOT/lib/engines.sh"

ART="${1:-$SD_ROOT/work/artifacts}"
[ -f "$ART/manifest.json" ] || die "no manifest.json in '$ART' — run manifest.sh first"

require_cmd docker "verify.sh restores the dumps into throwaway pinned engines"
require_cmd sqlite3 "verify.sh opens the SQLite artifact directly"
require_cmd gzip "the MySQL artifact is gzipped"

trap sd_cleanup_engines EXIT
FAILURES=0
fail() { printf '%s: FAIL — %s\n' "$SD_SCRIPT" "$*" >&2; FAILURES=$((FAILURES + 1)); }
pass() { printf '%s: ok   — %s\n' "$SD_SCRIPT" "$*" >&2; }

mjson() { python3 -c "
import json,sys
m=json.load(open('$ART/manifest.json'))
$1" ; }

# --- 1. artifact integrity --------------------------------------------------
step "artifact checksums"
while IFS=$'\t' read -r file want bytes; do
  p="$ART/$file"
  if [ ! -f "$p" ]; then fail "manifest lists '$file' but it is not in $ART"; continue; fi
  got=$(sha256_of "$p")
  gotb=$(wc -c < "$p" | tr -d ' ')
  if [ "$got" != "$want" ]; then fail "$file sha256 mismatch (manifest=$want actual=$got)"
  elif [ "$gotb" != "$bytes" ]; then fail "$file size mismatch (manifest=$bytes actual=$gotb)"
  else pass "$file  $bytes bytes  sha256=${want:0:16}…"; fi
done < <(mjson "
for a in m['artifacts']: print('\t'.join([a['file'], a['sha256'], str(a['bytes'])]))")

# The manifest's own fingerprints, as a lookup for stages 2-4.
mjson "
for t in m['tables']: print('\t'.join([t['engine'].lower(), t['table'], str(t['row_count']), t['checksum']]))" \
  > "$SD_ROOT/work/.verify-expected.tsv"

compare_engine() { # <engine> <handle>
  local engine="$1" handle="$2" e t n ck want_n want_ck compared=0 expected=0
  # The manifest's table count for THIS engine — the denominator of the
  # non-vacuity guard below.
  expected=$(awk -F'\t' -v e="$engine" '$1==e {n++} END {print n+0}' "$SD_ROOT/work/.verify-expected.tsv")
  while IFS=$'\t' read -r e t n ck; do
    [ "$e" = "$engine" ] || continue
    compared=$((compared + 1))
    want_n=$(awk -F'\t' -v e="$e" -v t="$t" '$1==e && $2==t {print $3}' "$SD_ROOT/work/.verify-expected.tsv")
    want_ck=$(awk -F'\t' -v e="$e" -v t="$t" '$1==e && $2==t {print $4}' "$SD_ROOT/work/.verify-expected.tsv")
    if [ -z "$want_n" ]; then fail "$e.$t restored but the manifest has no fingerprint for it"; continue; fi
    if [ "$n" != "$want_n" ]; then fail "$e.$t row count: manifest=$want_n restored=$n"
    elif [ "sha256:$ck" != "$want_ck" ]; then fail "$e.$t content checksum: manifest=$want_ck restored=sha256:$ck"
    else pass "$e.$t  $n rows  $want_ck"; fi
  done < <(checksum_rows "$engine" "$handle")
  # NON-VACUITY (023 F4): checksum_rows runs in process substitution, so its `die`
  # exits only the subshell and the loop simply ends early — the old code compared
  # the subset that arrived and could print overall OK having verified half the
  # manifest's tables (the exact class load.sh's manifest guard prevents). A
  # comparison of fewer tables than the manifest declares for this engine is a
  # FAIL, whatever the individual results said.
  if [ "$compared" -lt "$expected" ]; then
    fail "$engine: only $compared of $expected manifest table(s) were compared — a subset comparison is a failure"
  fi
}

# --- 2. postgres ------------------------------------------------------------
step "restoring pg-trips.dump into a throwaway $(image_ref postgres)"
PG=$(engine_start_postgres verify)
docker exec -i "$PG" pg_restore --no-owner --no-privileges --exit-on-error -h 127.0.0.1 \
  -U build -d dp_sample_trips < "$ART/pg-trips.dump"
compare_engine postgres "$PG"

# --- 3. mysql ---------------------------------------------------------------
step "restoring mysql-weather.sql.gz into a throwaway $(image_ref mysql)"
MY=$(engine_start_mysql verify)
gzip -dc "$ART/mysql-weather.sql.gz" | mysql_in "$MY"
compare_engine mysql "$MY"

# --- 4. sqlite --------------------------------------------------------------
step "opening nyc_reference.db"
compare_engine sqlite "$ART/nyc_reference.db"
# The SQLite artifact IS a live database, so ask it whether it thinks so.
integrity=$(sqlite3 -noheader "$ART/nyc_reference.db" "PRAGMA integrity_check;")
[ "$integrity" = "ok" ] && pass "sqlite PRAGMA integrity_check = ok" || fail "sqlite integrity_check: $integrity"

# --- 5. examples.json -------------------------------------------------------
step "examples.json"
python3 - "$ART/examples.json" <<'PY' || exit 1
import json, re, sys
path = sys.argv[1]
doc = json.load(open(path, encoding="utf-8"))
bad = []
if not isinstance(doc, dict):
    bad.append("top level is not a JSON object (the seeder refuses it at startup)")
if not doc.get("templates") and not doc.get("pipelines"):
    bad.append("declares neither 'templates' nor 'pipelines'")

UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", re.I)
for i, p in enumerate(doc.get("pipelines", [])):
    if "id" in p:
        bad.append(f"pipelines[{i}] carries a root 'id' — pipelines.id is a GLOBAL primary key, "
                   "so a seeded id is claimed by the first user's login and every later "
                   "provisioning collides on it")
    for f in ("version", "owner", "created_at", "updated_at"):
        if f in p:
            bad.append(f"pipelines[{i}] carries the server-assigned field '{f}'")
    for j, n in enumerate(p.get("nodes", [])):
        tid = (n.get("template") or {}).get("id")
        if tid and UUID.match(tid):
            bad.append(f"pipelines[{i}].nodes[{j}] references a template by UUID, not by name")
# Template ids are NAMES (templates.name, unique per workspace — metadata-db §4.8), not
# global keys; they are required, because a pipeline node resolves its template by that name.
names = {t.get("id") for t in doc.get("templates", [])}
for t in doc.get("templates", []):
    if not t.get("id"):
        bad.append("a template entry has no 'id' — its name is how a pipeline node references it")
    elif UUID.match(t["id"]):
        bad.append(f"template id '{t['id']}' is a UUID; template ids are names")
for i, p in enumerate(doc.get("pipelines", [])):
    for j, n in enumerate(p.get("nodes", [])):
        tid = (n.get("template") or {}).get("id")
        if tid and tid not in names:
            bad.append(f"pipelines[{i}].nodes[{j}] references template '{tid}', which this file "
                       "does not define — seeding would fail with pipeline.import.missing_template")
if bad:
    for b in bad:
        print(f"verify: FAIL — examples.json: {b}", file=sys.stderr)
    sys.exit(1)
print(f"verify: ok   — examples.json: {len(doc.get('templates', []))} templates, "
      f"{len(doc.get('pipelines', []))} pipelines, no pipeline ids, every template reference resolves",
      file=sys.stderr)
PY

# --- 6. licence gate --------------------------------------------------------
step "licence gate"
unverified=$(mjson "print(sum(1 for p in m['provenance'] if p['license_verified'] is None))")
total=$(mjson "print(len(m['provenance']))")
if [ "$unverified" != "0" ]; then
  log "NOT PUBLISHABLE — $unverified of $total provenance rows have license_verified: null.
  This is the design §8 go-live gate, not a defect: the owner verifies each
  source's current terms and records the date. verify.sh reports it and does
  NOT fail on it, because an unpublished build is expected to be in this state."
else
  pass "every provenance row carries a license_verified date"
fi

rm -f "$SD_ROOT/work/.verify-expected.tsv"
step "verify complete"
if [ "$FAILURES" -gt 0 ]; then die "$FAILURES check(s) failed"; fi
log "OK — every artifact matched its manifest, and every row count and content checksum was re-derived from the artifacts"
