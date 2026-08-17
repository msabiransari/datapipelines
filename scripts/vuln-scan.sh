#!/usr/bin/env bash
# vuln-scan.sh — dependency vulnerability scan (OSV-Scanner) over the committed
# Gradle lockfiles. See DEVELOPMENT.md §10.2.
#
#   ./scripts/vuln-scan.sh          # scan; see the EXIT CONTRACT below
#                                   # (0 clean / 1 findings / 2 error /
#                                   #  200 skipped-offline)
#
# Tool: osv-scanner, PINNED. Version verified 2026-08-15 against the GitHub
# releases API (google/osv-scanner, latest release). Install method: the
# release binary is downloaded from the release page into .tools/ (git-ignored,
# OUTSIDE build/ so `gradlew clean` does not force a re-download),
# SHA256-checked against the release's own osv-scanner_SHA256SUMS manifest, and
# reused on later runs. Bump by editing OSV_SCANNER_VERSION after verifying the
# new release the same way.
#
# Scope: every committed gradle.lockfile (root, modules, tests, buildSrc). The
# lockfiles ARE the resolved dependency set — scanning them covers direct and
# transitive artifacts exactly. gradle/verification-metadata.xml is derived from
# the same resolutions (same group:name:version set, plus checksums) and has no
# osv-scanner parser, so it adds nothing to scan.
#
# Ignores live in osv-scanner.toml at the repo root; every entry carries a
# reason + date comment (project rule).
#
# OFFLINE BEHAVIOUR: the scan needs network access to osv.dev. When the
# network is GENUINELY unreachable this script WARNS and exits 200
# ($SCAN_EXIT_OFFLINE from scripts/lib/scan-tools.sh, shared with gate.sh —
# never grep a log string: wording drift would convert a skip into an
# affirmative PASS). Fail-soft so an offline laptop is not a broken gate —
# the warning is printed so the skip is never silent. The preflight runs
# BEFORE any install attempt: a missing binary plus no network must still
# skip, not die on the install's curl.
#
# EXIT CONTRACT (012/F1 — the scanner's own exit codes are remapped INSIDE
# this script and never propagated raw; osv-scanner v2.5.0 exits 0/1/127/
# 128/129/130, and any future code in that shape must not silently re-mean
# "offline" the way the old raw exit 3 did):
#   0   scan ran, no findings
#   1   scan ran, vulnerabilities found (osv-scanner's 1)
#   2   scan did NOT run to a verdict: scanner error while online, or the
#       preflight classified the environment as broken (curl missing, TLS/CA
#       failure) — fails the gate loudly with the cause named
#   200 skipped: offline (fail-soft sentinel; connection-level failures only)

set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
source "$ROOT/scripts/lib/scan-tools.sh"

OSV_SCANNER_VERSION="v2.5.0"   # verified latest release, 2026-08-15
TOOL_DIR="$(scan_tools_dir osv-scanner)"

os=$(uname -s | tr '[:upper:]' '[:lower:]')    # darwin | linux
arch=$(scan_tools_arch amd64) || { echo "vuln-scan: unsupported architecture $(uname -m)" >&2; exit 1; }
BIN="$TOOL_DIR/osv-scanner-${OSV_SCANNER_VERSION}-${os}-${arch}"

install_osv_scanner() {
  mkdir -p "$TOOL_DIR"
  local base="https://github.com/google/osv-scanner/releases/download/${OSV_SCANNER_VERSION}"
  local asset="osv-scanner_${os}_${arch}"
  echo "vuln-scan: installing osv-scanner ${OSV_SCANNER_VERSION} (${asset})"
  scan_tools_download vuln-scan "$base/$asset" "$BIN"
  scan_tools_download vuln-scan "$base/osv-scanner_SHA256SUMS" "$TOOL_DIR/SHA256SUMS"
  scan_tools_verify_sha256 vuln-scan "$BIN" "$TOOL_DIR/SHA256SUMS" "$asset"
  chmod +x "$BIN"
  scan_tools_prune vuln-scan osv-scanner "$BIN"
}

# Fail-soft offline preflight: osv-scanner is useless without osv.dev.
# Runs BEFORE the install: offline + no cached binary must skip, not fail.
#
# The preflight CLASSIFIES curl's failure (012/F2) — the old any-nonzero
# branch read every failure as "offline", so a broken CA store (curl 60/77)
# or a missing curl (127) skipped the scan forever with nobody told:
#   * curl 5/6/7/28 — connection-level: couldn't resolve host/proxy,
#     connection refused, timed out → GENUINELY offline → sentinel skip.
#   * curl 0 — online. Any HTTP response counts (the root path answers 404,
#     and a proxy that ANSWERS means TCP+TLS work; if osv.dev is then blocked
#     the scan itself fails loudly as exit 2, not silently skipped).
#   * anything else (127 curl absent, 35/60/77 TLS/CA breakage, …) —
#     environment breakage → FAIL LOUD, naming the cause. Fail-soft is the
#     exception that must be earned; every unrecognised failure is loud.
curl_rc=0
curl -s --max-time 5 -o /dev/null https://api.osv.dev/ || curl_rc=$?
case "$curl_rc" in
  0) : ;;
  5|6|7|28)
    echo "vuln-scan: WARNING — osv.dev unreachable (offline, curl exit $curl_rc); vulnerability scan SKIPPED (fail-soft)." >&2
    exit "$SCAN_EXIT_OFFLINE"
    ;;
  127)
    echo "vuln-scan: FAIL — curl is not installed (exit 127); cannot run the offline preflight at all. Install curl." >&2
    exit 2
    ;;
  *)
    echo "vuln-scan: FAIL — preflight to https://api.osv.dev/ failed with curl exit $curl_rc (TLS/CA/proxy breakage, not a connection-level offline state); scan NOT skipped." >&2
    exit 2
    ;;
esac

[ -x "$BIN" ] || install_osv_scanner

lockfiles=()
while IFS= read -r f; do lockfiles+=("$f"); done < <(git ls-files -- 'gradle.lockfile' '**/gradle.lockfile')
if [ "${#lockfiles[@]}" -eq 0 ]; then
  echo "vuln-scan: no committed gradle.lockfile files found" >&2
  exit 1
fi

args=()
for f in "${lockfiles[@]}"; do args+=("--lockfile=$ROOT/$f"); done

echo "vuln-scan: osv-scanner $OSV_SCANNER_VERSION over ${#lockfiles[@]} lockfile(s)"
# The scanner's exit is CAPTURED and remapped to this script's contract
# (012/F1): the old last-line raw propagation made "our offline sentinel" and
# "the scanner's own error N" the same namespace — an online result-error 3
# read as SKIPPED and the gate passed green. Only 0 and 1 mean the scan
# reached a verdict; everything else fails the gate loudly.
scan_exit=0
"$BIN" scan source --config="$ROOT/osv-scanner.toml" "${args[@]}" || scan_exit=$?
case "$scan_exit" in
  0) exit 0 ;;
  1) exit 1 ;;
  *)
    echo "vuln-scan: FAIL — osv-scanner exited $scan_exit while online (scanner error, not an offline skip; see the output above)." >&2
    exit 2
    ;;
esac
