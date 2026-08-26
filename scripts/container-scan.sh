#!/usr/bin/env bash
# container-scan.sh — Trivy scan of the container surface (DEVELOPMENT.md §10.2).
#
#   ./scripts/container-scan.sh           # config scan + image scan (builds the image)
#   ./scripts/container-scan.sh --config-only
#
# Tool: trivy, PINNED. Version verified 2026-08-15 against the GitHub releases
# API (aquasecurity/trivy, latest release). Install method: release tarball
# downloaded into .tools/ (git-ignored, OUTSIDE build/ so `gradlew clean` does
# not force a re-download), SHA256-checked against the release's own checksums
# file, reused on later runs. Bump by editing TRIVY_VERSION after verifying the
# new release the same way.
#
# Two scans:
#   1. CONFIG — Dockerfile misconfigurations. Exit 1 on any finding not
#      allowlisted in .trivyignore. NOTE: trivy 0.74 has no docker-compose
#      scanner (deploy/ yields "0 config files"), so compose files are not
#      covered — recorded in the 006 handback.
#   2. IMAGE — `docker build` the production image (needs only the bootJar,
#      built locally — no secrets/infra) and scan its packages. Known baseline
#      CVEs live in .trivyignore, each with a reason + date comment; anything
#      NOT ignored exits 1. We do not chase base-image CVE zero: a baseline
#      entry is added only with its triage reason.
#
# Needs network on first run (trivy downloads its vulnerability DB to
# ~/.cache/trivy) and a running Docker daemon for the image scan.
#
# Exit codes: trivy's --exit-code 1 = findings not allowlisted — trivy's
# verdict exits are CAPTURED and re-exited deliberately (014/F1: a bare
# invocation under set -e fired the ERR trap on a real finding and replaced
# exit 1 with "tooling failure" exit 2);
# tooling/environment failures exit 2 (013/F1: unsupported OS/architecture,
# or the sourced scan-tools.sh helpers' download/verify failures — with
# errtrace set, also any unhandled failure inside install_trivy, 014/F2) —
# distinct from 1 so an install-side breakage never reads as a scan finding.

set -Eeuo pipefail
# -E/errtrace (014/F2): without it this trap is NOT inherited by shell
# functions, so install_trivy's unguarded mkdir/tar/mv/chmod died as a raw
# exit 1 — which in this contract means "trivy finding" (014/F2 proof:
# read-only .tools/ → "mkdir: Permission denied", exit 1).
# Any UNHANDLED tooling failure (docker build, bootJar, tar, …) exits 2,
# never a raw set -e death that would read as a trivy finding (013/F1).
# Handled failures (the docker-info check, the captured trivy verdicts)
# never fire this.
trap 'echo "container-scan: unexpected tooling failure at line $LINENO — no verdict" >&2; exit 2' ERR
cd "$(dirname "$0")/.."
ROOT="$PWD"
source "$ROOT/scripts/lib/scan-tools.sh"

TRIVY_VERSION="0.74.0"       # verified latest release, 2026-08-15
TOOL_DIR="$(scan_tools_dir trivy)"

os=$(uname -s)                    # Darwin | Linux
# trap - ERR inside the substitution (018/F3): errtrace makes the
# command-substitution subshell inherit this script's ERR trap, so
# scan_tools_arch's HANDLED `return 1` (unsupported arch) fires it and prints
# the spurious "unexpected tooling failure" line on exactly the path this
# guard exists to name. NB `set +E` there is NOT enough — the trap is
# inherited at fork time and stays SET in the subshell (proven: still fires;
# the parent's -E only governs further inheritance). Removing it in the
# subshell silences the line; the parent's trap is untouched; EXIT stays 2.
arch=$(trap - ERR; scan_tools_arch trivy) || { echo "container-scan: unsupported architecture $(uname -m)" >&2; exit 2; }
# trivy's asset names conflate OS and arch (macOS-ARM64, Linux-64bit, …): the
# ARCH token comes from scan_tools_arch (one map for all scanners, 012/F10);
# only the OS token is mapped here.
case "$os" in
  Darwin) os_token="macOS" ;;
  Linux)  os_token="Linux" ;;
  *) echo "container-scan: unsupported OS $os" >&2; exit 2 ;;
esac
asset="trivy_${TRIVY_VERSION}_${os_token}-${arch}.tar.gz"
BIN="$TOOL_DIR/trivy-${TRIVY_VERSION}-${os}-$(uname -m)"

install_trivy() {
  mkdir -p "$TOOL_DIR"
  local base="https://github.com/aquasecurity/trivy/releases/download/v${TRIVY_VERSION}"
  echo "container-scan: installing trivy ${TRIVY_VERSION} (${asset})"
  scan_tools_download container-scan "$base/$asset" "$TOOL_DIR/$asset"
  scan_tools_download container-scan "$base/trivy_${TRIVY_VERSION}_checksums.txt" "$TOOL_DIR/checksums.txt"
  scan_tools_verify_sha256 container-scan "$TOOL_DIR/$asset" "$TOOL_DIR/checksums.txt" "$asset"
  tar -xzf "$TOOL_DIR/$asset" -C "$TOOL_DIR" trivy
  mv "$TOOL_DIR/trivy" "$BIN"
  chmod +x "$BIN"
  rm -f "$TOOL_DIR/$asset"
  scan_tools_prune container-scan trivy "$BIN"
}

[ -x "$BIN" ] || install_trivy

IGNORE_ARGS=()
[ -f "$ROOT/.trivyignore" ] && IGNORE_ARGS=(--ignorefile "$ROOT/.trivyignore")

# trivy_verdict <stage-label> <captured-rc> — map trivy's CAPTURED exit to
# the published contract (014/F1): 0 = clean, 1 = findings not allowlisted
# (trivy's own output above is the finding report). Anything else is a
# trivy error, not a verdict → exit 2. Bare invocations under set -e used
# to fire the ERR trap on a REAL finding and replace its exit 1 with
# "tooling failure" (exit 2) — the same commit that introduced the trap
# inverted the contract it documented.
# KNOWN LIMIT (verified against trivy 0.74.0, 2026-08-18): trivy exits 1
# for FATAL errors too, so a FATAL lands in the 1 branch — loud (its FATAL
# line prints above) but misclassified; exit 2 can only catch codes ≠ 0/1.
trivy_verdict() {
  case "$2" in
    0) return 0 ;;
    1) echo "container-scan: $1 — findings not allowlisted (report above)." >&2; exit 1 ;;
    *) echo "container-scan: $1 — trivy exited $2 without a verdict (error, not findings)." >&2; exit 2 ;;
  esac
}

echo "== trivy $TRIVY_VERSION — config scan (Dockerfile, deploy/) =="
# Verdicts are CAPTURED and re-exited deliberately (014/F1) — never bare
# under set -e. Second scan only runs while the first is clean (the
# pre-013 set -e semantics), so a finding names its stage.
scan_rc=0
"$BIN" config --exit-code 1 ${IGNORE_ARGS[@]+"${IGNORE_ARGS[@]}"} "$ROOT/Dockerfile" || scan_rc=$?
if [ "$scan_rc" -eq 0 ]; then
  "$BIN" config --exit-code 1 ${IGNORE_ARGS[@]+"${IGNORE_ARGS[@]}"} "$ROOT/deploy" || scan_rc=$?
fi
trivy_verdict "config scan" "$scan_rc"

if [ "${1:-}" = "--config-only" ]; then
  echo "container-scan: --config-only given; image scan skipped."
  exit 0
fi

if ! docker info > /dev/null 2>&1; then
  echo "container-scan: WARNING — Docker daemon unavailable; image scan SKIPPED." >&2
  exit 0
fi

echo "== building production image (bootJar + docker build) =="
./gradlew -q :modules:app:bootJar > /dev/null
docker build -q -t datapipelines:trivy-scan "$ROOT" > /dev/null

echo "== trivy $TRIVY_VERSION — image scan (datapipelines:trivy-scan) =="
scan_rc=0
"$BIN" image --exit-code 1 ${IGNORE_ARGS[@]+"${IGNORE_ARGS[@]}"} datapipelines:trivy-scan || scan_rc=$?
trivy_verdict "image scan" "$scan_rc"
