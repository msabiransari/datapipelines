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
# Five stages, each its own bare gradle invocation writing to a log FILE (no pipes —
# DEVELOPMENT.md §9.4): (1) lint + the four root audits over the whole tree; (2) the UNFILTERED test task of
# every `modules/*` module the diff touched; (2b) for the two `tests/*` modules, ONLY the
# test classes the diff changed or added (their build file changed → the whole module),
# with the zero-test guard skipped — the E2E and browser suites are the expensive part of
# a full build, and the orchestrator's gate on the merge SHA runs them whole; (3) the
# cross-cutting guard classes, filtered, with the zero-test guard skipped for those
# modules (a filtered run always trips it — §9.4); (4) compileTestKotlin of EVERY module —
# a signature change breaks a test in a module the lane never ran (MISTAKES.md, the
# cross-module variant). Exit 0 only if all five passed.
#
# LANE PROTOCOL (owner, 2026-09-24): a lane runs THIS and does not run scripts/gate.sh.
# The full build runs twice — the orchestrator's gate on the merge SHA, and CI cold —
# not three times. This is still NOT the gate: scripts/gate.sh is.
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
changed_files="$( { git diff --name-only "$mb" HEAD; git diff --name-only; git ls-files --others --exclude-standard; } | sort -u)"
touched="$(echo "$changed_files" | grep -oE '^modules/[a-z-]+/' | sort -u | sed -E 's|^modules/([a-z-]+)/|:modules:\1|')"
touched_tests="$(echo "$changed_files" | grep -oE '^tests/[a-z-]+/' | sort -u | sed -E 's|^tests/([a-z-]+)/|\1|')"
echo "=============================================================="
echo " Pre-gate   |   $(date '+%Y-%m-%d %H:%M:%S %z')   |   base $BASE ($mb)"
echo " touched modules: ${touched:-(none)}"
echo " touched tests/ modules: ${touched_tests:-(none)}"
echo " logs: $LOGDIR"
echo "=============================================================="

# --- 1. lint, whole tree --------------------------------------------------------
# The four ROOT audits ride with lint: they hang off every module's `check`, so the gate's
# `build` reaches them before any test — a config key bound in application.yml without its
# compose pass-through (197, 2026-09-26) passed a lane's lint + tests and killed the gate
# at its first task. A targeted run never executes them; this stage does.
lint=$(run "$LOGDIR/1-lint.log" ktlintCheck detekt composeEnvAudit composeArgvSecretsAudit verifyModuleDependencies verifyVerificationMetadataDocs --continue)
echo "  1 lint + root audits (ktlintCheck detekt composeEnvAudit composeArgvSecretsAudit verifyModuleDependencies verifyVerificationMetadataDocs)  EXIT=$lint"
[ "$lint" -ne 0 ] && grep -E 'ktlint|detekt|\.kt:[0-9]+|audit:|^\s+[0-9]+ |verifyModule|verifyVerification|What went wrong' "$LOGDIR/1-lint.log" | grep -vE '^> Task|UP-TO-DATE' | head -24 | sed 's/^/      /'

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

# --- 2b. tests/* modules: the changed test classes only -------------------------
# A changed `tests/<m>/build.gradle.kts` means the knobs changed → that module unfiltered.
# Otherwise every changed/added `*.kt` under `tests/<m>/src/test/kotlin/` is a class to
# run (file name = class name, package from the path), with that module's zero-test guard
# skipped (a filtered run always trips it). The merge gate runs these modules whole.
t2b=0
if [ -n "$touched_tests" ]; then
  targs=()
  for m in $touched_tests; do
    if echo "$changed_files" | grep -qE "^tests/$m/build\.gradle\.kts$"; then
      targs+=(":tests:$m:test")
      echo "  2b tests/$m: build file changed → whole module"
    else
      classes=$(echo "$changed_files" | grep -E "^tests/$m/src/test/kotlin/.*\.kt$" \
        | sed -E "s|^tests/$m/src/test/kotlin/||; s|\.kt$||; s|/|.|g")
      if [ -n "$classes" ]; then
        targs+=(":tests:$m:test")
        for c in $classes; do targs+=("--tests" "$c"); done
        targs+=("-x" ":tests:$m:verifyTestsExecuted")
        echo "  2b tests/$m: $(echo "$classes" | wc -l | tr -d ' ') changed test class(es)"
      else
        echo "  2b tests/$m: touched, but no test class and no build file changed → nothing to run here"
      fi
    fi
  done
  if [ ${#targs[@]} -gt 0 ]; then
    t2b=$(run "$LOGDIR/2b-tests-modules.log" "${targs[@]}" --continue)
  fi
fi
echo "  2b tests/* changed classes                         EXIT=$t2b"
[ "$t2b" -ne 0 ] && grep -E 'FAILED$|> Task .* FAILED' "$LOGDIR/2b-tests-modules.log" | head -20 | sed 's/^/      /'

# --- 3. cross-cutting guards -----------------------------------------------------
# One line per module: the guard classes that read the WHOLE tree or the docs, so a
# lane that touched module A can break them in module B. Keep in sync with the list in
# DEVELOPMENT.md §9.4 ("the pre-gate").
declare -A GUARDS
GUARDS[":modules:auth"]="co.datapipelines.auth.ScopeMatrixSpecDriftTest co.datapipelines.auth.PublicPathsTest co.datapipelines.auth.RoleMatrixTest co.datapipelines.auth.PermissionResolutionTest"
GUARDS[":modules:pipeline-contract"]="co.datapipelines.pipeline.PipelineErrorCodesSpecDriftTest"
GUARDS[":modules:mcp-server"]="co.datapipelines.mcp.SkillDistributionTest co.datapipelines.mcp.McpToolSurfaceSpecDriftTest"
GUARDS[":modules:app"]="co.datapipelines.config.OrgConfigKeysSpecDriftTest co.datapipelines.config.ConfigValidatorCheckCountTest"
GUARDS[":modules:web"]="co.datapipelines.web.api.ApiErrorCatalogSpecDriftTest co.datapipelines.web.api.RequiredScopeCoverageTest co.datapipelines.web.api.RequiredScopeKonsistTest co.datapipelines.web.api.MutatingHandlerScopeFloorTest co.datapipelines.web.api.PublicRouteWalkerTest co.datapipelines.web.api.MatrixRowReachabilityTest co.datapipelines.web.api.ReadFloorTest co.datapipelines.web.ui.site.SiteRouteFloorTest co.datapipelines.web.ui.site.SiteSeoMetaTest co.datapipelines.web.ui.site.SiteKeywordCoverageTest co.datapipelines.web.ui.site.SiteHandTypedCountsGuardTest co.datapipelines.web.ui.site.SiteClaimCitationTest co.datapipelines.web.ui.DocsLinkRewriteTest"
# 217a's architecture rule lives in the integration module and a lane rarely touches it, so the
# changed-classes stage never runs it; a new transport→repository pair then surfaces at the merge
# gate (7d, 2026-09-25). A source scan: no containers, seconds.
GUARDS[":tests:integration-tests"]="co.datapipelines.integration.ArchitectureGuardTest"
args=()
for m in "${!GUARDS[@]}"; do
  args+=("$m:test")
  for c in ${GUARDS[$m]}; do args+=("--tests" "$c"); done
  args+=("-x" "$m:verifyTestsExecuted")
done
grd=$(run "$LOGDIR/3-guards.log" "${args[@]}" --continue)
echo "  3 cross-cutting guards (filtered, zero-test guard skipped) EXIT=$grd"
[ "$grd" -ne 0 ] && grep -E 'FAILED$|> Task .* FAILED' "$LOGDIR/3-guards.log" | head -20 | sed 's/^/      /'

# --- 4. every module's test sources compile ------------------------------------
# The cross-module trap (MISTAKES.md): a changed signature, a test in ANOTHER module
# still calling the old one, nothing compiled it until the full gate. About a minute.
cmp=$(run "$LOGDIR/4-compile-all-tests.log" compileTestKotlin --continue)
echo "  4 compileTestKotlin, every module                  EXIT=$cmp"
[ "$cmp" -ne 0 ] && grep -E '^e: |error:|FAILED' "$LOGDIR/4-compile-all-tests.log" | head -20 | sed 's/^/      /'

echo "--------------------------------------------------------------"
if [ "$lint" -eq 0 ] && [ "$mod" -eq 0 ] && [ "$t2b" -eq 0 ] && [ "$grd" -eq 0 ] && [ "$cmp" -eq 0 ]; then
  echo "  PRE-GATE PASS — a lane stops here (protocol 2026-09-24); the orchestrator's gate on the merge SHA is the verdict"
  exit 0
else
  echo "  PRE-GATE FAIL — fix the lines above"
  exit 1
fi
