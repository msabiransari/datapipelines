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
# DEVELOPMENT.md §9.4): (1) lint + the four root audits over the whole tree; (2) `check` of every
# `modules/*` module the diff touched — its unfiltered tests, every other test task it carries
# (scripting's breachSuite) and its coverage floor, reported as a number; (2b) for the two `tests/*` modules, ONLY the
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
#        plus the working tree). Logs: .pregate-logs/ — the verdict line of every run is
#        appended to .pregate-logs/0-verdict.log (PASS|FAIL, base, merge-base, HEAD, UTC time).
#        ./scripts/pregate.sh --self-test    (the stage-2b selector over isolated fixtures
#        and a recording, refusing Gradle stand-in — no gradle, no repo state touched).
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
# shellcheck source=scripts/pregate-2b-lib.sh
source "$ROOT/scripts/pregate-2b-lib.sh"
if [ "${1:-}" = "--self-test" ]; then pg2b::self_test; exit $?; fi
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

# --- 2. the touched modules' check: every test task and the coverage floor ------
# `<module>:check` is that module's slice of the gate's `build`: the unfiltered `test`, every
# other Test task the module hangs off check (scripting's breachSuite), their zero-test guards,
# web's editorJsTest, and koverVerify — the coverage FLOOR. `<module>:test` alone reached only
# the first: 2026-09-28's landing failed two gates on floors no pregate had run, and a scripting
# change's pregate skipped the breach suite in its own module (#297). Lint and the root audits
# that also hang off check are UP-TO-DATE from stage 1. koverXmlReport rides along so each
# floor is reported as a number beside its verdict.
if [ -n "$touched" ]; then
  tasks=()
  for m in $touched; do
    tasks+=("$m:check" "$m:koverXmlReport")
    # A report left by an EARLIER run would be read below as this run's number.
    rm -f "modules/${m##*:}/build/reports/kover/report.xml"
  done
  mod=$(run "$LOGDIR/2-touched-modules.log" "${tasks[@]}" --continue)
else
  mod=0
fi
echo "  2 touched modules' check (all test tasks + coverage floor)  EXIT=$mod"
[ "$mod" -ne 0 ] && grep -E 'FAILED$|> Task .* FAILED|violated' "$LOGDIR/2-touched-modules.log" | head -20 | sed 's/^/      /'
# The number behind each floor: line coverage from the module's own Kover XML (the counters
# koverVerify's line rule reads), the floor from COVERAGE_FLOORS. Informational — the verdict is
# koverVerify's exit above; a module with no report is one whose tests failed or never ran.
for m in $touched; do
  dir="modules/${m##*:}"; xml="$dir/build/reports/kover/report.xml"
  floor=$(grep -oE "\"$m\" to [0-9]+" buildSrc/src/main/kotlin/CommonConventionsPlugin.kt | grep -oE '[0-9]+$')
  if [ -f "$xml" ]; then
    python3 - "$xml" "$m" "${floor:-none}" <<'PY'
import sys, xml.etree.ElementTree as ET
xml, module, floor = sys.argv[1:4]
line = next((c for c in ET.parse(xml).getroot().findall("counter") if c.get("type") == "LINE"), None)
if line is None:
    print(f"     {module}: no LINE counter in {xml}")
else:
    covered, missed = int(line.get("covered")), int(line.get("missed"))
    pct = 100.0 * covered / (covered + missed) if covered + missed else 100.0
    print(f"     {module}: line coverage {pct:.2f}% ({covered}/{covered + missed}), floor {floor}")
PY
  else
    echo "     $m: no coverage report ($xml) — read $LOGDIR/2-touched-modules.log"
  fi
done

# --- 2b. tests/* modules: the changed test classes and their affected consumers ------
# A changed `tests/<m>/build.gradle.kts` means the knobs changed → that module unfiltered.
# The selection itself lives in scripts/pregate-2b-lib.sh (drivable by --self-test): a
# changed CONCRETE test class runs focused by class; a changed file that is not runnable —
# an abstract base, an interface, an object, a helper — schedules its REAL runnable
# consumers, found transitively through intermediate bases; when consumers cannot be
# established (orphan helper, deleted/renamed file, changed test resource) the fallback is
# the WHOLE module. The plan is never empty while test sources changed: the abstract/sealed
# SKIP this stage once had let a BrowserSuite-only change pass without any browser class
# running (#342 round review, 2026-09-30). The module's zero-test guard is skipped (a
# filtered run always trips it — §9.4); the merge gate runs these modules whole.
t2b=0
if [ -n "$touched_tests" ]; then
  targs=()
  for m in $touched_tests; do
    mfiles=()
    while IFS= read -r cf; do
      [ -n "$cf" ] && mfiles+=("$ROOT/$cf")
    done < <(echo "$changed_files" | grep -E "^tests/$m/")
    if [ "${#mfiles[@]}" -eq 0 ]; then
      echo "  2b tests/$m: no changed files → nothing to run here"
      continue
    fi
    plan="$(pg2b::plan_module "$m" "$ROOT/tests/$m/build.gradle.kts" "$ROOT/tests/$m/src/test/kotlin" ${mfiles[@]+"${mfiles[@]}"})"
    margs=()
    while IFS= read -r ga; do
      [ -n "$ga" ] && margs+=("$ga")
    done < <(pg2b::gradle_args "$m" $plan)
    if [ "${#margs[@]}" -gt 0 ]; then
      targs+=("${margs[@]}")
    else
      echo "  2b tests/$m: touched, but no test source changed → nothing to run here"
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
GUARDS[":modules:app"]="co.datapipelines.config.OrgConfigKeysSpecDriftTest co.datapipelines.config.ConfigValidatorCheckCountTest co.datapipelines.config.RemoveMemberRollbackIntegrationTest co.datapipelines.config.HelmDuckDbValuesSpecDriftTest"
# 242a's merge gate went red on the datasources doc-drift guard its pregate never ran (the lane
# had repointed paths the checklist parses); 242b adds it so a docs lane sees it here.
GUARDS[":modules:datasources"]="co.datapipelines.datasources.DialectChecklistDriftTest"
GUARDS[":modules:web"]="co.datapipelines.web.api.ApiErrorCatalogSpecDriftTest co.datapipelines.web.api.RequiredScopeCoverageTest co.datapipelines.web.api.RequiredScopeKonsistTest co.datapipelines.web.api.MutatingHandlerScopeFloorTest co.datapipelines.web.api.PublicRouteWalkerTest co.datapipelines.web.api.MatrixRowReachabilityTest co.datapipelines.web.api.ReadFloorTest co.datapipelines.web.ui.site.SiteRouteFloorTest co.datapipelines.web.ui.site.SiteSeoMetaTest co.datapipelines.web.ui.site.SiteKeywordCoverageTest co.datapipelines.web.ui.site.SiteHandTypedCountsGuardTest co.datapipelines.web.ui.site.SiteClaimCitationTest co.datapipelines.web.ui.DocsLinkRewriteTest"
# 217a's architecture rule lives in the integration module and a lane rarely touches it, so the
# changed-classes stage never runs it; a new transport→repository pair then surfaces at the merge
# gate (7d, 2026-09-25). A source scan: no containers, seconds.
# 242b adds the entry-inventory pair: both parse auth.md's §8.6.2 rows against the RUNNING app,
# and 242a's merge gate went red on them for a doc-row wording its pregate never executed.
# They boot the shared containers, so they are the expensive end of this stage — still cheaper
# than a full gate cycle.
# 2026-10-02: the two product-tree sweeps the merge gates of 332 (DependencyGuardsE2eTest, a
# fixture the lane never ran) and 353 (RequestNestingDepthE2eTest, a new @RequestBody String
# route absent from its per-file inventory) went red on — each a guard that existed and that no
# lane pregate executed.
# 2026-10-02: 328's batch gate went red on the shipped-migrations pin; no lane pregate selects it
# from a migration diff (FlywayMigrationIntegrationTest pins the exact V-list a lane adding a
# migration must extend).
GUARDS[":tests:integration-tests"]="co.datapipelines.integration.ArchitectureGuardTest co.datapipelines.integration.EntryInventoryE2eTest co.datapipelines.integration.PublicContractE2eTest co.datapipelines.integration.RequestNestingDepthE2eTest co.datapipelines.integration.DependencyGuardsE2eTest co.datapipelines.integration.FlywayMigrationIntegrationTest"
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

# The verdict is also APPENDED to a file, one line per run: the terminal is the only other place it
# lives, and the lander reads a delivered lane's verdict from here (2026-10-02 — until then it had to
# re-derive it from the five step logs).
verdict() { # verdict PASS|FAIL → one line in .pregate-logs/0-verdict.log
  echo "PRE-GATE $1 base=$BASE merge-base=$(git rev-parse --short "$mb" 2>/dev/null || echo "$mb") head=$(git rev-parse --short HEAD) at=$(date -u '+%Y-%m-%dT%H:%M:%SZ') lint=$lint mod=$mod t2b=$t2b grd=$grd cmp=$cmp" >> "$LOGDIR/0-verdict.log"
}
echo "--------------------------------------------------------------"
if [ "$lint" -eq 0 ] && [ "$mod" -eq 0 ] && [ "$t2b" -eq 0 ] && [ "$grd" -eq 0 ] && [ "$cmp" -eq 0 ]; then
  echo "  PRE-GATE PASS — a lane stops here (protocol 2026-09-24); the orchestrator's gate on the merge SHA is the verdict"
  verdict PASS
  exit 0
else
  echo "  PRE-GATE FAIL — fix the lines above"
  verdict FAIL
  exit 1
fi
