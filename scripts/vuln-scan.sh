#!/usr/bin/env bash
# vuln-scan.sh — dependency vulnerability scan (OSV-Scanner) over the committed
# Gradle lockfiles. See DEVELOPMENT.md §10.2.
#
#   ./scripts/vuln-scan.sh          # scan; exit 1 on any finding
#
# Tool: osv-scanner, PINNED. Version verified 2026-08-15 against the GitHub
# releases API (google/osv-scanner, latest release). Install method: the
# release binary is downloaded from the release page into build/tools/
# (git-ignored), SHA256-checked against the release's own osv-scanner_SHA256SUMS
# manifest, and reused on later runs. Bump by editing OSV_SCANNER_VERSION after
# verifying the new release the same way.
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
# OFFLINE BEHAVIOUR: the scan needs network access to osv.dev. When the network
# is unreachable this script WARNS and exits 0 (fail-soft) so an offline laptop
# is not a broken gate — the warning is printed in both modes so the skip is
# never silent.

set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"

OSV_SCANNER_VERSION="v2.5.0"   # verified latest release, 2026-08-15
TOOL_DIR="$ROOT/build/tools/osv-scanner"

os=$(uname -s | tr '[:upper:]' '[:lower:]')    # darwin | linux
arch=$(uname -m)
[ "$arch" = "x86_64" ] && arch="amd64"
BIN="$TOOL_DIR/osv-scanner-${OSV_SCANNER_VERSION}-${os}-${arch}"

install_osv_scanner() {
  mkdir -p "$TOOL_DIR"
  local base="https://github.com/google/osv-scanner/releases/download/${OSV_SCANNER_VERSION}"
  local asset="osv-scanner_${os}_${arch}"
  echo "vuln-scan: installing osv-scanner ${OSV_SCANNER_VERSION} (${asset})"
  curl -sfL "$base/$asset" -o "$BIN"
  curl -sfL "$base/osv-scanner_SHA256SUMS" -o "$TOOL_DIR/SHA256SUMS"
  # Verify against the release manifest; refuse to run an unverified binary.
  local want got
  want=$(grep "  $asset\$" "$TOOL_DIR/SHA256SUMS" | awk '{print $1}')
  got=$(shasum -a 256 "$BIN" | awk '{print $1}')
  if [ -z "$want" ] || [ "$want" != "$got" ]; then
    echo "vuln-scan: SHA256 mismatch for $asset (want=$want got=$got)" >&2
    rm -f "$BIN"
    exit 1
  fi
  chmod +x "$BIN"
}

[ -x "$BIN" ] || install_osv_scanner

# Fail-soft offline preflight: osv-scanner is useless without osv.dev.
# Any HTTP response counts as online (the root path answers 404 — that is fine);
# only a connection-level failure (curl exit != 0) means offline.
if ! curl -s --max-time 5 -o /dev/null https://api.osv.dev/; then
  echo "vuln-scan: WARNING — osv.dev unreachable (offline?); vulnerability scan SKIPPED (fail-soft)." >&2
  exit 0
fi

lockfiles=()
while IFS= read -r f; do lockfiles+=("$f"); done < <(git ls-files -- 'gradle.lockfile' '**/gradle.lockfile')
if [ "${#lockfiles[@]}" -eq 0 ]; then
  echo "vuln-scan: no committed gradle.lockfile files found" >&2
  exit 1
fi

args=()
for f in "${lockfiles[@]}"; do args+=("--lockfile=$ROOT/$f"); done

echo "vuln-scan: osv-scanner $OSV_SCANNER_VERSION over ${#lockfiles[@]} lockfile(s)"
# Exit code is osv-scanner's: 0 = clean, 1 = vulnerabilities found.
"$BIN" scan source --config="$ROOT/osv-scanner.toml" "${args[@]}"
