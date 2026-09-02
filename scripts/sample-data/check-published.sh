#!/usr/bin/env bash
# scripts/sample-data/check-published.sh — the drift guard the 049 round adds (C2): the
# PUBLISHED sample-data artifact cannot silently disagree with the repo's
# content/examples.json — the defect class of T70, where the published v1 still carried
# the `${}` interpolations 042 had already migrated out of the repo copy, and the demo
# 500ed on first login for two days because nothing compared the two.
#
#   ./scripts/sample-data/check-published.sh <version>
#   SAMPLE_BASE_URL=http://host.docker.internal:8099 ./scripts/sample-data/check-published.sh v2
#   SAMPLE_BASE_URL=file://$PWD/scripts/sample-data/work/artifacts-parent ...
#
# Compares THREE hashes and fails if any pair disagrees, naming which:
#   1. the repo copy        scripts/sample-data/content/examples.json
#   2. the published copy   $SAMPLE_BASE_URL/$version/examples.json
#   3. the published manifest's declared sha256 for examples.json
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

VERSION="${1:-}"
[ -n "$VERSION" ] || die "usage: $0 <version>   (e.g. v2) — the version directory under \$SAMPLE_BASE_URL"
BASE="${SAMPLE_BASE_URL:-https://datapipelines-co.s3.amazonaws.com/sample-data/mobility}"
REPO_COPY="$SD_ROOT/content/examples.json"
[ -f "$REPO_COPY" ] || die "repo copy '$REPO_COPY' does not exist"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

step "fetching $BASE/$VERSION/manifest.json"
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

log "ok — published $VERSION examples.json == repo copy (sha256=${REPO_SHA:0:16}…, manifest agrees)"
