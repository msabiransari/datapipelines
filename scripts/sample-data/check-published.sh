#!/usr/bin/env bash
# scripts/sample-data/check-published.sh — the drift guard the 049 round adds (C2): the
# PUBLISHED sample-data artifact cannot silently disagree with the repo's
# content/examples.json — the defect class of T70, where the published v1 still carried
# the `${}` interpolations 042 had already migrated out of the repo copy, and the demo
# 500ed on first login for two days because nothing compared the two.
#
#   ./scripts/sample-data/check-published.sh [--family nyc|trade] <version>
#   ./scripts/sample-data/check-published.sh                 # the version deploy/env/demo.env pins
#   SAMPLE_BASE_URL=http://host.docker.internal:8099 ./scripts/sample-data/check-published.sh v2
#   SAMPLE_TRADE_BASE_URL=http://host.docker.internal:8099 ./scripts/sample-data/check-published.sh --family trade v2
#   SAMPLE_BASE_URL=file://$PWD/scripts/sample-data/work/artifacts-parent ...
#
# Compares THREE hashes and fails if any pair disagrees, naming which:
#   1. the repo copy        <family content dir>/examples.json
#   2. the published copy   $BASE/$version/examples.json
#   3. the published manifest's declared sha256 for examples.json
#
# --family selects all three of: which repo copy is authoritative, which base
# URL env var is read, and which published prefix is the default. It is the
# SAME byte-identity contract either way — the trade family ships its own
# examples.json into the same demo workspace, so it drifts the same way and is
# guarded the same way. Default: nyc (mobility), the family this script was
# written for.
#
# Network by nature, so deliberately NOT part of `./gradlew build`: it is a
# release-rehearsal step (docs/deployment.md) — run it for the version the demo pins
# before every publish confirmation and every release rehearsal. Against an unpublished
# version it fails on the manifest fetch, which is exactly the §B upload gate's curl.
#
# The DATA artifacts are out of scope here on purpose: their byte-identity to the last
# published version is the rebuild's own claim (README "Changing the data"), re-proven
# by verify.sh from the artifacts themselves. examples.json is the one artifact whose
# drift has ever broken the demo (T70), and the only one the repo authors by hand.

set -euo pipefail
SD_SCRIPT=check-published
SD_ROOT="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SD_ROOT/../.." && pwd)"
source "$SD_ROOT/lib/common.sh"

# 075/T117: the family's base URL and its pinned VERSION come from the TRACKED
# deploy/env/demo.env — the same file the running deployment loads. They used to live
# in a git-ignored scaffold, so the repo could not say which version the demo pinned
# and two stacks in one week loaded a version the repo had moved past. `<version>` is
# now OPTIONAL: with no argument this checks the version the repo actually ships.
DEMO_ENV="$REPO_ROOT/deploy/env/demo.env"
demo_env_get() { # key
  [ -f "$DEMO_ENV" ] || return 0
  grep -E "^$1=" "$DEMO_ENV" | head -1 | cut -d= -f2- || true
}

FAMILY=nyc
VERSION=""
while [ $# -gt 0 ]; do
  case "$1" in
    --family) FAMILY="${2:-}"; shift 2 ;;
    --family=*) FAMILY="${1#--family=}"; shift ;;
    -*) die "unknown option '$1' — usage: $0 [--family nyc|trade] <version>" ;;
    *) [ -z "$VERSION" ] || die "two versions given ('$VERSION' and '$1') — usage: $0 [--family nyc|trade] <version>"
       VERSION="$1"; shift ;;
  esac
done

# Each family has its OWN content directory and its OWN base-URL variable — the
# same two names the demo profile and app.sh already use, so a local-serve
# rehearsal sets the variable it was already setting.
case "$FAMILY" in
  nyc)
    REPO_COPY="$REPO_ROOT/scripts/sample-data/content/examples.json"
    BASE="${SAMPLE_BASE_URL:-$(demo_env_get SAMPLE_BASE_URL)}"
    [ -n "$VERSION" ] || VERSION="$(demo_env_get SAMPLE_VERSION)" ;;
  trade)
    REPO_COPY="$REPO_ROOT/scripts/sample-data-trade/content/examples.json"
    BASE="${SAMPLE_TRADE_BASE_URL:-$(demo_env_get SAMPLE_TRADE_BASE_URL)}"
    [ -n "$VERSION" ] || VERSION="$(demo_env_get SAMPLE_TRADE_VERSION)" ;;
  *) die "unknown family '$FAMILY' — the sample-data families are nyc (mobility) and trade" ;;
esac
[ -n "$BASE" ] || die "no base URL for family '$FAMILY': set SAMPLE_BASE_URL / SAMPLE_TRADE_BASE_URL, or fix $DEMO_ENV"
[ -n "$VERSION" ] || die "no version for family '$FAMILY': pass one, or fix SAMPLE_VERSION / SAMPLE_TRADE_VERSION in $DEMO_ENV"

[ -f "$REPO_COPY" ] || die "repo copy '$REPO_COPY' does not exist"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

step "$FAMILY: fetching $BASE/$VERSION/manifest.json"
fetch "$BASE/$VERSION/manifest.json" "$WORK/manifest.json"

MANIFEST_VERSION=$(python3 -c "
import json; print(json.load(open('$WORK/manifest.json')).get('version',''))")
if [ "$MANIFEST_VERSION" != "$VERSION" ]; then
  die "manifest at $BASE/$VERSION/ declares version '$MANIFEST_VERSION', not '$VERSION' — the published directory and its manifest disagree about what this is"
fi

DECLARED=$(python3 -c "
import json
m = json.load(open('$WORK/manifest.json'))
e = [a for a in m['artifacts'] if a['file'] == 'examples.json']
print(e[0]['sha256'] if e else '')")
[ -n "$DECLARED" ] || die "the published manifest lists no examples.json artifact"

step "fetching $BASE/$VERSION/examples.json"
fetch "$BASE/$VERSION/examples.json" "$WORK/examples.json"

REPO_SHA=$(sha256_of "$REPO_COPY")
PUBLISHED_SHA=$(sha256_of "$WORK/examples.json")

if [ "$REPO_SHA" != "$PUBLISHED_SHA" ]; then
  die "PUBLISHED DRIFT (the T70 defect class): the published $VERSION examples.json
  published sha256 : $PUBLISHED_SHA
  repo sha256      : $REPO_SHA
  The repo copy and the published artifact disagree. Either the artifact was built from
  older content (republish per scripts/sample-data/README.md), or the repo copy moved
  after the last publish (rebuild + republish a NEW version directory)."
fi

if [ "$DECLARED" != "$REPO_SHA" ]; then
  die "PUBLISHED DRIFT: the published manifest declares examples.json sha256
  declared : $DECLARED
  actual   : $REPO_SHA
  The published manifest does not describe the published bytes — the artifact set is
  internally inconsistent. Re-publish the whole version directory per the README."
fi

log "ok — published $FAMILY $VERSION examples.json == repo copy (sha256=${REPO_SHA:0:16}…, manifest agrees)"
