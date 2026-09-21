#!/usr/bin/env bash
#
# pregate.sh — the CHEAP checks before the first full gate (owner ruling 2026-09-21).
#
# The last five lanes spent 2–3 extra full gates each (15–22 min a run) on findings a
# targeted test run cannot see: ktlint/detekt (they run only in the full build),
# cross-cutting guards in modules the lane never touched (spec-drift, route floors,
# coverage scans, page-count pins), and fixtures in other modules. This script runs
# exactly those in a few minutes so the full gate runs once.
#
# Three stages, each its own bare gradle invocation writing to a log FILE (no pipes —
# DEVELOPMENT.md §9.4): (1) lint over the whole tree; (2) the UNFILTERED test task of
# every module the diff touched; (3) the cross-cutting guard classes, filtered, with
# the zero-test guard skipped for those modules (a filtered run always trips it —
# §9.4). Exit 0 only if all three passed. This is NOT the gate: scripts/gate.sh is.
#
# Usage: ./scripts/pregate.sh [base-ref]     (default: origin/main; the diff is base...HEAD
#        plus the working tree). Logs: .pregate-logs/.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
BASE="${1:-origin/main}"; LOGDIR="$ROOT/.pregate-logs"; mkdir -p "$LOGDIR"

run() { # run <logfile> <args...> → echoes exit code; never pipes gradle
  local log="$1"; shift
  ./gradlew "$@" > "$log" 2>&1
  echo $?
}

# --- touched modules -----------------------------------------------------------
mb="$(git merge-base "$BASE" HEAD 2>/dev/null || echo "$BASE")"
touched="$( { git diff --name-only "$mb" HEAD; git diff --name-only; git ls-files --others --exclude-standard; } \
  | grep -oE '^(modules|tests)/[a-z-]+/' | sort -u | sed -E 's|^(modules\|tests)/([a-z-]+)/|:\1:\2|')"
echo "=============================================================="
echo " Pre-gate   |   $(date '+%Y-%m-%d %H:%M:%S %z')   |   base $BASE ($mb)"
echo " touched modules: ${touched:-(none)}"
echo " logs: $LOGDIR"
echo "=============================================================="

# --- 1. lint, whole tree --------------------------------------------------------
lint=$(run "$LOGDIR/1-lint.log" ktlintCheck detekt --continue)
echo "  1 lint (ktlintCheck detekt, whole tree)          EXIT=$lint"
[ "$lint" -ne 0 ] && grep -E 'ktlint|detekt|\.kt:[0-9]+' "$LOGDIR/1-lint.log" | grep -vE '^> Task|UP-TO-DATE' | head -20 | sed 's/^/      /'

# --- 2. unfiltered tests of the touched modules ---------------------------------
if [ -n "$touched" ]; then
  tasks=$(echo "$touched" | sed 's/$/:test/' | tr '\n' ' ')
  # shellcheck disable=SC2086
  mod=$(run "$LOGDIR/2-touched-modules.log" $tasks --continue)
else
  mod=0
fi
echo "  2 touched modules' tests (unfiltered)              EXIT=$mod"
[ "$mod" -ne 0 ] && grep -E 'FAILED$|> Task .* FAILED' "$LOGDIR/2-touched-modules.log" | head -20 | sed 's/^/      /'

# --- 3. cross-cutting guards -----------------------------------------------------
# One line per module: the guard classes that read the WHOLE tree or the docs, so a
# lane that touched module A can break them in module B. Keep in sync with the list in
# DEVELOPMENT.md §9.4 ("the pre-gate").
declare -A GUARDS
GUARDS[":modules:auth"]="co.datapipelines.auth.ScopeMatrixSpecDriftTest co.datapipelines.auth.PublicPathsTest co.datapipelines.auth.RoleMatrixTest"
GUARDS[":modules:pipeline-contract"]="co.datapipelines.pipeline.PipelineErrorCodesSpecDriftTest"
GUARDS[":modules:mcp-server"]="co.datapipelines.mcp.SkillDistributionTest co.datapipelines.mcp.McpToolSurfaceSpecDriftTest"
GUARDS[":modules:app"]="co.datapipelines.config.OrgConfigKeysSpecDriftTest co.datapipelines.config.ConfigValidatorCheckCountTest"
GUARDS[":modules:web"]="co.datapipelines.web.api.ApiErrorCatalogSpecDriftTest co.datapipelines.web.api.RequiredScopeCoverageTest co.datapipelines.web.api.RequiredScopeKonsistTest co.datapipelines.web.api.MutatingHandlerScopeFloorTest co.datapipelines.web.api.PublicRouteWalkerTest co.datapipelines.web.api.MatrixRowReachabilityTest co.datapipelines.web.api.ReadFloorTest co.datapipelines.web.ui.site.SiteRouteFloorTest co.datapipelines.web.ui.site.SiteSeoMetaTest co.datapipelines.web.ui.site.SiteKeywordCoverageTest co.datapipelines.web.ui.site.SiteHandTypedCountsGuardTest co.datapipelines.web.ui.site.SiteClaimCitationTest co.datapipelines.web.ui.DocsLinkRewriteTest"
args=()
for m in "${!GUARDS[@]}"; do
  args+=("$m:test")
  for c in ${GUARDS[$m]}; do args+=("--tests" "$c"); done
  args+=("-x" "$m:verifyTestsExecuted")
done
grd=$(run "$LOGDIR/3-guards.log" "${args[@]}" --continue)
echo "  3 cross-cutting guards (filtered, zero-test guard skipped) EXIT=$grd"
[ "$grd" -ne 0 ] && grep -E 'FAILED$|> Task .* FAILED' "$LOGDIR/3-guards.log" | head -20 | sed 's/^/      /'

echo "--------------------------------------------------------------"
if [ "$lint" -eq 0 ] && [ "$mod" -eq 0 ] && [ "$grd" -eq 0 ]; then
  echo "  PRE-GATE PASS — now run ./scripts/gate.sh 1 (the verdict is the gate's, not this)"
  exit 0
else
  echo "  PRE-GATE FAIL — fix the lines above before spending a full gate"
  exit 1
fi
