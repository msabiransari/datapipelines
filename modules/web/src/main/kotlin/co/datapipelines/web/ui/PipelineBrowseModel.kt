package co.datapipelines.web.ui

import co.datapipelines.application.endpoints.PublishedEndpointRepository
import co.datapipelines.application.lens.LensedView
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.pipeline.DatasourceRegistry
import co.datapipelines.pipeline.LifecycleBucket
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeSource
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineDeserializer
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineVersionRecord
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.SearchNeedle
import co.datapipelines.pipeline.through
import co.datapipelines.web.schedules.PipelineJobExecutor
import co.datapipelines.web.schedules.PrincipalTargetViewer
import org.springframework.ui.Model
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * The pipelines explorer's model, in one place for the page controller and the partial
 * controller (067; template-hierarchy-design §9.2, which this follows deliberately rather
 * than re-deciding).
 *
 * `/pipelines` is the flat **catalog** (#350, owner ruling 2026-10-02): a list of full paths
 * under `#pipeline-list-wrapper`, every pipeline when `q` is empty, the matches otherwise — not
 * a tree pruned to matching leaves, because pruning means walking the ancestors of every match
 * (§9.2, decided for templates in 047 and inherited here). There is no tree on the page and no
 * detail pane: every row is a link to the canonical workspace `/pipelines/{id}`. The folder tree
 * is the global sidebar's, read from `/api/v1/pipelines/tree` since #460; its server-rendered
 * levels (`scope=nav`, `prefix`) are gone with #466.
 *
 * The list swaps one stable root with `outerHTML` and renders the shared `partials/pager` —
 * the SPA contract this screen has had since 028 (ui-screens.md §4.3).
 *
 * Nothing here creates, renames, moves or deletes a folder, and nothing can: a folder is a
 * name prefix with no identity (§3.1), derived per request from the live rows beneath it and
 * gone with them. An empty folder is unrepresentable rather than merely unrendered.
 *
 * The list goes through [PipelineService.page] rather than a query of its own — the D2 rule
 * that made one component answer the REST list, the MCP list, this screen and its partial.
 *
 * Declared as an explicit `@Bean` in [UiConfig], not by a stereotype: the house rule is zero
 * DI stereotypes in production code with **no** allowlist (015 / module-structure §8.4), and
 * `ArchitectureGuardTest` enforces it.
 */
@Suppress("LongParameterList") // one collaborator per fact the screen shows; the constructor is the container's business
class PipelineBrowseModel(
    private val pipelines: PipelineService,
    private val repository: PipelineRepository,
    private val executions: ExecutionRepository,
    private val endpoints: PublishedEndpointRepository,
    private val datasources: DatasourceRegistry,
    private val actors: ActorNames,
    private val runStats: PipelineRunStats,
    /** #259 — the Usage tab's Schedules list, the scheduler's one transport-facing type. */
    private val schedules: co.datapipelines.scheduler.ScheduleService,
    /** #320 — the dashboards whose sources pin a version of the pipeline: the port `PipelineService.refuseIfPinned` asks. */
    private val dashboards: co.datapipelines.pipeline.PipelineVersionConsumers,
) {
    private val deserializer = PipelineDeserializer()

    /**
     * Fills [model] for the catalog's **flat list of full paths**, paged by the shared pager
     * against [CATALOG_ROOT_ID] (§9.2): the matches of `q`, or — `q` absent — every pipeline the
     * caller may read ([PipelineService.page]'s own null-query page, the same lens and the same
     * page size). #556: a [lifecycle] keeps one inventory bucket, decided in the page's SQL; the
     * fragment renders it back into the catalog's control and carries it on the pager.
     */
    fun fillSearch(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        q: String?,
        offset: Int,
        lifecycle: LifecycleBucket? = null,
    ): String {
        val page = maxOf(0, offset)
        // The trim is the model's, in one place (#504b, the ParameterSetsBrowseModel shape): a
        // blank `q` is no search, and what renders back into the box is the trimmed needle. The
        // service still owns the refusal — a box only truncates, so a typed overflow never errors.
        val needle = q?.trim()?.takeIf { it.isNotEmpty() }?.take(SearchNeedle.MAX_LENGTH)
        val result = pipelines.page(workspaceId, view.pipelines, needle, page, PAGE_SIZE, lifecycle)
        model.addAttribute(LENS_UNAVAILABLE, view.unavailable)
        model.addAttribute("lifecycle", lifecycle?.wire.orEmpty())
        model.addAttribute("lifecycleLabel", lifecycle?.let(CatalogLifecycle::label))
        model.addAttribute("lifecycleOptions", CatalogLifecycle.options(CatalogLifecycle.PIPELINES))
        model.addAttribute("searching", true)
        model.addAttribute("rootId", CATALOG_ROOT_ID)
        model.addAttribute("q", needle.orEmpty())
        model.addAttribute("pipelines", result.items)
        model.addAttribute("drafts", result.drafts)
        model.addAttribute("offset", page)
        model.addAttribute("hasMore", result.hasMore)
        model.addAttribute("total", result.total)
        return SEARCH_VIEW
    }

    /**
     * Fills [model] for the catalog's list ([fillSearch]) and returns the catalog's swap-root
     * view, `partials/pipelines`, whose one root element is [CATALOG_ROOT_ID].
     */
    fun fillWrapper(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        q: String?,
        offset: Int,
        lifecycle: LifecycleBucket? = null,
    ): String {
        fillSearch(model, workspaceId, view, q, offset, lifecycle)
        return WRAPPER_VIEW
    }

    /**
     * #349 — the pipeline workspace's composition facts, filled for the canonical page's
     * Versions tab and Overview pane in ONE call from [PipelineWorkspaceController].
     *
     * The Versions tab composes `partials/pipeline-versions :: versions` — the fragment the
     * explorer's detail pane rendered until #401 removed the pane, so the model carries the
     * SAME row shapes that pane filled ([versionRows]) — visibility, per-row verbs and run
     * counts are the unchanged contract — plus the viewed-version mark the workspace adds to
     * every row. The
     * returned facts are the Overview's record-level and registry-resolved halves
     * ([WorkspaceTabFacts]): who created the pipeline and on what surface, its last visible
     * run, and the datasource→dialect map across EVERY ADMITTED version's body — the
     * in-page version switch (a client-side fetch of another admitted body) must be able to
     * relabel the new body's datasources without a second round trip, and the lens decides
     * admission per body exactly as the page's own resolution did.
     *
     * Everything here is a read; nothing here changes a verb, a flag or a visibility rule the
     * pane didn't already state.
     */
    fun fillWorkspaceTabs(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        record: PipelineRecord,
        versions: List<PipelineVersionRecord>,
        viewedVersion: Int?,
        principal: AuthenticatedPrincipal,
    ): WorkspaceTabFacts {
        model.addAttribute("pipeline", record)
        model.addAttribute("versions", versionRows(record, versions, viewedVersion))
        // #395 — the {D} shape (one version, a draft) is the entity purge's, exactly the
        // workspace header's `canDelete` rule: the workspace header offers Purge pipeline… (the
        // entity dialog — exclusive draft templates, #335's kept list) in place of Purge draft….
        model.addAttribute("canDelete", versions.size == 1 && versions.single().status == PipelineVersionStatus.DRAFT)
        return WorkspaceTabFacts(
            createdBy = actorName(record.ownerId),
            createdVia = versions.minByOrNull { it.version }?.createdVia,
            lastRun = lastRunView(workspaceId, record.id, principal),
            datasourceDialects = workspaceDialects(workspaceId, view, record, versions),
        )
    }

    /** The version rows the Versions tab renders — one mapping, shared since the pane. */
    private fun versionRows(
        record: PipelineRecord,
        versions: List<PipelineVersionRecord>,
        viewedVersion: Int? = null,
    ): List<VersionRowView> {
        val runs = runStats.runsByVersion(record.id)
        val names = actors.lookup(versions.map { it.createdBy })
        val now = Instant.now()
        return versions.map { v ->
            VersionRowView.of(
                version = v.version,
                status = v.status,
                createdAt = v.createdAt,
                actor = names[v.createdBy] ?: ActorNames.fallback(v.createdBy),
                now = now,
                usage = runs[v.version] ?: 0,
                usageUnit = "run",
                isCurrent = record.currentVersion == v.version,
                // The chip's fact (V20): a draft row shows its last write's surface.
                via = if (v.status == PipelineVersionStatus.DRAFT) v.updatedVia else v.createdVia,
                isViewed = viewedVersion != null && v.version == viewedVersion,
            )
        }
    }

    /**
     * The datasource names ONE body touches — the registry decides what is a datasource
     * (`tempdb` is the reserved literal and never a registered name, §4.8), extracted once so
     * the explorer's working-body rows and the workspace's all-versions dialect map read the
     * same rule.
     */
    private fun datasourceNames(body: Pipeline): List<String> =
        body.nodes
            .flatMap { node ->
                listOfNotNull(
                    (NodeSource.from(node.source) as? NodeSource.Datasource)?.name,
                    (node.output as? NodeOutput.Datasource)?.datasource,
                )
            }.distinct()
            .sorted()

    /**
     * Fills [model] for the Runs tab — this pipeline's last [RUNS_LIMIT] executions.
     *
     * Visibility follows the execution history screen ([ExecutionHistoryPartialController])
     * through [listVisibleTo] (#275): an admin sees the workspace's runs, a member with
     * `execution.read` her own plus the workspace's SCHEDULED runs (#9 R3), and a role without
     * it — the promoter, who reaches this `pipeline.read` pane — her own only. A second surface
     * over the same rows must not be a wider one.
     */
    fun fillRuns(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        pipelineId: UUID,
        principal: AuthenticatedPrincipal,
    ): String {
        val rows =
            // 178: a pipeline the lens hides has no runs to show — the executions are the
            // pipeline's, and the pane must answer for it exactly as for an absent id.
            if (pipelines.findRecord(workspaceId, view.pipelines, pipelineId) == null) {
                emptyList()
            } else {
                executions.listVisibleTo(principal, workspaceId, pipelineId, status = null, limit = RUNS_LIMIT)
            }
        model.addAttribute("runs", rows)
        model.addAttribute("runActors", actors.lookup(rows.map { it.executedBy }))
        val now = Instant.now()
        model.addAttribute("runAgo", rows.associate { it.executionId to RelativeTime.since(it.startedAt, now) })
        return RUNS_VIEW
    }

    /**
     * Fills [model] for the Usage tab — 101's discard evidence, read before the refusal, plus
     * #259's Schedules: the live schedules whose target names the pipeline, which a discard
     * does NOT refuse but which then block (`pointer_null` / `target_not_found`) at their next
     * occurrence — the consequence a reader is deciding about.
     */
    fun fillUsage(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        pipelineId: UUID,
        principal: AuthenticatedPrincipal,
    ): String {
        val record = pipelines.findRecord(workspaceId, view.pipelines, pipelineId)
        model.addAttribute(
            "usage",
            record?.let { usage(workspaceId, view, it, principal) } ?: UsageView(emptyList(), emptyList()),
        )
        return USAGE_VIEW
    }

    /**
     * What the server would refuse a discard over, and — for the tab, not the badge — the
     * schedules that run the pipeline.
     *
     * The parent half is [PipelineRepository.findLiveParentsPinningVersion] — the SAME query
     * `PipelineService.refuseIfPinned` runs — asked once per version this pipeline has, so the
     * tab's list and the refusal's `pinned_by` detail cannot disagree. The endpoint half is the
     * published-endpoints registry, which pins a pipeline and not a version (§5.1). The schedule
     * half is the scheduler's by-target read, LENSED like every schedule list (§20.1): a schedule
     * whose target the lens hides answers as absent. Schedules carry [UsageView.total] NO — the
     * badge counts refusal evidence, and a schedule is not that — so the detail badge's call
     * passes no principal and reads none.
     */
    private fun usage(
        workspaceId: UUID,
        view: LensedView,
        record: PipelineRecord,
        principal: AuthenticatedPrincipal? = null,
    ): UsageView {
        val versions = repository.listVersions(workspaceId, record.id)
        val parents =
            versions
                .flatMap { repository.findLiveParentsPinningVersion(workspaceId, record.name, it.version) }
                // 178: a hidden parent must not leak through the reverse arrow. Both halves of the lens apply (#340): the
                // NAME, and under a narrowing lens RELEASED parent versions only — a draft's number never reaches a promoter.
                .through(view.pipelines) { it.pipelineName }
                .filter { !view.isLensed || it.versionStatus == PipelineVersionStatus.RELEASED }
                .map { UsageView.ParentUse(it.pipelineId, it.pipelineName, it.pipelineVersion, it.nodeId, it.pinnedVersion) }
        val served =
            endpoints
                .findByPipeline(record.id)
                .map { UsageView.EndpointUse(it.pathPattern, it.isEnabled, it.description) }
        val runsOnIt = runSchedules(workspaceId, record, principal)
        // #320: the dashboards whose sources pin a version — the SAME question `PipelineService.refuseIfPinned` asks per
        // version (`pipeline.version.pinned`'s `referencing_dashboards`), through the dashboard lens (a hidden dashboard
        // must not leak through the reverse arrow either). Both halves of the lens apply: the NAME, and under a narrowing
        // lens RELEASED rows only — a draft's number and status never reach a promoter (178b; the 320 security pass, F1).
        val dashboardUses =
            versions
                .flatMap { v -> dashboards.liveVersionPins(workspaceId, record.name, v.version).map { it to v.version } }
                .through(view.dashboards) { (pin, _) -> pin.name }
                .filter { (pin, _) -> view.dashboards.isEverything || pin.status == PipelineVersionStatus.RELEASED }
                .map { (pin, pinned) -> UsageView.DashboardUse(pin.name, pin.version, pin.status.name, pinned) }
        return UsageView(endpoints = served, parents = parents, schedules = runsOnIt, dashboards = dashboardUses)
    }

    /** The schedule half of the Usage read — empty when no principal is supplied (the badge). */
    private fun runSchedules(
        workspaceId: UUID,
        record: PipelineRecord,
        principal: AuthenticatedPrincipal?,
    ): List<UsageView.ScheduleUse> {
        if (principal == null) return emptyList()
        val targetRef = PipelineJobExecutor.TARGET_PREFIX + record.name
        return schedules
            .listByTarget(workspaceId, targetRef, PrincipalTargetViewer(principal))
            .map { UsageView.ScheduleUse(it.id, it.name, scheduleState(it)) }
    }

    /** The schedule's operational state, in the tab's vocabulary: enabled, paused or blocked. */
    private fun scheduleState(schedule: co.datapipelines.scheduler.Schedule): String =
        when {
            schedule.blockedReason != null -> "blocked"
            !schedule.enabled -> "paused"
            else -> "enabled"
        }

    private fun lastRun(
        workspaceId: UUID,
        pipelineId: UUID,
        principal: AuthenticatedPrincipal,
    ) = executions.listVisibleTo(principal, workspaceId, pipelineId, status = null, limit = 1).firstOrNull()

    /** The Overview's last-run line, as the workspace's JSON facts carry it (#349). */
    private fun lastRunView(
        workspaceId: UUID,
        pipelineId: UUID,
        principal: AuthenticatedPrincipal,
    ): WorkspaceLastRunView? =
        lastRun(workspaceId, pipelineId, principal)?.let { last ->
            WorkspaceLastRunView(
                executionId = last.executionId,
                status = last.status.name,
                durationMs = last.durationMs,
                rowCount = last.resultRowCount?.toInt(),
                ago = RelativeTime.since(last.startedAt, Instant.now()),
                at = LAST_RUN_FORMAT.withZone(ZoneId.systemDefault()).format(last.startedAt),
                by = actorName(last.executedBy),
            )
        }

    /**
     * The datasource→dialect map across every ADMITTED version's body (#349): the in-page
     * version switch fetches another admitted body client-side and must relabel its
     * datasources without a second round trip. Admission is the lens's own — a body the
     * caller cannot read contributes nothing — and a name the registry cannot resolve from
     * this workspace is left out, exactly as the detail pane's old datasource rows left it out.
     */
    private fun workspaceDialects(
        workspaceId: UUID,
        view: LensedView,
        record: PipelineRecord,
        versions: List<PipelineVersionRecord>,
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        versions.forEach { v ->
            admittedBodyNames(workspaceId, view, record, v).forEach { name -> rememberDialect(out, workspaceId, name) }
        }
        return out
    }

    /** The datasource names ONE admitted version's body touches, or nothing when the
     *  body is hidden (the lens), absent, or unparsable. */
    private fun admittedBodyNames(
        workspaceId: UUID,
        view: LensedView,
        record: PipelineRecord,
        version: PipelineVersionRecord,
    ): List<String> {
        val bodyJson =
            pipelines.findVersionBody(workspaceId, view.pipelines, record.id, version.version) ?: return emptyList()
        val body = runCatching { deserializer.readOrThrow(bodyJson) }.getOrNull() ?: return emptyList()
        return datasourceNames(body)
    }

    private fun rememberDialect(
        out: LinkedHashMap<String, String>,
        workspaceId: UUID,
        name: String,
    ) {
        if (!out.containsKey(name)) {
            datasources.describe(name, workspaceId)?.let { out[name] = it.dialect.name }
        }
    }

    private fun actorName(actor: UUID): String = actors.lookup(listOf(actor))[actor] ?: ActorNames.fallback(actor)

    companion object {
        /** The pipelines screen's page size — the value the flat list has always used. */
        const val PAGE_SIZE = 25

        /**
         * 178 — the model attribute the list fragments read: non-null (a `LensedView.Unavailable`)
         * when a lensed principal's list is empty BECAUSE the higher environment could not be
         * read, so the fragment renders the promotion page's sentence in place of its ordinary
         * empty state. Every other principal, and a lensed one with a readable target, gets
         * null and the ordinary state. Shared with the templates browser by name.
         */
        const val LENS_UNAVAILABLE = "lensUnavailable"

        /** #350 — the `/pipelines` catalog's stable swap root: its search and its pager target it. */
        const val CATALOG_ROOT_ID = "pipeline-list-wrapper"

        const val WRAPPER_VIEW = "partials/pipelines"
        const val SEARCH_VIEW = "partials/pipeline-search"
        const val RUNS_VIEW = "partials/pipeline-runs"
        const val USAGE_VIEW = "partials/pipeline-usage"

        /** The Runs tab's ceiling (106) — "the last 20", never the whole history. */
        const val RUNS_LIMIT = 20

        /** The last-run line's exact stamp — the explorer's own `yyyy-MM-dd HH:mm` reading. */
        private val LAST_RUN_FORMAT =
            java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm")
    }
}
