#!/usr/bin/env bash
# Read-only plan shared by preview and execution. Commands are arrays, never eval'd.
# Requires pregate-2b-lib.sh; callers cd to the checkout root first.
# The stage order, ONE list: the plan prints it and pregate.sh executes it.
PG_STAGES=(0 1 2 2b 3 4)
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
  PG_MODULES=(); PG_TEST_MODULES=(); PG_DEFERRED=(); PG_ROOTS2=(); PG_ROOTS2B=(); PG_BUILDSRC=()
  # Stage 0 (#797): a diff that changes the resolved dependency set — any gradle.lockfile, the
  # version catalog or the verification metadata — runs Gate A's OSV scan before lint, so a
  # vulnerable transitive the lane introduced fails here and not at the batch gate. The scan
  # itself reads every committed lockfile; these paths only decide WHETHER it runs.
  mapfile -t PG_DEPS_FILES < <(printf '%s\n' "$PG_FILES" \
    | sed -nE '/(^|\/)gradle\.lockfile$|^gradle\/libs\.versions\.toml$|^gradle\/verification-metadata\.xml$/p')
  PG_0=()
  [ "${#PG_DEPS_FILES[@]}" -eq 0 ] || PG_0=(./scripts/vuln-scan.sh)
  # buildSrc is its own build, which the bare ktlintCheck/detekt never reach (#724): its lint
  # rides this invocation by task path, under the root's dependency verification.
  PG_1=(ktlintCheck detekt :buildSrc:ktlintCheck :buildSrc:detekt composeEnvAudit composeArgvSecretsAudit verifyModuleDependencies verifyVerificationMetadataDocs --continue)
  PG_2=(); PG_2B=(); PG_4=(compileTestKotlin --continue)
  # The sequences knob bounds VersionLifecycleModelTest's random sweep in the pregate
  # only (#483, DEVELOPMENT.md §9.5); Gate A and CI keep the 2000 default and stay the bank.
  # --build-cache (#480, DEVELOPMENT.md §9.4): compile and detekt outputs another worktree
  # built from identical inputs come FROM-CACHE; ktlint worker outputs (#689), test, Kover and
  # verifyTestsExecuted never do (buildSrc CommonConventionsPlugin). The pregate only: gate.sh
  # and CI stay cold. pregate-safety-test.sh asserts every stage carries it and
  # gate.sh/gradle.properties do not.
  # Both appended AFTER the serial quartet: pregate-safety-test.sh pins that substring contiguous.
  PG_COMMON=(-Pdp.browser.ciPatience=true --no-parallel --max-workers=1 -Pdp.test.forks=1 -Pdp.test.forks.e2e=1 -PversionLifecycle.sequences=200 --build-cache)
  declare -gA PG_SELECTIONS=()
  mapfile -t PG_MODULES < <(printf '%s\n' "$PG_FILES" | sed -nE 's|^modules/([a-z-]+)/.*|:modules:\1|p' | LC_ALL=C sort -u)
  mapfile -t PG_TEST_MODULES < <(printf '%s\n' "$PG_FILES" | sed -nE 's|^tests/([a-z-]+)/.*|\1|p' | LC_ALL=C sort -u)
  # Production shell resources must reach stage 2b even without changed test files.
  local -a layouts=()
  while IFS= read -r cf; do
    case "$cf" in
      modules/web/src/main/resources/templates/layouts/*.html) layouts+=("$ROOT/$cf") ;;
    esac
  done <<< "$PG_FILES"
  if [ "${#layouts[@]}" -gt 0 ]; then
    mapfile -t PG_TEST_MODULES < <(printf '%s\n' "${PG_TEST_MODULES[@]}" integration-tests | sed '/^$/d' | LC_ALL=C sort -u)
  fi
  for m in "${PG_MODULES[@]}"; do
    PG_2+=("$m:check" "$m:koverXmlReport")
    PG_ROOTS2+=("modules/${m##*:}")
  done
  # buildSrc (#1004, owner ruling 2026-11-15 "Fast always, full on build edits"): a diff that
  # touches buildSrc/, the version catalog or the verification metadata runs buildSrc's WHOLE
  # test task here, unfiltered, as a touched module runs its own tests. By task path from the
  # root (the root's dependency verification applies, as in stage 1); cleanTest as in the gate,
  # so a PASS never reports an UP-TO-DATE task. buildSrc has no verifyTestsExecuted and no Kover
  # floor; it is not a PG_MODULES entry. PG_BUILDSRC holds `:buildSrc` when it ran, so the run can
  # let this stage's retained XML cover stage 3's always-run buildSrc classes.
  mapfile -t PG_BUILDSRC_FILES < <(printf '%s\n' "$PG_FILES" \
    | sed -nE '/^buildSrc\/|^gradle\/libs\.versions\.toml$|^gradle\/verification-metadata\.xml$/p')
  if [ "${#PG_BUILDSRC_FILES[@]}" -gt 0 ]; then
    PG_2+=(:buildSrc:cleanTest :buildSrc:test)
    PG_ROOTS2+=(buildSrc)
    PG_BUILDSRC=(:buildSrc)
  fi
  [ "${#PG_2[@]}" -eq 0 ] || PG_2+=(--continue)
  for m in "${PG_TEST_MODULES[@]}"; do
    local -a files=() words=() args=()
    while IFS= read -r cf; do
      [[ "$cf" == tests/"$m"/* ]] && files+=("$ROOT/$cf")
    done <<< "$PG_FILES"
    if [ "$m" = integration-tests ]; then files+=("${layouts[@]}"); fi
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
GUARDS[":tests:integration-tests"]="co.datapipelines.integration.ArchitectureGuardTest co.datapipelines.integration.EntryInventoryE2eTest co.datapipelines.integration.LakeEnabledEntryInventoryE2eTest co.datapipelines.integration.PublicContractE2eTest co.datapipelines.integration.RequestNestingDepthE2eTest co.datapipelines.integration.DependencyGuardsE2eTest co.datapipelines.integration.FlywayMigrationIntegrationTest co.datapipelines.integration.ElvisBoundMatcherGuardTest"

# inventory 995 (2026-11-15): every test class that reads beyond its own module, found by
# PregateGuardInventoryTest (modules/mcp-server, itself registered below). A class that starts a
# container or an application context is waived in scripts/pregate-guard-exemptions.txt under
# the owner's ruling of 2026-11-14; one that reads only its own module carries an own-module line
# there. A new class that reads the docs, the deploy files or another module is added here (or
# exempted there) in the same commit; the inventory test names it otherwise.
GUARDS[":modules:app"]+=" co.datapipelines.config.BootstrapConfigKeysSpecDriftTest"  # inventory 995: reads docs/configuration.md
GUARDS[":modules:app"]+=" co.datapipelines.config.ConfigValidatorKeyProviderTest"  # inventory 995: reads docs/key-providers.md
GUARDS[":modules:app"]+=" co.datapipelines.config.DbKeyProviderConfigKeysSpecDriftTest"  # inventory 995: reads docs/configuration.md
GUARDS[":modules:app"]+=" co.datapipelines.config.DeploymentImageBulletsSpecDriftTest"  # inventory 995: reads Dockerfile docs/deployment.md
GUARDS[":modules:app"]+=" co.datapipelines.config.DockerfileDuckDbExtensionPinTest"  # inventory 995: reads Dockerfile deploy/duckdb-extensions.sha256
GUARDS[":modules:app"]+=" co.datapipelines.config.HelmManagementValuesSpecDriftTest"  # inventory 995: reads deploy/helm/datapipelines/templates/_helpers.tpl deploy/helm/datapipelines/templates/deployment.yaml
GUARDS[":modules:app"]+=" co.datapipelines.config.HelmScriptJsValuesSpecDriftTest"  # inventory 995: reads Dockerfile deploy/helm/datapipelines/templates/deployment.yaml
GUARDS[":modules:app"]+=" co.datapipelines.config.ImageLoaderPathSpecDriftTest"  # inventory 995: reads Dockerfile deploy
GUARDS[":modules:app"]+=" co.datapipelines.config.ImageUserPinSpecDriftTest"  # inventory 995: reads Dockerfile deploy/helm/datapipelines/templates/deployment.yaml
GUARDS[":modules:app"]+=" co.datapipelines.config.MailConfigKeysSpecDriftTest"  # inventory 995: reads docs/configuration.md
GUARDS[":modules:app"]+=" co.datapipelines.config.MetadataPoolBoundsConfigTest"  # inventory 995: reads deploy/env/defaults.env
GUARDS[":modules:app"]+=" co.datapipelines.config.ObservabilityConfigKeysSpecDriftTest"  # inventory 995: reads docs/configuration.md
GUARDS[":modules:app"]+=" co.datapipelines.config.PinDriftTest"  # inventory 995: walks the tree (git ls-files)
GUARDS[":modules:app"]+=" co.datapipelines.config.PostureDefaultsSpecDriftTest"  # inventory 995: reads deploy/compose.yml deploy/env/defaults.env
GUARDS[":modules:app"]+=" co.datapipelines.config.PromotionConfigKeysSpecDriftTest"  # inventory 995: reads docs/configuration.md modules/auth/src/main/kotlin/co/datapipelines/auth/PromotionProperties.kt
GUARDS[":modules:app"]+=" co.datapipelines.config.RedisConfigAuthorityDriftTest"  # inventory 995: reads deploy modules
GUARDS[":modules:app"]+=" co.datapipelines.config.ScriptConfigKeysSpecDriftTest"  # inventory 995: reads docs/configuration.md
GUARDS[":modules:app"]+=" co.datapipelines.config.SpringAdvisoryExemptionsTest"  # inventory 995: walks the tree (git ls-files)
GUARDS[":modules:app"]+=" co.datapipelines.config.UiConfigKeysSpecDriftTest"  # inventory 995: reads docs/configuration.md
GUARDS[":modules:app"]+=" co.datapipelines.logging.ObservabilityEventCatalogParityTest"  # inventory 995: reads .claude docs/observability.md
GUARDS[":modules:app"]+=" co.datapipelines.logging.ObservabilityRedactionSpecDriftTest"  # inventory 995: reads docs/observability.md
GUARDS[":modules:app"]+=" co.datapipelines.metrics.MeterCatalogueParityTest"  # inventory 995: reads .claude docs/observability.md
GUARDS[":modules:application"]="co.datapipelines.application.semantics.SemanticsAuditEventsSpecDriftTest"  # inventory 995: reads docs/enums.md
GUARDS[":modules:application"]+=" co.datapipelines.application.charts.ChartJudgeEngineTest"  # reads the js-rendered engine and shared fixtures
GUARDS[":modules:application"]+=" co.datapipelines.application.charts.ChartTypeRegistryTest"  # reads docs/enums.md and modules/js-rendered
GUARDS[":modules:application"]+=" co.datapipelines.application.charts.JsRenderedIdentityTest"  # reads modules/js-rendered
GUARDS[":modules:application"]+=" co.datapipelines.application.charts.JsRenderedPictureTest"  # reads modules/js-rendered
GUARDS[":modules:application"]+=" co.datapipelines.application.charts.JsRenderedSourceGuardTest"  # scans modules/js-rendered source
GUARDS[":modules:app"]+=" co.datapipelines.config.ChartLibraryRemnantGuardTest"  # walks the tree (git ls-files)
GUARDS[":modules:app"]+=" co.datapipelines.config.ChildJvmTempDirectoryGuardTest"  # walks the tree (git ls-files)
GUARDS[":modules:auth"]+=" co.datapipelines.auth.AuthErrorSpecDriftTest"  # inventory 995: reads docs/pipeline-contract.md
GUARDS[":modules:auth"]+=" co.datapipelines.auth.AuthPropertiesSpecDriftTest"  # inventory 995: reads docs/configuration.md
GUARDS[":modules:auth"]+=" co.datapipelines.auth.MailAuditEventsSpecDriftTest"  # inventory 995: reads docs/enums.md
GUARDS[":modules:auth"]+=" co.datapipelines.auth.SecurityConfigCsrfExemptionParityTest"  # inventory 995: reads docs/auth.md
GUARDS[":modules:auth"]+=" co.datapipelines.auth.SecurityHeadersTest"  # inventory 995: reads modules/web/src/main/resources/static/vendor/cytoscape/cytoscape.min.js
GUARDS[":modules:auth"]+=" co.datapipelines.auth.SecurityMutationAnchorsTest"  # inventory 995: reads .claude scripts/security-mutations
GUARDS[":modules:calculators"]="co.datapipelines.calculators.CalculatorRegistrySpecDriftTest"  # inventory 995: reads docs/calculators.md
GUARDS[":modules:datasources"]+=" co.datapipelines.datasources.DuckDbVersionTest"  # inventory 995: reads gradle/libs.versions.toml
GUARDS[":modules:lake"]="co.datapipelines.lake.LakeConfigKeysSpecDriftTest"  # inventory 995: reads docs/configuration.md modules/app/src/main/resources/application.yml
GUARDS[":modules:lake"]+=" co.datapipelines.lake.LakeDeletionArchitectureTest"  # inventory 995: reads .claude modules
GUARDS[":modules:lake"]+=" co.datapipelines.lake.definitions.LakeAuditEventsSpecDriftTest"  # inventory 995: reads docs/enums.md
GUARDS[":modules:lake"]+=" co.datapipelines.lake.definitions.LakeDefinitionEnumsSpecDriftTest"  # inventory 995: reads docs/enums.md
GUARDS[":modules:lake"]+=" co.datapipelines.lake.delivery.DeliveryEnumsSpecDriftTest"  # inventory 995: reads docs/enums.md modules/app/src/main/resources/db/migration/V55__lake_deliveries.sql
GUARDS[":modules:lake"]+=" co.datapipelines.lake.delivery.LakeDeliveryAuditEventsSpecDriftTest"  # inventory 995: reads docs/enums.md
GUARDS[":modules:lake"]+=" co.datapipelines.lake.maintenance.MaintenanceEnumsSpecDriftTest"  # inventory 995: reads docs/enums.md modules/app/src/main/resources/db/migration/V60__lake_maintenance_runs.sql
GUARDS[":modules:lake"]+=" co.datapipelines.lake.maintenance.cleanup.CleanupEnumsSpecDriftTest"  # inventory 995: reads docs/enums.md
GUARDS[":modules:lake"]+=" co.datapipelines.lake.maintenance.keep.KeepEnumsSpecDriftTest"  # inventory 995: reads docs/enums.md
GUARDS[":modules:lake"]+=" co.datapipelines.lake.maintenance.plan.PlanEnumsSpecDriftTest"  # inventory 995: reads docs/enums.md
GUARDS[":modules:lake"]+=" co.datapipelines.lake.profiles.LakeProfileAuditEventsSpecDriftTest"  # inventory 995: reads docs/enums.md
GUARDS[":modules:lake"]+=" co.datapipelines.lake.profiles.LakeTuningSourceGuardTest"  # inventory 995: reads modules modules/app/src/main/
GUARDS[":modules:lake"]+=" co.datapipelines.lake.settle.CatalogStoreTimeoutsTest"  # inventory 995: reads docs/lake.md
GUARDS[":modules:lake"]+=" co.datapipelines.lake.settle.SettlementObjectReadEndCompatTest"  # inventory 995: reads gradle/libs.versions.toml
GUARDS[":modules:lake"]+=" co.datapipelines.lake.settle.SettlementObjectReadEndDerivationTest"  # inventory 995: reads docs/lake.md
GUARDS[":modules:lake"]+=" co.datapipelines.lake.settle.SettlementOverrunDerivationTest"  # inventory 995: reads docs/configuration.md docs/lake.md
GUARDS[":modules:mcp-server"]+=" co.datapipelines.mcp.DocSetStructureTest"  # inventory 995: reads docs/pipeline-contract.md
GUARDS[":modules:mcp-server"]+=" co.datapipelines.mcp.DuckDbCoreRenderTest"  # inventory 995: reads gradle/libs.versions.toml
GUARDS[":modules:mcp-server"]+=" co.datapipelines.mcp.McpKeysV2ProseDriftTest"  # inventory 995: reads docs/mcp-server.md
GUARDS[":modules:mcp-server"]+=" co.datapipelines.mcp.PregateGuardInventoryTest"  # inventory 995: the completeness check itself (reads the tree, the registry and the exemptions)
GUARDS[":modules:parameters"]="co.datapipelines.parameters.ParameterEnumsSpecDriftTest"  # inventory 995: reads docs/enums.md
GUARDS[":modules:parameters"]+=" co.datapipelines.parameters.ParameterErrorCodesSpecDriftTest"  # inventory 995: reads docs/pipeline-contract.md
GUARDS[":modules:parameters"]+=" co.datapipelines.parameters.ParametersConfigKeysSpecDriftTest"  # inventory 995: reads docs/configuration.md modules/app/src/main/kotlin/co/datapipelines/config/ParametersRules.kt
GUARDS[":modules:parameters"]+=" co.datapipelines.parameters.SampleDataParameterSetsContentTest"  # inventory 995: reads deploy/sample-data/bootstrap-datasources-nyc.yml scripts/sample-data/content/examples.json
GUARDS[":modules:scheduler"]="co.datapipelines.scheduler.SchedulerConfigKeysSpecDriftTest"  # inventory 995: reads docs/configuration.md modules/app/src/main/resources/application.yml
GUARDS[":modules:scripting"]="co.datapipelines.scripting.DockerfileGraalJsPinTest"  # inventory 995: reads Dockerfile gradle/libs.versions.toml
GUARDS[":modules:visualization"]="co.datapipelines.visualization.ChartTypeTest"  # reads docs/enums.md
GUARDS[":modules:visualization"]+=" co.datapipelines.visualization.DashboardErrorCodesSpecDriftTest"  # inventory 995: reads docs/pipeline-contract.md
GUARDS[":modules:visualization"]+=" co.datapipelines.visualization.DashboardRuntimeConfigKeysSpecDriftTest"  # inventory 995: reads deploy/compose.yml deploy/env/defaults.env
GUARDS[":modules:visualization"]+=" co.datapipelines.visualization.VisualizationConfigKeysSpecDriftTest"  # inventory 995: reads docs/configuration.md modules/app/src/main/kotlin/co/datapipelines/config/VisualizationRules.kt
GUARDS[":modules:visualization"]+=" co.datapipelines.visualization.VisualizationEnumsSpecDriftTest"  # inventory 995: reads docs/enums.md
GUARDS[":modules:visualization"]+=" co.datapipelines.visualization.VisualizationErrorCodesSpecDriftTest"  # inventory 995: reads docs/pipeline-contract.md
GUARDS[":modules:web"]+=" co.datapipelines.web.TestRedisDefaultsTest"  # inventory 995: reads docs/configuration.md modules
GUARDS[":modules:web"]+=" co.datapipelines.web.api.AuthorizationVocabularyGuardTest"  # inventory 995: walks the tree (git ls-files)
GUARDS[":modules:web"]+=" co.datapipelines.web.bootstrap.ExampleContentSeederTest"  # inventory 995: reads scripts/sample-data-trade/content/examples.json scripts/sample-data/content/examples-lake.json
GUARDS[":modules:web"]+=" co.datapipelines.web.config.DeploymentNameBranchingGuardTest"  # inventory 995: reads modules
GUARDS[":modules:web"]+=" co.datapipelines.web.config.ReleaseDependencyConfigKeysSpecDriftTest"  # inventory 995: reads docs/configuration.md modules/app/src/main/resources/application.yml
GUARDS[":modules:web"]+=" co.datapipelines.web.config.ShutdownGraceArithmeticTest"  # inventory 995: reads deploy/compose.yml deploy/helm/datapipelines/templates/deployment.yaml
GUARDS[":modules:web"]+=" co.datapipelines.web.config.WebPropertiesSpecDriftTest"  # inventory 995: reads docs/configuration.md
GUARDS[":modules:web"]+=" co.datapipelines.web.dashboards.runtime.DashboardAuditEventsSpecDriftTest"  # inventory 995: reads docs/enums.md
GUARDS[":modules:web"]+=" co.datapipelines.web.dependencies.DependencyFamilyTransportTest"  # inventory 995: reads docs/pipeline-contract.md
GUARDS[":modules:web"]+=" co.datapipelines.web.dependencies.DependencyTreeProjectionTest"  # inventory 995: reads modules/dependency-contract/src/main/resources${}
GUARDS[":modules:web"]+=" co.datapipelines.web.endpoints.EndpointAuditEventsSpecDriftTest"  # inventory 995: reads docs/enums.md
GUARDS[":modules:web"]+=" co.datapipelines.web.parameters.ParameterSetAuditEventsSpecDriftTest"  # inventory 995: reads docs/enums.md
GUARDS[":modules:web"]+=" co.datapipelines.web.pipelines.EndpointPromotionImportReachabilityTest"  # inventory 995: walks the tree (git ls-files)
GUARDS[":modules:web"]+=" co.datapipelines.web.release.ReleaseCodeSwitchGuardTest"  # inventory 995: walks the tree (git ls-files)
GUARDS[":modules:web"]+=" co.datapipelines.web.schedules.ScheduleAuditEventsSpecDriftTest"  # inventory 995: reads docs/enums.md
GUARDS[":modules:web"]+=" co.datapipelines.web.ui.DocsContentVisibilitySuspensionDriftTest"  # inventory 995: reads scripts/site-lighthouse.sh
GUARDS[":modules:web"]+=" co.datapipelines.web.ui.DocsSlugAlgorithmTest"  # inventory 995: reads docs
GUARDS[":modules:web"]+=" co.datapipelines.web.ui.InlineWidthAuditTest"  # inventory 995: reads ../../modules
GUARDS[":modules:web"]+=" co.datapipelines.web.ui.NativeDialogBanTest"  # inventory 995: reads tests/browser-tests/src
GUARDS[":modules:web"]+=" co.datapipelines.web.ui.ScriptVocabularyGuardTest"  # inventory 995: walks the tree (git ls-files)
GUARDS[":modules:web"]+=" co.datapipelines.web.ui.ChartInteractionsLoadOrderTest"  # walks the tree (git ls-files)
GUARDS[":modules:web"]+=" co.datapipelines.web.ui.UiScreensTransportVocabularyTest"  # inventory 995: reads docs/ui-screens.md
GUARDS[":modules:web"]+=" co.datapipelines.web.ui.WebsiteFactsGuardTest"  # inventory 995: reads scripts/sample-data-trade/content/examples.json scripts/sample-data/content/examples-lake.json
GUARDS[":modules:web"]+=" co.datapipelines.web.ui.site.SiteDemoDataGuardsTest"  # inventory 995: reads deploy/env/defaults.env
GUARDS[":modules:web"]+=" co.datapipelines.web.ui.site.SiteDuckDbVersionTest"  # inventory 995: reads gradle/libs.versions.toml
GUARDS[":modules:web"]+=" co.datapipelines.web.ui.site.SiteSemanticLayerGuardsTest"  # inventory 995: reads docs/enums.md
GUARDS[":modules:web"]+=" co.datapipelines.web.visualizations.VisualizationAuditEventsSpecDriftTest"  # inventory 995: reads docs/enums.md
GUARDS[":tests:integration-tests"]+=" co.datapipelines.integration.PackagedResolverTest"  # inventory 995: reads modules/app/build/libs/datapipelines-app.jar modules/auth/src/main
GUARDS[":modules:datasources"]+=" co.datapipelines.datasources.BootstrapDatasourceFileReaderTest"  # inventory 995: reads deploy/sample-data/bootstrap-datasources-census.yml deploy/sample-data/bootstrap-datasources-lake.yml
GUARDS[":modules:datasources"]+=" co.datapipelines.datasources.DatasourceAuditEventsSpecDriftTest"  # inventory 995: reads docs/enums.md
GUARDS[":modules:datasources"]+=" co.datapipelines.datasources.DatasourceErrorCodesSpecDriftTest"  # inventory 995: reads docs/pipeline-contract.md
GUARDS[":modules:datasources"]+=" co.datapipelines.datasources.ManagedLakeErrorCodesSpecDriftTest"  # inventory 995: reads docs/pipeline-contract.md
GUARDS[":modules:datasources"]+=" co.datapipelines.datasources.ReadOnlyEnforcementSpecDriftTest"  # inventory 995: reads docs/dag-executor.md
GUARDS[":modules:datasources"]+=" co.datapipelines.datasources.LocalWriteQualificationSpecDriftTest"  # 1036: reads docs/dag-executor.md
GUARDS[":modules:dag"]="co.datapipelines.executor.StaleSweepCommitEvidenceIntegrationTest"  # reads the Flyway migrations under modules/app (1035 r2): the sweep classifies on the real schema
GUARDS[":modules:datasources"]+=" co.datapipelines.datasources.ShippedMigrationsTest"  # inventory 995: reads README.md modules/app/src/main/resources/db/migration
GUARDS[":modules:datasources"]+=" co.datapipelines.datasources.semantics.LearnedFactKindSpecDriftTest"  # inventory 995: reads docs/enums.md modules/app/src/main/resources/db/migration/V25__learned_facts.sql
GUARDS[":modules:datasources"]+=" co.datapipelines.datasources.semantics.SemanticsErrorCodesSpecDriftTest"  # inventory 995: reads docs/pipeline-contract.md
GUARDS[":modules:pipeline-contract"]+=" co.datapipelines.pipeline.CompositionRulesReplayTest"  # inventory 995: reads scripts/sample-data/content/examples-lake.json scripts/sample-data/content/examples.json
GUARDS[":modules:pipeline-contract"]+=" co.datapipelines.pipeline.DemoPipelineHashNeutralityTest"  # inventory 995: reads config scripts/sample-data/content/examples-lake.json
GUARDS[":modules:pipeline-contract"]+=" co.datapipelines.pipeline.NodeTypeSpecDriftTest"  # inventory 995: reads docs/enums.md
GUARDS[":modules:pipeline-contract"]+=" co.datapipelines.pipeline.PipelineNameGrammarSpecDriftTest"  # inventory 995: reads docs/template-hierarchy-design.md
GUARDS[":modules:pipeline-contract"]+=" co.datapipelines.pipeline.VersioningSpecDriftTest"  # inventory 995: reads docs/versioning.md
GUARDS[":modules:staging"]="co.datapipelines.staging.StagingErrorCodesSpecDriftTest"  # inventory 995: reads docs/pipeline-contract.md
GUARDS[":modules:templates"]="co.datapipelines.templates.ForbiddenConstructSpecDriftTest"  # inventory 995: reads docs/templates.md
GUARDS[":modules:templates"]+=" co.datapipelines.templates.SampleDataExamplesContentTest"  # inventory 995: reads deploy/sample-data/bootstrap-datasources-census.yml deploy/sample-data/bootstrap-datasources-lake.yml
GUARDS[":modules:templates"]+=" co.datapipelines.templates.TemplateErrorCodesSpecDriftTest"  # inventory 995: reads docs/pipeline-contract.md
GUARDS[":modules:templates"]+=" co.datapipelines.templates.TemplateNameGrammarSpecDriftTest"  # inventory 995: reads docs/template-hierarchy-design.md
GUARDS[":modules:templates"]+=" co.datapipelines.templates.TemplatesPropertiesSpecDriftTest"  # inventory 995: reads docs/configuration.md
GUARDS[":modules:typesystem"]="co.datapipelines.typesystem.ColumnSchemaSpecDriftTest"  # inventory 995: reads docs/type-system.md
GUARDS[":modules:typesystem"]+=" co.datapipelines.typesystem.StagingEngineSpecDriftTest"  # inventory 995: reads docs/enums.md
GUARDS[":modules:web"]+=" co.datapipelines.web.endpoints.RetiredApiPrefixGuardTest"  # inventory 995: reads deploy docs
GUARDS[":modules:web"]+=" co.datapipelines.web.ui.SiteRenderTest"  # inventory 995: reads docs docs/[A-Za-z0-9._/-]+\.md
GUARDS[":modules:web"]+=" co.datapipelines.web.ui.site.SiteEngineFactsGuardTest"  # inventory 995: reads docs docs/$doc
GUARDS[":modules:web"]+=" co.datapipelines.web.ui.site.SiteFaqCitationTest"  # inventory 995: reads docs docs/[A-Za-z0-9._/-]+\.md
GUARDS[":tests:integration-tests"]+=" co.datapipelines.integration.OrderedTestAnnotationGuardTest"  # inventory 995: reads modules modules/pipeline-contract
GUARDS[":tests:integration-tests"]+=" co.datapipelines.integration.ScratchDatabaseOnceGuardTest"  # inventory 995: reads modules modules/pipeline-contract
GUARDS[":tests:integration-tests"]+=" co.datapipelines.integration.PublishedMethodDowngradeBarrierIntegrationTest"  # #1037: reads docs/metadata-db.md modules/app/src/main/resources/db/migration modules/app/src/main/resources/db/downgrade-barrier
# buildSrc (#1004, owner ruling 2026-11-15): each buildSrc test class that reads the repository
# beyond buildSrc and starts no nested build runs in EVERY lane check. Found by reading each class
# (lane 1004's evidence, A.1) and by PregateGuardInventoryTest, which scans buildSrc's tests too.
# A buildSrc class that starts a nested build (GradleRunner) carries a waived-for-cost line in
# scripts/pregate-guard-exemptions.txt instead: it runs here only in stage 2's unfiltered buildSrc
# run, and always in the gate and CI. buildSrc's classes live in the default package.
GUARDS[":buildSrc"]="RepoTestInputsTest RawListAppenderGuardTest ThirdPartyNoticesTest"  # reads .gitignore, the modules' and integration tests' test sources, Dockerfile, deploy/duckdb-extensions.sha256, deploy/duckdb-extension-ports, gradle/libs.versions.toml

# Only runtime retained evidence can populate PG_COVERED; preview passes none.
pgplan::guard_args() {
  PG_3=(); PG_ROOTS3=()
  local m c
  mapfile -t PG_GUARD_MODULES < <(printf '%s\n' "${!GUARDS[@]}" | LC_ALL=C sort)
  for m in "${PG_GUARD_MODULES[@]}"; do
    [ -z "${PG_COVERED[$m]+x}" ] || continue
    if [ "$m" = :buildSrc ]; then
      # Its own command shape (#1004): cleanTest as in the gate, and no verifyTestsExecuted,
      # which buildSrc does not have.
      PG_3+=(:buildSrc:cleanTest :buildSrc:test)
      for c in ${GUARDS[$m]}; do PG_3+=(--tests "$c"); done
      PG_ROOTS3+=(buildSrc)
      continue
    fi
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
    0) dest=("${PG_0[@]}"); return 0 ;; # not Gradle: no PG_COMMON
    1) dest=("${PG_1[@]}") ;; 2) dest=("${PG_2[@]}") ;;
    2b) dest=("${PG_2B[@]}") ;; 3) dest=("${PG_3[@]}") ;;
    4) dest=("${PG_4[@]}") ;; *) return 2 ;;
  esac
  [ "${#dest[@]}" -eq 0 ] || dest+=("${PG_COMMON[@]}")
}
pgplan::command() {
  local stage="$1"; shift
  printf 'stage=%s command=' "$stage"
  if [ "$#" -eq 0 ]; then printf '(none)'
  elif [ "$stage" = 0 ]; then printf '%q ' "$@"
  else printf '%q ' ./gradlew "$@"; fi
  printf '\n'
}
# The buildSrc decision (#1004), one line, shared by --plan and the run's PLAN.txt.
pgplan::buildsrc_decision() {
  if [ "${#PG_BUILDSRC_FILES[@]}" -eq 0 ]; then
    printf 'buildsrc-tests: always-run classes only (stage 3) — no buildSrc/, gradle/libs.versions.toml or gradle/verification-metadata.xml change\n'
  else
    local joined; joined="$(printf '%s, ' "${PG_BUILDSRC_FILES[@]}")"
    printf 'buildsrc-tests: full (stage 2, unfiltered) — %s\n' "${joined%, }"
  fi
}
# The stage-0 trigger decision, one line, shared by --plan and the run's PLAN.txt.
pgplan::deps_decision() {
  if [ "${#PG_DEPS_FILES[@]}" -eq 0 ]; then
    printf 'deps-scan: skip — no dependency file changed (gradle.lockfile, gradle/libs.versions.toml, gradle/verification-metadata.xml)\n'
  else
    local joined; joined="$(printf '%s, ' "${PG_DEPS_FILES[@]}")"
    printf 'deps-scan: run — %s\n' "${joined%, }"
  fi
}
pgplan::print() {
  printf 'base=%s%s resolved-base=%s merge-base=%s head=%s\n' "$PG_BASE" "${PG_BASE_SOURCE:+ ($PG_BASE_SOURCE)}" "$PG_BASE_SHA" "$PG_MB" "$PG_HEAD"
  printf 'changed files (selected base plus staged/unstaged/untracked):\n%s\n' "${PG_FILES:-none}"
  printf 'current-main comparison=%s\nselected-base-only:\n%s\ncurrent-main-only:\n%s\n' "$PG_MAIN_DIFF" "${PG_BASE_ONLY:-none}" "${PG_MAIN_ONLY:-none}"
  printf 'touched modules: %s\n' "${PG_MODULES[*]:-none}"
  local m stage; local -a args=()
  for m in "${PG_TEST_MODULES[@]}"; do printf 'selection tests/%s:\n%s\n' "$m" "${PG_SELECTIONS[$m]}"; done
  printf 'deferred=%s\n' "${PG_DEFERRED[*]:-none}"
  printf 'guards (omission conditional on successful fresh unfiltered stage-2 retained XML):\n'
  for m in "${PG_GUARD_MODULES[@]}"; do printf '%s %s\n' "$m" "${GUARDS[$m]}"; done
  pgplan::deps_decision
  pgplan::buildsrc_decision
  printf 'stage order: %s\n' "${PG_STAGES[*]}"
  for stage in "${PG_STAGES[@]}"; do pgplan::args "$stage" args; pgplan::command "$stage" "${args[@]}"; done
}
