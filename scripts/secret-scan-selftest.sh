#!/usr/bin/env bash
# Prove the pinned scanner detects secrets and keeps AND allowlists at finding
# level in Git mode (#686, #691). Run secret-scan.sh first to install the binary.
# Exit 0 = pass, 1 = failed assertion, 2 = tooling failure (no verdict).
set -Eeuo pipefail
trap 'echo "secret-scan-selftest: tooling failure at line $LINENO — no verdict" >&2; exit 2' ERR
cd "$(dirname "$0")/.."
ROOT="$PWD"
source "$ROOT/scripts/lib/scan-tools.sh"

# Reuse the canonical install and version; this script never downloads anything.
GITLEAKS_VERSION=$(sed -nE 's/^GITLEAKS_VERSION="([^"]+)".*/\1/p' scripts/secret-scan.sh)
[ -n "$GITLEAKS_VERSION" ] || { echo 'self-test: missing version — no verdict' >&2; exit 2; }
TOOL_DIR="$(scan_tools_dir gitleaks)"
os=$(uname -s | tr '[:upper:]' '[:lower:]')
arch=$(trap - ERR; scan_tools_arch x64) || { echo 'self-test: unsupported architecture — no verdict' >&2; exit 2; }
BIN="$TOOL_DIR/gitleaks-${GITLEAKS_VERSION}-${os}-${arch}"
[ -x "$BIN" ] || { echo 'self-test: run scripts/secret-scan.sh first — no verdict' >&2; exit 2; }

# All planted material, reports and Git objects live outside the working tree.
scratch=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/secret-scan-selftest.XXXXXX")
trap 'rm -rf "$scratch"' EXIT
trap 'exit 2' HUP INT TERM
printf '[extend]\nuseDefault = true\n' > "$scratch/default.toml"
fixture_path=modules/app/src/test/kotlin/co/datapipelines/config/ConfigValidatorTest.kt
# Read the exact admitted fixture from the maintained allowlist, not a second copy.
admitted=$(awk '/^paths = .*ConfigValidatorTest/ {found=1; next}
  found && /^regexes =/ {sub(/^.*\^/, ""); sub(/\$.*$/, ""); print; exit}' .gitleaks.toml)
[ -n "$admitted" ] || { echo 'self-test: admitted fixture missing — no verdict' >&2; exit 2; }
# v8.30.1 default github-pat rule: ghp_ followed by 36 alphanumeric chars,
# entropy >= 3 (upstream config/gitleaks.toml). Generate every value at run time.
plant="ghp_$(openssl rand -hex 18)"
different="ghp_$(openssl rand -hex 18)"

new_repo() {
  repo="$scratch/$1"
  mkdir -p "$repo"
  git -C "$repo" init -q
  cp "$ROOT/.gitleaks.toml" "$repo/.gitleaks.toml"
}
commit_fixture() {
  git -C "$repo" add .
  # Isolated fixtures must not invoke the checkout's hooks or signing setup.
  git -C "$repo" -c core.hooksPath=/dev/null -c commit.gpgsign=false \
    -c user.name=secret-scan-selftest -c user.email=secret-scan@invalid \
    commit -qm 'Synthetic self-test fixture'
}
fail_leg() {
  echo "secret-scan-selftest: leg $1 FAILED — $2" >&2
  exit 1
}
scan() {
  local leg="$1" config="$2" expected="$3" rule="$4" rc=0 commits summary leaks rules
  # --source is explicit: never depend on deprecated detect positional handling.
  "$BIN" detect --source "$repo" --config "$config" --redact --no-banner --no-color \
    --report-format json --report-path "$scratch/report.json" > "$scratch/scan.log" 2>&1 || rc=$?
  case "$rc" in
    0|1) ;;
    *) echo "self-test: leg $leg scanner exit=$rc — no verdict" >&2; exit 2 ;;
  esac
  commits=$(sed -nE 's/.* ([0-9]+) commits? scanned.*/\1/p' "$scratch/scan.log")
  if ! [[ "$commits" =~ ^[0-9]+$ ]] || [ "$commits" -lt 1 ]; then
    echo "self-test: leg $leg has no commit scan evidence — no verdict" >&2
    exit 2
  fi
  summary=$(python3 - "$scratch/report.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as report:
    findings = json.load(report)
print(len(findings), ",".join(sorted({finding["RuleID"] for finding in findings})) or "none")
PY
  )
  read -r leaks rules <<< "$summary"
  echo "leg $leg: $commits commits scanned; leaks=$leaks; rules=$rules; exit=$rc (expected $expected)"
  if [ "$expected" -eq 1 ]; then
    [ "$rc" -eq 1 ] && [ "$leaks" -ge 1 ] || fail_leg "$leg" 'expected a finding'
    [[ ",$rules," == *",$rule,"* ]] || fail_leg "$leg" "expected rule $rule"
  else
    [ "$rc" -eq 0 ] && [ "$leaks" -eq 0 ] || fail_leg "$leg" 'expected the admitted fixture to pass'
  fi
}

new_repo detects
printf 'token = "%s"\n' "$plant" > "$repo/plant.txt"
commit_fixture
scan '(i) detects/default' "$scratch/default.toml" 1 github-pat
scan '(i) detects/repo' "$repo/.gitleaks.toml" 1 github-pat

new_repo admits
mkdir -p "$repo/$(dirname "$fixture_path")"
printf 'secret = "%s"\n' "$admitted" > "$repo/$fixture_path"
commit_fixture
scan '(ii) admits/default-control' "$scratch/default.toml" 1 generic-api-key
scan '(ii) admits/repo' "$repo/.gitleaks.toml" 0 none

new_repo rejects
mkdir -p "$repo/$(dirname "$fixture_path")"
# Same file AND same line as the admitted value: file/line exemptions fail here.
printf 'secret = "%s"; token = "%s"\n' "$admitted" "$different" > "$repo/$fixture_path"
commit_fixture
scan '(iii) rejects/repo' "$repo/.gitleaks.toml" 1 github-pat

echo 'secret-scan-selftest: PASS (three legs, default-rule controls included)'
