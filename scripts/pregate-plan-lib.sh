#!/usr/bin/env bash
# Read-only plan shared by preview and execution. Commands are arrays, never eval'd.
# Requires pregate-2b-lib.sh; callers cd to the checkout root first.
pgplan::build() {
  local base="$1" m cf plan argv main_mb all main_files
  PG_COVERED=()
  PG_BASE="$base"
  PG_BASE_SHA="$(git rev-parse --verify --end-of-options "${base}^{commit}")" || return 2
  PG_HEAD="$(git rev-parse --verify HEAD)" || return 2
  PG_MB="$(git merge-base "$PG_BASE_SHA" "$PG_HEAD")" || return 2
  all="$(git diff --name-only "$PG_MB" "$PG_HEAD")" || return 2
  local working untracked
  working="$(git diff --name-only HEAD)" || return 2
  untracked="$(git ls-files --others --exclude-standard)" || return 2
  PG_FILES="$(printf '%s\n' "$all" "$working" "$untracked" | sed '/^$/d' | LC_ALL=C sort -u)"
  PG_MAIN_DIFF=unavailable
  PG_MAIN_ONLY=""; PG_BASE_ONLY=""
  if git show-ref --verify --quiet refs/heads/main; then
    main_mb="$(git merge-base main "$PG_HEAD")" || return 2
    main_files="$(git diff --name-only "$main_mb" "$PG_HEAD")" || return 2
    main_files="$(printf '%s\n' "$main_files" "$working" "$untracked" | sed '/^$/d' | LC_ALL=C sort -u)"
    PG_MAIN_DIFF=same
    if [ "$PG_FILES" != "$main_files" ]; then
      PG_MAIN_DIFF=different
      PG_BASE_ONLY="$(LC_ALL=C comm -23 <(printf '%s\n' "$PG_FILES") <(printf '%s\n' "$main_files"))"
      PG_MAIN_ONLY="$(LC_ALL=C comm -13 <(printf '%s\n' "$PG_FILES") <(printf '%s\n' "$main_files"))"
    fi
  fi
  PG_MODULES=(); PG_TEST_MODULES=(); PG_DEFERRED=(); PG_ROOTS2=(); PG_ROOTS2B=()
  PG_1=(ktlintCheck detekt composeEnvAudit composeArgvSecretsAudit verifyModuleDependencies verifyVerificationMetadataDocs --continue)
  PG_2=(); PG_2B=(); PG_4=(compileTestKotlin --continue)
  PG_COMMON=(-Pdp.browser.ciPatience=true --no-parallel --max-workers=1 -Pdp.test.forks=1 -Pdp.test.forks.e2e=1)
  declare -gA PG_SELECTIONS=()
  mapfile -t PG_MODULES < <(printf '%s\n' "$PG_FILES" | sed -nE 's|^modules/([a-z-]+)/.*|:modules:\1|p' | LC_ALL=C sort -u)
  mapfile -t PG_TEST_MODULES < <(printf '%s\n' "$PG_FILES" | sed -nE 's|^tests/([a-z-]+)/.*|\1|p' | LC_ALL=C sort -u)
  for m in "${PG_MODULES[@]}"; do
    PG_2+=("$m:check" "$m:koverXmlReport")
    PG_ROOTS2+=("modules/${m##*:}")
  done
  [ "${#PG_2[@]}" -eq 0 ] || PG_2+=(--continue)
  for m in "${PG_TEST_MODULES[@]}"; do
    local -a files=() words=() args=()
    while IFS= read -r cf; do
      [[ "$cf" == tests/"$m"/* ]] && files+=("$ROOT/$cf")
    done <<< "$PG_FILES"
    plan="$(pg2b::plan_module "$m" "$ROOT/tests/$m/build.gradle.kts" "$ROOT/tests/$m/src/test/kotlin" "${files[@]}")" || return 2
    PG_SELECTIONS[$m]="$plan"
    mapfile -t words <<< "$plan"
    argv="$(pg2b::gradle_args "$m" "${words[@]}")" || return 2
    if [ "${words[0]}" = deferred ]; then PG_DEFERRED+=("tests/$m"); fi
    if [ -n "$argv" ]; then
      mapfile -t args <<< "$argv"
      PG_2B+=("${args[@]}"); PG_ROOTS2B+=("tests/$m")
    fi
  done
  [ "${#PG_2B[@]}" -eq 0 ] || PG_2B+=(--continue)
  pgplan::guard_args
}

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

# Only runtime retained evidence can populate PG_COVERED; preview passes none.
pgplan::guard_args() {
  PG_3=(); PG_ROOTS3=()
  local m c
  mapfile -t PG_GUARD_MODULES < <(printf '%s\n' "${!GUARDS[@]}" | LC_ALL=C sort)
  for m in "${PG_GUARD_MODULES[@]}"; do
    [ -z "${PG_COVERED[$m]+x}" ] || continue
    PG_3+=("$m:test")
    for c in ${GUARDS[$m]}; do PG_3+=(--tests "$c"); done
    PG_3+=(-x "$m:verifyTestsExecuted")
    local rel="${m#:}"; PG_ROOTS3+=("${rel//:/\/}")
  done
  [ "${#PG_3[@]}" -eq 0 ] || PG_3+=(--continue)
}

declare -A PG_COVERED=()
pgplan::args() {
  local -n dest="$2"
  case "$1" in
    1) dest=("${PG_1[@]}") ;; 2) dest=("${PG_2[@]}") ;;
    2b) dest=("${PG_2B[@]}") ;; 3) dest=("${PG_3[@]}") ;;
    4) dest=("${PG_4[@]}") ;; *) return 2 ;;
  esac
  [ "${#dest[@]}" -eq 0 ] || dest+=("${PG_COMMON[@]}")
}
pgplan::command() {
  local stage="$1"; shift
  printf 'stage=%s command=' "$stage"
  if [ "$#" -eq 0 ]; then printf '(none)'; else printf '%q ' ./gradlew "$@"; fi
  printf '\n'
}
pgplan::print() {
  printf 'base=%s resolved-base=%s merge-base=%s head=%s\n' "$PG_BASE" "$PG_BASE_SHA" "$PG_MB" "$PG_HEAD"
  printf 'changed files (selected base plus staged/unstaged/untracked):\n%s\n' "${PG_FILES:-none}"
  printf 'current-main comparison=%s\nselected-base-only:\n%s\ncurrent-main-only:\n%s\n' "$PG_MAIN_DIFF" "${PG_BASE_ONLY:-none}" "${PG_MAIN_ONLY:-none}"
  printf 'touched modules: %s\n' "${PG_MODULES[*]:-none}"
  local m stage; local -a args=()
  for m in "${PG_TEST_MODULES[@]}"; do printf 'selection tests/%s:\n%s\n' "$m" "${PG_SELECTIONS[$m]}"; done
  printf 'deferred=%s\n' "${PG_DEFERRED[*]:-none}"
  printf 'guards (omission conditional on successful fresh unfiltered stage-2 retained XML):\n'
  for m in "${PG_GUARD_MODULES[@]}"; do printf '%s %s\n' "$m" "${GUARDS[$m]}"; done
  printf 'stage order: 1 2 2b 3 4\n'
  for stage in 1 2 2b 3 4; do pgplan::args "$stage" args; pgplan::command "$stage" "${args[@]}"; done
}
