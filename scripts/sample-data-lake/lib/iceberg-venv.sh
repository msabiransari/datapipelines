#!/usr/bin/env bash
# shellcheck source-path=SCRIPTDIR
# scripts/sample-data-lake/lib/iceberg-venv.sh — the pinned pyiceberg environment,
# installed and used exactly the way lib/common.sh installs the pinned DuckDB CLI:
# a fixed version, into $REPO_ROOT/.tools/ (git-ignored, OUTSIDE build/ so
# `gradlew clean` does not force a reinstall), created on first use and reused after.
#
#   ./scripts/sample-data-lake/lib/iceberg-venv.sh run <script.py> [args...]
#   ./scripts/sample-data-lake/lib/iceberg-venv.sh python                 # the interpreter path
#
# WHY A SECOND TOOLCHAIN AT ALL. DuckDB's iceberg extension can READ an Iceberg
# table from a path, but it can only WRITE through an attached Iceberg REST catalog
# — verified 2026-09-07 against the pinned v1.5.5 (`ATTACH '<dir>' (TYPE iceberg)`
# fails with "AUTHORIZATION_TYPE is 'oauth2', yet no 'secret' was provided") and
# against the extension's own documentation, which states: "Catalog-managed tables
# are accessed by attaching an Iceberg REST catalog. This unlocks the full feature
# set, including writing."
# (https://duckdb.org/docs/current/core_extensions/iceberg/overview.html, read
# 2026-09-07.) A published S3 prefix has no catalog service, so DuckDB cannot be the
# writer. pyiceberg can, with a throwaway local catalog whose state is discarded —
# see iceberg_write.py.

set -euo pipefail
SD_SCRIPT=iceberg-venv
LIB_DIR="$(cd "$(dirname "$0")" && pwd)"
SD_ROOT="$(cd "$LIB_DIR/.." && pwd)"
REPO_ROOT="$(cd "$SD_ROOT/../.." && pwd)"
# shellcheck source=../../sample-data/lib/common.sh
source "$REPO_ROOT/scripts/sample-data/lib/common.sh"

VERSION=$(lock_field param pyiceberg_version)
[ -n "$VERSION" ] || die "sources.lock has no 'param pyiceberg_version'"
REQ="$SD_ROOT/requirements-pyiceberg.txt"
[ -f "$REQ" ] || die "$REQ is missing — it is the lock for this environment"

VENV="$REPO_ROOT/.tools/pyiceberg-$VERSION"
PYTHON="$VENV/bin/python"
STAMP="$VENV/.installed-from"

ensure_venv() {
  # The stamp records WHICH lock file content the venv was built from, so an edited
  # requirements file rebuilds instead of being silently ignored — the failure mode
  # a bare `[ -d "$VENV" ]` check has.
  local want
  want=$(sha256_of "$REQ")
  if [ -x "$PYTHON" ] && [ -f "$STAMP" ] && [ "$(cat "$STAMP")" = "$want" ]; then
    return 0
  fi
  require_cmd python3 "the Iceberg writer runs on pinned pyiceberg"
  log "creating the pinned pyiceberg $VERSION environment in $VENV"
  rm -rf "$VENV"
  python3 -m venv "$VENV"
  "$VENV/bin/pip" install --quiet --upgrade pip
  "$VENV/bin/pip" install --quiet --no-deps -r "$REQ"
  # --no-deps + a complete lock: pip installs exactly the 28 pinned lines and
  # resolves nothing, so a transitive release published this morning cannot enter a
  # build whose lock has not changed. `pip check` then proves the closed set is
  # actually consistent — without it, --no-deps would happily leave a hole.
  "$VENV/bin/pip" check >/dev/null \
    || die "the pinned environment in $REQ is not self-consistent (pip check failed) — regenerate it"
  local got
  got=$("$PYTHON" -c "import pyiceberg; print(pyiceberg.__version__)")
  [ "$got" = "$VERSION" ] \
    || die "installed pyiceberg $got but sources.lock pins $VERSION — requirements-pyiceberg.txt and the param disagree"
  printf '%s' "$want" > "$STAMP"
}

case "${1:-}" in
  run)
    shift
    [ $# -ge 1 ] || die "usage: $0 run <script.py> [args...]"
    ensure_venv
    exec "$PYTHON" "$@"
    ;;
  python)
    ensure_venv
    echo "$PYTHON"
    ;;
  *)
    die "usage: $0 run <script.py> [args...] | $0 python"
    ;;
esac
