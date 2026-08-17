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

# vuln-scan exit contract, defined ONCE so the producer (vuln-scan.sh) and the
# consumer (gate.sh) can never drift apart (012/F1). osv-scanner's own exit
# codes (v2.5.0, cmd/osv-scanner/internal/cmd/run.go) are 0/1/127/128/129/130
# and may change with any bump — the sentinel therefore lives OUTSIDE both
# that set and the shell's reserved band (126 perm-denied, 127 not-found,
# 128+N signal deaths). vuln-scan.sh NEVER propagates a scanner exit raw.
# Full contract: 0 = clean · 1 = findings · 2 = scan error / broken
# environment (fails the gate, cause named) · 200 = skipped offline (fail-soft).
SCAN_EXIT_OFFLINE=200

# scan_tools_dir <name> — where scanner <name> (gitleaks|osv-scanner|trivy) lives.
scan_tools_dir() {
  echo "$ROOT/.tools/$1"
}

# scan_tools_arch <style> — map `uname -m` to the arch token a tool's release
# assets use. Styles: "amd64" (osv-scanner), "x64" (gitleaks names its amd64
# assets x64), "trivy" (trivy calls them 64bit / ARM64). trivy's asset names
# conflate OS and arch (macOS-ARM64, Linux-64bit, …), so container-scan.sh
# still maps the OS token itself — but the ARCH mapping lives HERE, once
# (012/F10: container-scan.sh carried a private arch case before). linux
# reports aarch64 where assets say arm64 — without this map the download 404s
# under `curl -f`.
scan_tools_arch() {
  local style="$1" raw
  raw=$(uname -m)
  case "$style:$raw" in
    amd64:x86_64)              echo amd64 ;;
    amd64:aarch64|amd64:arm64) echo arm64 ;;
    x64:x86_64)                echo x64 ;;
    x64:aarch64|x64:arm64)     echo arm64 ;;
    trivy:x86_64)              echo 64bit ;;
    trivy:aarch64|trivy:arm64) echo ARM64 ;;
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

# scan_tools_prune <script-name> <tool> <keep-binary>
# Remove superseded version-suffixed binaries of <tool> (files named
# "<tool>-<version>-…" under .tools/) except <keep-binary>. Called by each
# installer AFTER a successful verify of a new version: without it every
# version bump left the previous ~50-100MB binary in .tools/ forever (009
# review cut list). Prunes only on install — a failed download/verify never
# deletes the working binary it would fall back to.
scan_tools_prune() {
  local script="$1" tool="$2" keep="$3" removed=0 f
  for f in "$(scan_tools_dir "$tool")"/"$tool"-*; do
    [ -e "$f" ] || continue
    [ "$f" = "$keep" ] && continue
    rm -f "$f"
    removed=$((removed + 1))
  done
  if [ "$removed" -gt 0 ]; then
    echo "$script: pruned $removed superseded $tool binary(ies) from $(scan_tools_dir "$tool")"
  fi
  return 0
}

# scan_tools_verify_sha256 <script-name> <file> <sums-file> <asset-name>
# Verify <file> against the release's own checksum manifest. On mismatch (or
# no manifest entry) delete the file and exit 1: refuse to run an unverified
# binary.
scan_tools_verify_sha256() {
  local script="$1" file="$2" sums="$3" asset="$4"
  local want got
  # Fixed-string field match over BOTH manifest formats (012/F9): text mode
  # "hash  asset" ($2 == asset) and binary mode "hash *asset" ($2 == "*asset").
  # Field comparison, not a `grep "  $asset$"` regex: the old form broke on a
  # single-space separator or an asterisk (yielding want=none and DELETING a
  # valid download on the next version bump), and interpolated asset names
  # could act as BRE patterns.
  want=$(awk -v a="$asset" '$2 == a || $2 == "*" a {print $1}' "$sums" 2>/dev/null || true)
  got=$(shasum -a 256 "$file" | awk '{print $1}')
  if [ -z "$want" ]; then
    echo "$script: asset '$asset' not found in manifest $sums — refusing to run an unverified binary." >&2
    rm -f "$file"
    exit 1
  fi
  if [ "$want" != "$got" ]; then
    echo "$script: SHA256 mismatch for $asset (want=$want got=$got)" >&2
    rm -f "$file"
    exit 1
  fi
}
