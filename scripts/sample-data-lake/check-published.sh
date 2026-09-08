#!/usr/bin/env bash
# shellcheck source-path=SCRIPTDIR
# scripts/sample-data-lake/check-published.sh — the dp-lake publish-confirmation
# guard: does the bucket actually hold what the build said it would?
#
#   ./scripts/sample-data-lake/check-published.sh --family lake v1
#   ./scripts/sample-data-lake/check-published.sh v1            # --family defaults to lake
#   SAMPLE_LAKE_BASE_URL=https://… ./scripts/sample-data-lake/check-published.sh v1
#
# WHY THIS IS A SEPARATE SCRIPT FROM ../sample-data/check-published.sh, and not a
# third `--family` there. That script has exactly one contract — the published
# `examples.json` is byte-identical to the repo copy and to the manifest's declared
# checksum (the T70 defect class) — and its `--family nyc|trade` selector picks
# among families that share it. The lake family has NO examples.json: its content
# lives with the mobility family (the seeded pipeline is the mobility family's), and
# what can drift here is different — a published object that is not the object the
# manifest describes. Folding two unrelated contracts behind one flag would make
# "check-published passed" mean two different things. `--family lake` is accepted so
# the documented command reads like its sibling, and it is validated rather than
# ignored.
#
# What it checks, against the LIVE bucket:
#   1. the published manifest parses and its `version` matches the directory it came
#      from (an artifact that disagrees with its own prefix is not a version);
#   2. it declares no provenance row with license_verified: null — publishing one is
#      the design §8 go-live block, and this is the last place to catch it;
#   3. ONE OBJECT PER TABLE is fetched and its SHA-256 compared with the manifest.
#      Not the whole corpus: the full window is several GB and this is a
#      confirmation step, not a re-download. The object chosen is the FIRST the
#      manifest lists for that table, which for the partitioned table is the
#      earliest day — a stable, reviewable choice rather than a random one.
#   4. the Iceberg table's current metadata file is fetched and its recorded
#      locations are checked to be under the published prefix. This is the failure
#      that would otherwise be found by a user: an Iceberg table whose metadata
#      still names a build machine's directory is unreadable, and nothing about the
#      object listing shows it.
#
# Network by nature, so deliberately NOT part of `./gradlew build`: it is a release
# rehearsal step (docs/deployment.md Appendix B). Against an unpublished version it
# fails on the manifest fetch, which is exactly the upload gate.

set -euo pipefail
SD_SCRIPT=check-published
SD_ROOT="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SD_ROOT/../.." && pwd)"
# shellcheck source=../sample-data/lib/common.sh
source "$REPO_ROOT/scripts/sample-data/lib/common.sh"
# shellcheck source=lib/lake.sh
source "$SD_ROOT/lib/lake.sh"

DEFAULTS_ENV="$REPO_ROOT/deploy/env/defaults.env"
demo_env_get() { # key
  [ -f "$DEFAULTS_ENV" ] || return 0
  grep -E "^$1=" "$DEFAULTS_ENV" | head -1 | cut -d= -f2- || true
}

FAMILY=lake
VERSION=""
while [ $# -gt 0 ]; do
  case "$1" in
    --family) FAMILY="${2:-}"; shift 2 ;;
    --family=*) FAMILY="${1#--family=}"; shift ;;
    -*) die "unknown option '$1' — usage: $0 [--family lake] <version>" ;;
    *) [ -z "$VERSION" ] || die "two versions given ('$VERSION' and '$1')"
       VERSION="$1"; shift ;;
  esac
done
[ "$FAMILY" = "lake" ] \
  || die "unknown family '$FAMILY' — this script checks the lake family only; the
  nyc and trade families are ../sample-data/check-published.sh (a different contract:
  examples.json byte-identity)"

# The base URL and the pinned version come from the TRACKED settings file — the same
# file the running deployment loads (075/T117; the lake pair was pinned there by 089
# §E). The manifest's own https_base stays the fallback, derived from the lock rather
# than typed twice, for a checkout whose defaults.env predates the pins.
BASE="${SAMPLE_LAKE_BASE_URL:-$(demo_env_get SAMPLE_LAKE_BASE_URL)}"
if [ -z "$BASE" ]; then
  bucket=$(lake_publish_bucket)
  key=$(lake_publish_key)
  BASE="https://$bucket.s3.amazonaws.com/${key%/*}"
fi
[ -n "$VERSION" ] || VERSION="$(demo_env_get SAMPLE_LAKE_VERSION)"
[ -n "$VERSION" ] || VERSION="$(lock_field param artifact_version)"
[ -n "$VERSION" ] || die "no version: pass one, or set SAMPLE_LAKE_VERSION in $DEFAULTS_ENV"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
FAILURES=0
fail() { printf '%s: FAIL — %s\n' "$SD_SCRIPT" "$*" >&2; FAILURES=$((FAILURES + 1)); }
pass() { printf '%s: ok   — %s\n' "$SD_SCRIPT" "$*" >&2; }

step "fetching $BASE/$VERSION/manifest.json"
fetch "$BASE/$VERSION/manifest.json" "$WORK/manifest.json"

mjson() { python3 -c "
import json,sys
m=json.load(open('$WORK/manifest.json'))
$1"; }

MANIFEST_VERSION=$(mjson "print(m.get('version',''))")
[ "$MANIFEST_VERSION" = "$VERSION" ] \
  || die "the manifest at $BASE/$VERSION/ declares version '$MANIFEST_VERSION' — the
  published directory and its manifest disagree about what this is"
pass "manifest declares version $VERSION, $(mjson "print(m['object_count'])") objects, $(mjson "print(len(m['tables']))") tables"

# --- the licence gate, at the last possible moment --------------------------
unverified=$(mjson "print(sum(1 for p in m['provenance'] if p['license_verified'] is None))")
if [ "$unverified" != "0" ]; then
  fail "PUBLISHED WITH THE LICENCE GATE OPEN — $unverified provenance row(s) carry
  license_verified: null. Design §8: publishing with any null blocks go-live. Either
  the owner stamps 'param license_verified' in sources.lock, rebuilds and republishes
  a new version directory, or these objects come down."
else
  pass "every provenance row carries license_verified $(mjson "print(m['provenance'][0]['license_verified'])")"
fi

# --- one object per table ---------------------------------------------------
while IFS=$'\t' read -r table key want; do
  step "$table: fetching $key"
  if ! fetch "$BASE/$VERSION/$key" "$WORK/probe" 2>/dev/null; then
    fail "$table: $BASE/$VERSION/$key could not be fetched — the manifest lists an object the bucket does not serve"
    continue
  fi
  got=$(sha256_of "$WORK/probe")
  if [ "$got" != "$want" ]; then
    fail "$table: published $key sha256=$got but the manifest declares $want"
  else
    pass "$table: $key matches (sha256=${want:0:16}…)"
  fi
  rm -f "$WORK/probe"
done < <(mjson "
for t in m['tables']:
    prefix = t['name'] + '/'
    first = next((o for o in m['objects'] if o['key'] == t['name'] or o['key'].startswith(prefix)), None)
    if first: print('\t'.join([t['name'], first['key'], first['sha256']]))")

# --- the Iceberg table's own metadata ---------------------------------------
ICE_META=$(mjson "print(next((t.get('metadata_location','') for t in m['tables'] if t['format']=='iceberg'), ''))")
if [ -z "$ICE_META" ]; then
  fail "the manifest declares no Iceberg table — this family publishes one"
else
  ice_key="${ICE_META#*"/$VERSION/"}"
  step "iceberg: fetching $ice_key"
  if ! fetch "$BASE/$VERSION/$ice_key" "$WORK/ice.json" 2>/dev/null; then
    fail "the Iceberg metadata file the manifest names is not served: $BASE/$VERSION/$ice_key"
  else
    prefix=$(lake_publish_prefix)
    bad=$(python3 - "$WORK/ice.json" "$prefix" <<'PY'
import json, sys
doc = json.load(open(sys.argv[1], encoding="utf-8"))
prefix = sys.argv[2]
locations = [doc.get("location", "")]
locations += [s.get("manifest-list", "") for s in doc.get("snapshots", [])]
locations += [e.get("metadata-file", "") for e in doc.get("metadata-log", [])]
bad = [loc for loc in locations if loc and not loc.startswith(prefix)]
print("\n".join(bad))
PY
)
    if [ -n "$bad" ]; then
      fail "the published Iceberg metadata records location(s) outside $(lake_publish_prefix) —
  the table is unreadable where it is published:
$bad"
    else
      pass "the Iceberg metadata's recorded locations all sit under $(lake_publish_prefix)"
    fi
  fi
fi

step "check-published complete"
if [ "$FAILURES" -gt 0 ]; then die "$FAILURES check(s) failed against the published $FAMILY/$VERSION"; fi
log "ok — published $FAMILY/$VERSION agrees with its manifest (sampled one object per
  table), the Iceberg metadata points inside its own prefix, and the licence gate is closed"
