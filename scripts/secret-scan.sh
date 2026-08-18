#!/usr/bin/env bash
# secret-scan.sh — secret scanning with gitleaks (DEVELOPMENT.md §10.2).
#
#   ./scripts/secret-scan.sh            # full history: gitleaks detect
#   ./scripts/secret-scan.sh --staged   # staged changes only: gitleaks protect --staged
#                                       # (this is what the pre-commit hook runs)
#
# Tool: gitleaks, PINNED. Version verified 2026-08-15 against the GitHub
# releases API (gitleaks/gitleaks, latest release). Install method: the release
# tarball is downloaded from the release page into .tools/ (git-ignored,
# OUTSIDE build/ so `gradlew clean` does not force a re-download — and does not
# break offline commits: the pre-commit hook execs this script),
# SHA256-checked against the release's own checksums file, and reused on later
# runs. Bump by editing GITLEAKS_VERSION after verifying the new release the
# same way.
#
# Allowlist: .gitleaks.toml at the repo root; every entry carries a reason +
# date comment (project rule).
#
# Exit code is gitleaks': 0 = clean, 1 = leaks found. Tooling/environment
# failures exit 2 (013/F1: unsupported architecture, or the sourced
# scan-tools.sh helpers' download/verify failures) — distinct from 1 so an
# install-side breakage never reads as "leaks found". The pre-commit hook
# blocks on ANY non-zero, so its behavior is unchanged.

set -Eeuo pipefail
# -E/errtrace (014/F2): the ERR trap must be inherited by shell functions —
# without it install_gitleaks' unguarded mkdir/tar/mv/chmod died as a raw
# exit 1 (which here means "leaks found"; 014/F2 proof: read-only .tools/
# → "mkdir: Permission denied", exit 1), and the pre-commit hook reported a
# secret leak that did not exist.
# Any UNHANDLED tooling failure exits 2, never a raw set -e death that would
# read as "leaks found" (013/F1). gitleaks itself runs via exec, replacing
# this process — its own exit codes are untouched by the trap.
trap 'echo "secret-scan: unexpected tooling failure at line $LINENO — no verdict" >&2; exit 2' ERR
cd "$(dirname "$0")/.."
ROOT="$PWD"
source "$ROOT/scripts/lib/scan-tools.sh"

GITLEAKS_VERSION="8.30.1"    # verified latest release, 2026-08-15
TOOL_DIR="$(scan_tools_dir gitleaks)"

os=$(uname -s | tr '[:upper:]' '[:lower:]')    # darwin | linux
arch=$(scan_tools_arch x64) || { echo "secret-scan: unsupported architecture $(uname -m)" >&2; exit 2; }
BIN="$TOOL_DIR/gitleaks-${GITLEAKS_VERSION}-${os}-${arch}"

install_gitleaks() {
  mkdir -p "$TOOL_DIR"
  local base="https://github.com/gitleaks/gitleaks/releases/download/v${GITLEAKS_VERSION}"
  local asset="gitleaks_${GITLEAKS_VERSION}_${os}_${arch}.tar.gz"
  echo "secret-scan: installing gitleaks ${GITLEAKS_VERSION} (${asset})"
  scan_tools_download secret-scan "$base/$asset" "$TOOL_DIR/$asset"
  scan_tools_download secret-scan "$base/gitleaks_${GITLEAKS_VERSION}_checksums.txt" "$TOOL_DIR/checksums.txt"
  scan_tools_verify_sha256 secret-scan "$TOOL_DIR/$asset" "$TOOL_DIR/checksums.txt" "$asset"
  tar -xzf "$TOOL_DIR/$asset" -C "$TOOL_DIR" gitleaks
  mv "$TOOL_DIR/gitleaks" "$BIN"
  chmod +x "$BIN"
  rm -f "$TOOL_DIR/$asset"
  scan_tools_prune secret-scan gitleaks "$BIN"
}

[ -x "$BIN" ] || install_gitleaks

CONFIG_ARGS=()
[ -f "$ROOT/.gitleaks.toml" ] && CONFIG_ARGS=(--config "$ROOT/.gitleaks.toml")

if [ "${1:-}" = "--staged" ]; then
  exec "$BIN" protect --staged --redact ${CONFIG_ARGS[@]+"${CONFIG_ARGS[@]}"} "$ROOT"
else
  exec "$BIN" detect --redact ${CONFIG_ARGS[@]+"${CONFIG_ARGS[@]}"} "$ROOT"
fi
