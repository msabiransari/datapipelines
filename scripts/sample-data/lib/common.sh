#!/usr/bin/env bash
# scripts/sample-data/lib/common.sh — shared machinery for the sample-data build
# pipeline (sample-data design §4.1). SOURCED, not executed, by download.sh /
# transform.sh / load-and-dump.sh / manifest.sh / verify.sh / pin.sh after they
# set $SD_ROOT (this directory) and $REPO_ROOT.
#
#   source "$SD_ROOT/lib/common.sh"
#
# Three jobs, all of them "make the build refuse rather than drift":
#   1. sources.lock parsing + SHA-256 verification (D8: a silently re-published
#      upstream file must fail LOUDLY, not change the artifact).
#   2. The pinned DuckDB CLI install, following the scanner-binary pattern of
#      scripts/lib/scan-tools.sh (009/F1): exact version + SHA-256 into
#      .tools/, verified before every use, per-platform.
#   3. Pinned-image docker helpers for the throwaway build engines. Every
#      `docker compose`/`docker run` here is project-scoped to $SD_PROJECT so a
#      build can never replace a service of the owner's live `deploy` stack
#      (MISTAKES.md, 2026-08-28 — the incident that added `name:` to both
#      committed compose files).
#
# NOTE ON REUSE: scripts/lib/scan-tools.sh is deliberately NOT sourced here.
# Its download helper is a good fit, but its exit-code contract (200 = offline
# fail-soft, 2 = broken) belongs to the SCANNERS, whose callers (gate.sh,
# pre-commit) interpret those numbers. A build script that silently "skips
# offline" would produce a partial artifact set, so this pipeline has the
# opposite policy: no network, no build. Copying the ~15 lines of curl
# diagnostics is cheaper than coupling two contracts that must not agree.

set -euo pipefail

# --- logging ---------------------------------------------------------------

# All progress goes to stderr so a script's stdout stays usable as data
# (manifest.sh writes JSON to stdout; verify.sh writes its report there).
log()  { printf '%s: %s\n' "${SD_SCRIPT:-sample-data}" "$*" >&2; }
die()  { printf '%s: FAIL — %s\n' "${SD_SCRIPT:-sample-data}" "$*" >&2; exit 1; }
step() { printf '\n%s: ==> %s\n' "${SD_SCRIPT:-sample-data}" "$*" >&2; }

# --- checksums -------------------------------------------------------------

# sha256_of <file> — the hash alone, no filename. shasum(1) is used rather than
# sha256sum(1) because it is the one spelling present on both macOS and the
# Linux images this build runs in; scripts/lib/scan-tools.sh made the same call.
sha256_of() { shasum -a 256 "$1" | awk '{print $1}'; }

# window_day_end <YYYY-MM> — the last day of that month, ISO (2026-02-28).
# `window_end` in sources.lock names a MONTH; the inclusive DATE bound of the
# window is the last day of that month. COMPUTED, never hand-written: a
# hand-written end date is how the queries and the manifest end up describing
# a window the data does not have. One derivation, shared by transform.sh
# (the window filters) and manifest.sh (the provenance text) — two
# computations of the same fact drift (045 §B). python3, like every other
# date computation in this pipeline: BSD and GNU date disagree on the
# arithmetic flags.
window_day_end() {
  python3 - "$1" <<'PY'
import datetime, sys
y, m = (int(x) for x in sys.argv[1].split("-"))
ny, nm = (y + 1, 1) if m == 12 else (y, m + 1)
print((datetime.date(ny, nm, 1) - datetime.timedelta(days=1)).isoformat())
PY
}

# sha256_expect <file> <want> <what>
# Verify and REMOVE the file on mismatch. Removing it is what makes a retry
# meaningful: a half-written download that keeps failing its checksum would
# otherwise be indistinguishable from a re-published upstream file.
sha256_expect() {
  local file="$1" want="$2" what="$3" got
  [ -f "$file" ] || die "$what: expected file '$file' does not exist"
  got=$(sha256_of "$file")
  if [ "$want" != "$got" ]; then
    rm -f "$file"
    die "$what: SHA-256 mismatch for '$file'
  pinned in sources.lock: $want
  actually downloaded:    $got
  The upstream file has changed since it was pinned. This is NOT something to
  work around: re-pinning is a deliberate, reviewed change (scripts/sample-data/pin.sh),
  because it changes the published artifact's contents."
  fi
}

# --- sources.lock ----------------------------------------------------------
#
# Format — three space-separated fields per non-comment line:
#   <kind> <key> <value>
# where <kind> is one of:
#   url    <id> <url>       the pinned source URL for logical source <id>
#   sha256 <id> <hash>      the pinned SHA-256 of that file, or the literal
#                           `unpinned:<reason-token>` for a source whose file
#                           is genuinely not immutable (see NOAA below)
#   window <id> <hash>      CONTENT pin: SHA-256 of the canonical extract this
#                           build takes out of an <id> whose file is unpinned
#   tool   <name>-<plat> <version> <url> <sha256>   a pinned build binary
#   image  <name> <ref>     a pinned container image (tag + @sha256 digest)
#
# A flat, greppable text file rather than JSON/YAML: it is read by shell, it is
# reviewed by humans in a diff, and every pin must be visible on one line.

SOURCES_LOCK="${SOURCES_LOCK:-$SD_ROOT/sources.lock}"

# lock_field <kind> <id> [column] — value of a lock line, or empty.
lock_field() {
  local kind="$1" id="$2" col="${3:-3}"
  awk -v k="$kind" -v i="$id" -v c="$col" '
    /^[[:space:]]*#/ { next }
    NF == 0          { next }
    $1 == k && $2 == i { print $c; found = 1; exit }
    END { if (!found) exit 0 }
  ' "$SOURCES_LOCK"
}

# lock_ids <kind> — every id declared for a kind, in file order.
lock_ids() {
  awk -v k="$1" '/^[[:space:]]*#/ { next } NF == 0 { next } $1 == k { print $2 }' "$SOURCES_LOCK"
}

# --- downloading -----------------------------------------------------------

# fetch <url> <dest> — curl wrapper whose failure diagnostic NAMES the url.
# A bare `curl -sfL` under `set -euo pipefail` dies with no message at all,
# which in a 24-file loop is indistinguishable from a hang.
fetch() {
  local url="$1" dest="$2"
  mkdir -p "$(dirname "$dest")"
  if ! curl -sfL --retry 3 --retry-delay 2 --max-time "${SD_FETCH_TIMEOUT:-900}" "$url" -o "$dest.part"; then
    rm -f "$dest.part"
    die "could not download:
  $url
  (network unreachable, or the upstream path no longer exists — sources.lock pins exact URLs, so a 404 here means upstream moved the file and the pin needs a reviewed update)"
  fi
  mv "$dest.part" "$dest"
}

# --- the pinned DuckDB CLI -------------------------------------------------
#
# Build-time ETL only. DuckDB is NOT a runtime dialect (design D2); it is here
# because it reads TLC's Parquet natively and emits CSV, which is the whole of
# stage 2. Pinned exactly like the scanner binaries: version + per-platform
# SHA-256 in sources.lock, verified before every use, installed under
# $REPO_ROOT/.tools/ (git-ignored, OUTSIDE build/ so `gradlew clean` does not
# force a re-download — the reason scan-tools.sh chose that location).

sd_platform() {
  local os arch
  os=$(uname -s | tr '[:upper:]' '[:lower:]')
  arch=$(uname -m)
  case "$os:$arch" in
    darwin:arm64|darwin:x86_64) echo "osx-universal" ;;
    linux:x86_64)               echo "linux-amd64" ;;
    linux:aarch64|linux:arm64)  echo "linux-arm64" ;;
    *) die "unsupported platform '$os/$arch' — sources.lock pins duckdb for osx-universal, linux-amd64 and linux-arm64 only" ;;
  esac
}

# duckdb_bin — path to the verified, executable pinned CLI; installs on first use.
duckdb_bin() {
  local plat version url want dir bin zip
  plat=$(sd_platform)
  version=$(lock_field tool "duckdb-$plat" 3)
  url=$(lock_field tool "duckdb-$plat" 4)
  want=$(lock_field tool "duckdb-$plat" 5)
  [ -n "$version" ] && [ -n "$url" ] && [ -n "$want" ] \
    || die "sources.lock has no complete 'tool duckdb-$plat' pin"
  dir="$REPO_ROOT/.tools/duckdb"
  bin="$dir/duckdb-$version-$plat"
  if [ ! -x "$bin" ]; then
    log "installing DuckDB CLI $version ($plat)"
    mkdir -p "$dir"
    zip="$dir/duckdb-$version-$plat.zip"
    fetch "$url" "$zip"
    sha256_expect "$zip" "$want" "duckdb $version ($plat)"
    # -o: overwrite a partial extraction from an interrupted run. -j: the
    # archive carries a bare `duckdb`; junking paths keeps the layout flat.
    unzip -o -j -q "$zip" duckdb -d "$dir"
    mv "$dir/duckdb" "$bin"
    chmod +x "$bin"
    rm -f "$zip"
  fi
  echo "$bin"
}

# --- pinned build engines (docker) -----------------------------------------
#
# Project scoping is not optional. `docker run --name postgres` on this box
# would collide with, and `docker compose` without -p could REPLACE, the
# owner's live `deploy` stack (MISTAKES.md 2026-08-28). Every container this
# pipeline creates is named with the $SD_PROJECT prefix and removed on exit.
SD_PROJECT="${SD_PROJECT:-dp-sampledata-build}"

# image_ref <name> — the pinned `repo:tag@sha256:...` reference from sources.lock.
image_ref() {
  local ref
  ref=$(lock_field image "$1" 3)
  [ -n "$ref" ] || die "sources.lock has no 'image $1' pin"
  echo "$ref"
}

# require_cmd <cmd> <hint>
require_cmd() { command -v "$1" >/dev/null 2>&1 || die "'$1' is required — $2"; }
