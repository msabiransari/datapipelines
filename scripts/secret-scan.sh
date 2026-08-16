#!/usr/bin/env bash
# secret-scan.sh — secret scanning with gitleaks (DEVELOPMENT.md §10.2).
#
#   ./scripts/secret-scan.sh            # full history: gitleaks detect
#   ./scripts/secret-scan.sh --staged   # staged changes only: gitleaks protect --staged
#                                       # (this is what the pre-commit hook runs)
#
# Tool: gitleaks, PINNED. Version verified 2026-08-15 against the GitHub
# releases API (gitleaks/gitleaks, latest release). Install method: the release
# tarball is downloaded from the release page into build/tools/ (git-ignored),
# SHA256-checked against the release's own checksums file, and reused on later
# runs. Bump by editing GITLEAKS_VERSION after verifying the new release the
# same way.
#
# Allowlist: .gitleaks.toml at the repo root; every entry carries a reason +
# date comment (project rule).
#
# Exit code is gitleaks': 0 = clean, 1 = leaks found.

set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"

GITLEAKS_VERSION="8.30.1"    # verified latest release, 2026-08-15
TOOL_DIR="$ROOT/build/tools/gitleaks"

os=$(uname -s | tr '[:upper:]' '[:lower:]')    # darwin | linux
arch=$(uname -m)
[ "$arch" = "x86_64" ] && arch="x64"           # gitleaks names amd64 assets x64
BIN="$TOOL_DIR/gitleaks-${GITLEAKS_VERSION}-${os}-${arch}"

install_gitleaks() {
  mkdir -p "$TOOL_DIR"
  local base="https://github.com/gitleaks/gitleaks/releases/download/v${GITLEAKS_VERSION}"
  local asset="gitleaks_${GITLEAKS_VERSION}_${os}_${arch}.tar.gz"
  echo "secret-scan: installing gitleaks ${GITLEAKS_VERSION} (${asset})"
  curl -sfL "$base/$asset" -o "$TOOL_DIR/$asset"
  curl -sfL "$base/gitleaks_${GITLEAKS_VERSION}_checksums.txt" -o "$TOOL_DIR/checksums.txt"
  # Verify against the release manifest; refuse to run an unverified binary.
  local want got
  want=$(grep "  $asset\$" "$TOOL_DIR/checksums.txt" | awk '{print $1}')
  got=$(shasum -a 256 "$TOOL_DIR/$asset" | awk '{print $1}')
  if [ -z "$want" ] || [ "$want" != "$got" ]; then
    echo "secret-scan: SHA256 mismatch for $asset (want=$want got=$got)" >&2
    rm -f "$TOOL_DIR/$asset"
    exit 1
  fi
  tar -xzf "$TOOL_DIR/$asset" -C "$TOOL_DIR" gitleaks
  mv "$TOOL_DIR/gitleaks" "$BIN"
  chmod +x "$BIN"
  rm -f "$TOOL_DIR/$asset"
}

[ -x "$BIN" ] || install_gitleaks

CONFIG_ARGS=()
[ -f "$ROOT/.gitleaks.toml" ] && CONFIG_ARGS=(--config "$ROOT/.gitleaks.toml")

if [ "${1:-}" = "--staged" ]; then
  exec "$BIN" protect --staged --redact ${CONFIG_ARGS[@]+"${CONFIG_ARGS[@]}"} "$ROOT"
else
  exec "$BIN" detect --redact ${CONFIG_ARGS[@]+"${CONFIG_ARGS[@]}"} "$ROOT"
fi
