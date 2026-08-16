#!/usr/bin/env bash
# scripts/lib/scan-tools.sh — shared install machinery for the pinned scanner
# binaries (gitleaks, osv-scanner, trivy). SOURCED, not executed, by
# vuln-scan.sh / secret-scan.sh / container-scan.sh after they set $ROOT:
#
#   source "$ROOT/scripts/lib/scan-tools.sh"
#
# Install root: $ROOT/.tools/ (git-ignored) — deliberately OUTSIDE build/.
# The 006 layout installed into build/tools/, which `gradlew clean` (gate.sh
# cycle 1) deletes: every gate run re-downloaded ~50MB, and after any clean an
# offline developer could not COMMIT — the pre-commit hook execs
# secret-scan.sh, whose curl install died under `set -e` with a near-silent
# diagnostic. Binaries now survive clean; install failures name the URL.

# scan_tools_dir <name> — where scanner <name> (gitleaks|osv-scanner|trivy) lives.
scan_tools_dir() {
  echo "$ROOT/.tools/$1"
}

# scan_tools_arch <style> — map `uname -m` to the arch token a tool's release
# assets use. Styles: "amd64" (osv-scanner), "x64" (gitleaks names its amd64
# assets x64). trivy's asset names conflate OS and arch, so container-scan.sh
# keeps its own case. linux reports aarch64 where assets say arm64 — without
# this map the download 404s under `curl -f` (same-run inconsistency:
# container-scan.sh already mapped it, the other two did not).
scan_tools_arch() {
  local style="$1" raw
  raw=$(uname -m)
  case "$style:$raw" in
    amd64:x86_64)              echo amd64 ;;
    amd64:aarch64|amd64:arm64) echo arm64 ;;
    x64:x86_64)                echo x64 ;;
    x64:aarch64|x64:arm64)     echo arm64 ;;
    *) return 1 ;;
  esac
}

# scan_tools_download <script-name> <url> <dest>
# curl wrapper whose failure diagnostic NAMES the URL — a bare `curl -sfL`
# under `set -euo pipefail` dies with no message, which inside the pre-commit
# hook reads as a silent commit failure and trains people toward --no-verify.
scan_tools_download() {
  local script="$1" url="$2" dest="$3"
  if ! curl -sfL "$url" -o "$dest"; then
    echo "$script: FAILED to download:" >&2
    echo "  $url" >&2
    echo "  (network unreachable, or no release asset for this platform — see scan_tools_arch)" >&2
    exit 1
  fi
}

# scan_tools_verify_sha256 <script-name> <file> <sums-file> <asset-name>
# Verify <file> against the release's own checksum manifest. On mismatch (or
# no manifest entry) delete the file and exit 1: refuse to run an unverified
# binary.
scan_tools_verify_sha256() {
  local script="$1" file="$2" sums="$3" asset="$4"
  local want got
  want=$(grep "  $asset\$" "$sums" 2>/dev/null | awk '{print $1}' || true)
  got=$(shasum -a 256 "$file" | awk '{print $1}')
  if [ -z "$want" ] || [ "$want" != "$got" ]; then
    echo "$script: SHA256 mismatch for $asset (want=${want:-none} got=$got)" >&2
    rm -f "$file"
    exit 1
  fi
}
