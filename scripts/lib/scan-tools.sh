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

# Tooling-failure exit code shared by every script that sources this lib
# (013/F1): the helpers below exit 2 on download/verify failure so an
# install-side breakage (including a TAMPERED binary failing its SHA256
# check) can never surface as exit 1 — which in the scanners' contracts
# means "findings" (vuln-scan: vulnerabilities found; secret-scan: leaks
# found). A supply-chain failure must read as "no verdict", never as a scan
# result. Because these helpers are sourced, their exit terminates the
# calling script directly.
SCAN_EXIT_BROKEN=2

# scan_tools_classify_network <script> <url> [first-budget] [retry-budget]
# Offline preflight CLASSIFIER (013/F2): prints exactly one of
#   online | offline | nocurl | broken:<rc>
# Policy — "fail-soft is the exception that must be earned":
#   * curl 0                                    → online
#   * curl 5/6/7 (resolve/proxy/refuse)         → offline — connection-level
#   * curl 28 (whole-operation deadline: DNS+TCP+TLS+first byte) is the ONE
#     ambiguous code — a slow proxy, cold-DNS container, or loaded box hits it
#     while fully online — so it gets ONE retry at a materially longer budget
#     (default 5s → 20s); only a SECOND failure (any connection-level code)
#     earns "offline". A single 28 therefore can no longer silently skip a
#     scan that would have run (013/F2's false-PASS).
#   * curl 127                                  → nocurl (caller fails loudly)
#   * anything else (35/60/77 TLS/CA, …)        → broken:<rc> (caller fails loudly)
scan_tools_classify_network() {
  local script="$1" url="$2" first="${3:-5}" retry="${4:-20}" rc
  curl -s --max-time "$first" -o /dev/null "$url" || rc=$?
  case "${rc:-0}" in
    0) echo online ;;
    28)
      echo "$script: preflight timed out after ${first}s (curl 28 — deadline, not proof of offline); retrying once with a ${retry}s budget…" >&2
      rc=0
      curl -s --max-time "$retry" -o /dev/null "$url" || rc=$?
      case "${rc:-0}" in
        0)      echo online ;;
        5|6|7|28) echo "offline" ;;
        127)    echo nocurl ;;
        *)      echo "broken:$rc" ;;
      esac
      ;;
    5|6|7) echo offline ;;
    127)   echo nocurl ;;
    *)     echo "broken:$rc" ;;
  esac
}

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
    exit "$SCAN_EXIT_BROKEN"
  fi
}

# scan_tools_ver_lt <a> <b> — numeric dotted-version comparison, returns 0
# if a < b, 1 if a >= b, 2 if either version is not numeric-dot-separated
# (caller treats "incomparable" as "do not delete" — conservative).
scan_tools_ver_lt() {
  local a="${1#v}" b="${2#v}" i p q
  local IFS=.
  local -r xa=($a) xb=($b)
  local n=$(( ${#xa[@]} > ${#xb[@]} ? ${#xa[@]} : ${#xb[@]} ))
  for ((i = 0; i < n; i++)); do
    p="${xa[i]:-0}"; q="${xb[i]:-0}"
    [[ "$p" =~ ^[0-9]+$ && "$q" =~ ^[0-9]+$ ]] || return 2
    (( p < q )) && return 0
    (( p > q )) && return 1
  done
  return 1
}

# scan_tools_prune <script> <tool> <keep-binary>
# Remove GENUINE SUPERSESSIONS of <tool>: same platform, strictly OLDER
# version than the just-installed <keep-binary> (013/F5). The old form
# globbed "<tool>-*" and deleted everything not string-equal to keep —
# version- and platform-blind. Two failure shapes that made offline commits
# impossible: (1) checking out a state pinning an OLDER version ran its
# installer, which pruned the NEWER binary — switching back OFFLINE left
# `[ -x "$BIN" ]` false and the install's curl dead (pre-commit hook blocks
# every commit); (2) a shared checkout used from two platforms (Rosetta
# x86_64 vs native arm64 — container-scan names its binaries with raw
# `uname -m`) deleted the other platform's working binary. Pruning only
# older same-platform versions keeps both safe: a temporary downgrade never
# destroys the newer binary, and cross-platform binaries always coexist.
# Still prunes only on install — a failed download/verify never deletes the
# working binary it would fall back to.
#
# Name grammar: "<tool>-<version>-<os>-<arch>" (os/arch tokens are
# tool-specific; the platform is taken as everything after the version, so
# the function stays agnostic of each tool's token style). Versions with a
# dash would break the parse — none of the three pins has one; a non-numeric
# version token marks the file incomparable → KEPT, never deleted.
scan_tools_prune() {
  local script="$1" tool="$2" keep="$3" removed=0 f
  # NB: one declaration per line — `local a=x b=$a` expands $a BEFORE a is
  # assigned (all args of one local are expanded first), yielding "".
  local base="${keep##*/}"
  local rest="${base#"$tool"-}"
  local keep_ver="${rest%%-*}"
  local platform="${rest#*"-"}"
  if [ -z "$keep_ver" ] || [ "$platform" = "$rest" ]; then
    echo "$script: prune skipped — cannot parse platform from $base (name grammar changed?)" >&2
    return 0
  fi
  for f in "$(scan_tools_dir "$tool")"/"$tool"-*-"$platform"; do
    [ -e "$f" ] || continue
    [ "$f" = "$keep" ] && continue
    local frec="${f##*/}"
    local fver="${frec#"$tool"-}"
    fver="${fver%%-*}"
    if scan_tools_ver_lt "$fver" "$keep_ver"; then
      rm -f "$f"
      removed=$((removed + 1))
    else
      echo "$script: keeping $frec (same platform, not older than installed $keep_ver — or version not comparable)" >&2
    fi
  done
  if [ "$removed" -gt 0 ]; then
    echo "$script: pruned $removed superseded $tool binary(ies) for platform $platform from $(scan_tools_dir "$tool")"
  fi
  return 0
}

# scan_tools_verify_sha256 <script-name> <file> <sums-file> <asset-name>
# Verify <file> against the release's own checksum manifest. On mismatch (or
# no manifest entry) delete the file and exit 2 (013/F1): a tampered or
# corrupted binary is a supply-chain failure, NOT a scan verdict — exit 1
# would read as "findings"/"leaks" in the callers' published contracts.
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
  # `|| true`: under the callers' `set -euo pipefail` a failed shasum
  # (missing file) must fall through to the named exits below — a raw set -e
  # death would leak shasum's exit 1, colliding with "findings" (caught
  # empirically in the 013/F1 proof run).
  got=$(shasum -a 256 "$file" 2>/dev/null | awk '{print $1}') || true
  if [ -z "$want" ]; then
    echo "$script: asset '$asset' not found in manifest $sums — refusing to run an unverified binary." >&2
    rm -f "$file"
    exit "$SCAN_EXIT_BROKEN"
  fi
  if [ "$want" != "$got" ]; then
    echo "$script: SHA256 mismatch for $asset (want=$want got=$got)" >&2
    rm -f "$file"
    exit "$SCAN_EXIT_BROKEN"
  fi
}
