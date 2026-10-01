package co.datapipelines.web.ui

import co.datapipelines.application.endpoints.PublishedEndpointRepository
import co.datapipelines.application.lens.LensedView
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.pipeline.DatasourceRegistry
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeSource
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineDeserializer
import co.datapipelines.pipeline.PipelineFolder
import co.datapipelines.pipeline.PipelineFolderLevel
import co.datapipelines.pipeline.PipelineNameGrammar
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineVersionRecord
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.through
import co.datapipelines.web.schedules.PipelineJobExecutor
import co.datapipelines.web.schedules.PrincipalTargetViewer
import org.springframework.ui.Model
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * The pipelines explorer's model, in one place for the page controller and the partial
 * controller (067; template-hierarchy-design §9.2, which this follows deliberately rather
 * than re-deciding).
 *
 * The screen has **two presentations and one contract**. Browsing renders a tree, one level
 * per request, each level a server-side prefix query (§9.1: no client-side tree assembly, at
 * any size). A non-empty search shows a flat list of full paths — not a tree pruned to
 * matching leaves, because pruning means walking the ancestors of every match, which is
 * exactly the whole-list-in-the-browser work the tree exists to avoid (§9.2, decided for
 * templates in 047 and inherited here).
 *
 * Both presentations swap the same stable root `#pipeline-list-wrapper` with `outerHTML`, and
 * both render the shared `partials/pager` — the SPA contract this screen has had since 028
 * (ui-screens.md §4.3) carries over unchanged, because this is a new fragment shape on an
 * existing surface, not a new surface.
 *
 * Nothing here creates, renames, moves or deletes a folder, and nothing can: a folder is a
 * name prefix with no identity (§3.1), derived per request from the live rows beneath it and
 * gone with them. An empty folder is unrepresentable rather than merely unrendered.
 *
 * The search half goes through [PipelineService.page] rather than a query of its own — the D2
 * rule that made one component answer the REST list, the MCP list, this screen and its
 * partial. The browse half is [PipelineRepository.listFolder], which has no service-level
 * sibling to duplicate.
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
    private val authoring: co.datapipelines.pipeline.AuthoringGuard,
    /** #259 — the Usage tab's Schedules list, the scheduler's one transport-facing type. */
    private val schedules: co.datapipelines.scheduler.ScheduleService,
    /** #320 — the dashboards whose sources pin a version of the pipeline: the port `PipelineService.refuseIfPinned` asks. */
    private val dashboards: co.datapipelines.pipeline.PipelineVersionConsumers,
) {
    private val deserializer = PipelineDeserializer()

    /**
     * Fills [model] for one **tree level** — [prefix] `null`/empty is the root — and returns
     * the view name to render.
     *
     * The level's own leaves are paged with the shared pager against the level's own id, so
     * every level pages the same way and no level silently truncates. Its sub-folders are not
     * paged: they are a `GROUP BY` over one path segment, capped with an honest overflow flag
     * rather than a silent cut.
     *
     * **The ROOT level renders no leaves** (§4.1, 077) — see the comment on the call below for
     * why that is a rendering rule here and a data guarantee on the templates side.
     */
    fun fillLevel(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        prefix: String?,
        offset: Int,
    ): String {
        val page = maxOf(0, offset)
        // A prefix is user input that becomes a LIKE pattern. It is bound and escaped in the
        // repository, so nothing can be injected — but a value that is not a legal pipeline
        // name cannot name a real folder either, and letting an arbitrary-length string
        // through would turn a level request into an arbitrary-length pattern match. The
        // grammar it is checked against is the SERVER's own, not a second copy.
        //
        // An illegal prefix renders an ordinary EMPTY level, never an error: the templates
        // browser settled that (`TemplateBrowseModel.fillLevel`), a level that cannot exist is
        // not a client fault worth a 400, and two sibling explorers answering the same input
        // differently would be the surprise.
        if (!prefix.isNullOrEmpty() && !PipelineNameGrammar.matchesPrefix(prefix)) {
            return emptyLevel(model, prefix)
        }
        // "" and absent are the SAME level — the root — so they normalize to one repository
        // call rather than two shapes of the same query. The model keeps the caller's `""`,
        // because that is what the fragment renders its own prefix as.
        // Through the service since 178, so the promoter lens (`view.pipelines`) narrows the
        // level and its counts the way it narrows every other read; `Everything` is the same
        // SQL level this read went to the repository for before.
        val level = pipelines.browseLevel(workspaceId, view.pipelines, prefix?.takeIf { it.isNotEmpty() }, offset = page, limit = PAGE_SIZE)
        // THE ROOT HOLDS FOLDERS ONLY (§4.1, 077). A pipeline name needs a folder, so the root
        // level renders sub-folders and nothing else, and the fragment's "leaves at the root"
        // branch is gone.
        //
        // Unlike templates there is no deploy gate here, deliberately (§14.2): a pipeline name
        // is validated at SAVE only, so a pre-077 flat row still exists, still opens and still
        // executes. Dropping it from the ROOT LEVEL is a rendering decision about a tree whose
        // root is now a directory of folders — it is not a disappearance. That row is still
        // returned by search (`q`, a flat list of full paths), by `pipelines_list`, and by its
        // own UUID URL, which is how it is opened and run.
        val rendered = if (prefix.isNullOrEmpty()) level.withoutLeaves() else level
        fillLevelAttributes(model, workspaceId, view.pipelines, prefix, page, rendered)
        model.addAttribute(LENS_UNAVAILABLE, view.unavailable)
        return LEVEL_VIEW
    }

    /** A level that cannot exist: rendered as an ordinary empty level, never as an error. */
    private fun emptyLevel(
        model: Model,
        prefix: String,
    ): String {
        fillLevelAttributes(model, workspaceId = null, lens = ReadLens.Everything, prefix = prefix, page = 0, level = EMPTY_LEVEL)
        return LEVEL_VIEW
    }

    private fun fillLevelAttributes(
        model: Model,
        workspaceId: UUID?,
        lens: ReadLens,
        prefix: String?,
        page: Int,
        level: PipelineFolderLevel,
    ) {
        model.addAttribute("searching", false)
        model.addAttribute("prefix", prefix ?: "")
        model.addAttribute("levelId", levelId(prefix))
        model.addAttribute("folders", level.folders.map(::PipelineFolderView))
        model.addAttribute("foldersTruncated", level.foldersTruncated)
        model.addAttribute("pipelines", level.pipelines)
        // versioning §7: the "unreleased edits exist" badge, for the rows actually shown. An
        // empty level has no rows, so it needs no query — and has no workspace to run one in.
        val drafts =
            if (workspaceId == null || level.pipelines.isEmpty()) {
                emptyMap()
            } else {
                pipelines.findDrafts(workspaceId, lens, level.pipelines.map { it.id })
            }
        model.addAttribute("drafts", drafts)
        model.addAttribute("offset", page)
        model.addAttribute("hasMore", level.hasMore)
        model.addAttribute("total", level.total)
    }

    /**
     * Fills [model] for a **search** — a flat list of full paths, paged by the shared pager
     * against `#pipeline-list-wrapper` (§9.2).
     */
    fun fillSearch(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        q: String,
        offset: Int,
    ): String {
        val page = maxOf(0, offset)
        val result = pipelines.page(workspaceId, view.pipelines, q, page, PAGE_SIZE)
        model.addAttribute(LENS_UNAVAILABLE, view.unavailable)
        model.addAttribute("searching", true)
        model.addAttribute("pipelines", result.items)
        model.addAttribute("drafts", result.drafts)
        model.addAttribute("offset", page)
        model.addAttribute("hasMore", result.hasMore)
        model.addAttribute("total", result.total)
        return SEARCH_VIEW
    }

    /**
     * Fills [model] for whichever presentation [q] selects, and returns the **dispatcher**
     * view whose one root element is `#pipeline-list-wrapper` either way.
     *
     * This is what the search box's swap and the page's first render both go through, so
     * clearing the search box returns to the tree by construction (§9.2).
     */
    fun fillWrapper(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        q: String?,
        offset: Int,
    ): String {
        if (q.isNullOrEmpty()) {
            fillLevel(model, workspaceId, view, prefix = null, offset = offset)
        } else {
            fillSearch(model, workspaceId, view, q, offset)
        }
        return WRAPPER_VIEW
    }

    // -------------------------------------------------------------------------------------
    // 106 — the detail pane's three regions, in ONE call
    // -------------------------------------------------------------------------------------

    /**
     * Fills [model] for the SELECTED pipeline's detail — header, reading column, acting
     * column — and returns the view name.
     *
     * **One call, every region.** The header's verbs, the overview's key/value strip and the
     * Versions tab's rows are three views of the same lifecycle state; computing them in three
     * places is how a button appears for a version the server would refuse. Runs and Usage are
     * the two exceptions and they are deliberate: each is a tab the user may never open, so
     * each is its own cheap fragment ([fillRuns], [fillUsage]) rather than work every selection
     * pays for.
     *
     * A `pipeline` of null (deleted in another tab, a stale pane) fills nothing else: the
     * partial renders its quiet not-found state.
     */
    fun fillDetail(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        id: UUID,
    ): String {
        // 114 — the verbs this fragment renders are role-gated, so the ROLE arrives in the SAME
        // model call the facts do. Stamped here rather than at each caller because there are
        // three of them — the selection partial, the explorer page, and the lifecycle dialogs'
        // Shape A re-render — and the third one forgot: after a Release the re-rendered detail
        // came back with no role attributes at all, so every verb on it silently vanished until
        // the next selection re-fetched the pane. One model call, one answer.
        RoleModel.stamp(model)
        val record = pipelines.findRecord(workspaceId, view.pipelines, id)
        model.addAttribute("pipelineId", id)
        model.addAttribute("pipeline", record)
        if (record == null) return DETAIL_VIEW

        // The WORKING body (versioning §7): the draft when one exists, else the current
        // release — the same rule the editor's load follows, so opening the editor from here
        // shows what this pane just showed. A body that fails to parse renders as no chips and
        // no parameters rather than as an error page: the pane reads someone else's authored
        // content and the editor is where a malformed body is repaired.
        val working = pipelines.findWorking(workspaceId, view.pipelines, id)
        val body = working?.bodyJson?.let { runCatching { deserializer.readOrThrow(it) }.getOrNull() }
        val versions = pipelines.listVersions(workspaceId, view.pipelines, id)

        model.addAttribute("draftHash", working?.draft?.bodyHash)
        fillIdentity(model, record)
        fillOverview(model, workspaceId, record, body, working?.version?.version ?: record.currentVersion, working?.draft?.version)
        fillActing(model, workspaceId, view, record, versions)
        return DETAIL_VIEW
    }

    /** The header: the folder path as an eyebrow, the leaf as the title. */
    private fun fillIdentity(
        model: Model,
        record: PipelineRecord,
    ) {
        val cut = record.name.lastIndexOf('/')
        model.addAttribute("folderPath", if (cut < 0) "" else record.name.substring(0, cut + 1))
        model.addAttribute("leafName", if (cut < 0) record.name else record.name.substring(cut + 1))
    }

    private fun fillOverview(
        model: Model,
        workspaceId: UUID,
        record: PipelineRecord,
        body: Pipeline?,
        workingVersion: Int?,
        draftVersion: Int?,
    ) {
        model.addAttribute("workingVersion", workingVersion)
        model.addAttribute("draftVersion", draftVersion)
        model.addAttribute("parameters", body?.parameters ?: emptyMap<String, Any>())
        model.addAttribute("nodeCount", body?.nodes?.size ?: 0)
        // 140: the working body's release checks — the overview's Checks section renders its
        // shell only when there are any; definitions and runs lazy-load from the checks partial.
        model.addAttribute("checksCount", body?.checks?.size ?: 0)
        // The "Settings" table is gone (106): the staging engine is a chip, because one row of
        // one column was a table pretending to be a section.
        model.addAttribute("stagingEngine", body?.settings?.tempdb?.engine)
        model.addAttribute("datasourceRows", datasourceRows(body, workspaceId))
        model.addAttribute("templatePins", templatePins(body))
        model.addAttribute("createdBy", actorName(record.ownerId))
        // "via UI / MCP / API" is NOT rendered: nothing audits pipeline CREATE with the surface
        // it arrived on (there is no `pipeline.created` event and `pipelines` carries no
        // `triggered_via`), and the 106 prompt says to omit the chip rather than infer one.
        val last = lastRun(workspaceId, record.id)
        model.addAttribute("lastRun", last)
        model.addAttribute("lastRunAgo", last?.let { RelativeTime.since(it.startedAt, Instant.now()) })
        model.addAttribute("lastRunBy", last?.let { actorName(it.executedBy) })
    }

    private fun fillActing(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        record: PipelineRecord,
        versions: List<PipelineVersionRecord>,
    ) {
        model.addAttribute("versions", versionRows(record, versions))
        // The HEADER's verbs (101 §7, reshaped by 102 §B.1's one-destructive rule): a draft
        // is what Release acts on; Purge pipeline is the ENTITY purge, which 101 allows only
        // while the only version is a draft; otherwise the destructive verb on offer is
        // Discard of a RELEASED current — a pointer that named a draft (the D60 development
        // fallback) is NOT a discard target, so it must not offer the button.
        model.addAttribute("releasableVersion", versions.firstOrNull { it.status == PipelineVersionStatus.DRAFT }?.version)
        model.addAttribute("canDelete", versions.size == 1 && versions.single().status == PipelineVersionStatus.DRAFT)
        val currentRow = versions.firstOrNull { it.version == record.currentVersion }
        model.addAttribute("canDiscardCurrent", record.currentVersion != null && currentRow?.status == PipelineVersionStatus.RELEASED)
        // Purge draft joins the header only when neither other destructive is there (§4.3d).
        model.addAttribute(
            "canPurgeDraftInHeader",
            versions.any { it.status == PipelineVersionStatus.DRAFT } &&
                !(versions.size == 1 && versions.single().status == PipelineVersionStatus.DRAFT) &&
                !(record.currentVersion != null && currentRow?.status == PipelineVersionStatus.RELEASED),
        )
        // Switch needs >= 2 live, posture-eligible versions (§3.4) — the dialog's own rule.
        model.addAttribute(
            "canSwitchHeader",
            versions.count { PipelineVersionStatus.eligibleForPointer(it.status, authoring.developmentPosture) } >= 2,
        )
        model.addAttribute("versionCount", versions.size)
        model.addAttribute("runCount", runStats.totalRuns(record.id))
        model.addAttribute("usageCount", usage(workspaceId, view, record).total)
        // The reading column's Created line chip (V20): the FIRST version's surface.
        model.addAttribute("createdVia", versions.minByOrNull { it.version }?.createdVia)
    }

    /**
     * #349 — the pipeline workspace's composition facts, filled for the canonical page's
     * Versions tab and Overview pane in ONE call from [PipelineWorkspaceController].
     *
     * The Versions tab is the explorer's fragment (`partials/pipeline-versions :: versions`)
     * composed into the workspace page, so the model carries the SAME row shapes the explorer
     * fills ([versionRows]) — visibility, per-row verbs and run counts are the explorer's
     * unchanged contract — plus the viewed-version mark the workspace adds to every row. The
     * returned facts are the Overview's record-level and registry-resolved halves
     * ([WorkspaceTabFacts]): who created the pipeline and on what surface, its last visible
     * run, and the datasource→dialect map across EVERY ADMITTED version's body — the
     * in-page version switch (a client-side fetch of another admitted body) must be able to
     * relabel the new body's datasources without a second round trip, and the lens decides
     * admission per body exactly as the page's own resolution did.
     *
     * Everything here is a read; nothing here changes a verb, a flag or a visibility rule the
     * explorer didn't already state.
     */
    fun fillWorkspaceTabs(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        record: PipelineRecord,
        versions: List<PipelineVersionRecord>,
        viewedVersion: Int?,
    ): WorkspaceTabFacts {
        model.addAttribute("pipeline", record)
        model.addAttribute("versions", versionRows(record, versions, viewedVersion))
        return WorkspaceTabFacts(
            createdBy = actorName(record.ownerId),
            createdVia = versions.minByOrNull { it.version }?.createdVia,
            lastRun = lastRunView(workspaceId, record.id),
            datasourceDialects = workspaceDialects(workspaceId, view, record, versions),
        )
    }

    /** The version rows one tab renders — the explorer's and the workspace's one mapping. */
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
    ) = executions.findAll(workspaceId, pipelineId, limit = 1).firstOrNull()

    /** The Overview's last-run line, as the workspace's JSON facts carry it (#349). */
    private fun lastRunView(
        workspaceId: UUID,
        pipelineId: UUID,
    ): WorkspaceLastRunView? =
        lastRun(workspaceId, pipelineId)?.let { last ->
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
     * this workspace is left out, exactly as [datasourceRows] leaves it out.
     */
    private fun workspaceDialects(
        workspaceId: UUID,
        view: LensedView,
        record: PipelineRecord,
        versions: List<PipelineVersionRecord>,
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        versions.forEach { v ->
            val bodyJson =
                pipelines.findVersionBody(workspaceId, view.pipelines, record.id, v.version) ?: return@forEach
            val body = runCatching { deserializer.readOrThrow(bodyJson) }.getOrNull() ?: return@forEach
            datasourceNames(body).forEach { name ->
                if (!out.containsKey(name)) {
                    datasources.describe(name, workspaceId)?.let { out[name] = it.dialect.name }
                }
            }
        }
        return out
    }

    private fun actorName(actor: UUID): String = actors.lookup(listOf(actor))[actor] ?: ActorNames.fallback(actor)

    /**
     * The datasources the working body touches, with the dialect each speaks.
     *
     * The REGISTRY decides what is a datasource: `tempdb` is the reserved literal and never a
     * registered name (§4.8), and a name the registry cannot resolve FROM THIS WORKSPACE is
     * left out rather than rendered as a link to nothing (§11.2 — a body is portable, a
     * registry is per-environment; visibility is per-workspace, design §5.3, and the page's
     * workspace is passed rather than read off the thread — 134).
     */
    private fun datasourceRows(
        body: Pipeline?,
        workspaceId: UUID,
    ): List<DatasourceRowView> {
        if (body == null) return emptyList()
        return datasourceNames(body)
            .mapNotNull { name -> datasources.describe(name, workspaceId)?.let { DatasourceRowView(name, it.dialect) } }
    }

    /** The template versions the working body pins — `id@version`, deduplicated, in body order. */
    private fun templatePins(body: Pipeline?): List<TemplatePinView> =
        body
            ?.nodes
            ?.map { it.template }
            ?.filter { it.id.isNotEmpty() }
            ?.map { TemplatePinView(it.id, it.version) }
            ?.distinct()
            .orEmpty()

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

        /**
         * The root level's container id is the screen's long-standing stable swap root, so the
         * browse tree inherits the existing SPA contract instead of inventing a second one.
         */
        const val ROOT_LEVEL_ID = "pipeline-list-wrapper"

        const val WRAPPER_VIEW = "partials/pipelines"
        const val LEVEL_VIEW = "partials/pipeline-tree-level"
        const val SEARCH_VIEW = "partials/pipeline-search"
        const val DETAIL_VIEW = "partials/pipeline-detail"
        const val RUNS_VIEW = "partials/pipeline-runs"
        const val USAGE_VIEW = "partials/pipeline-usage"

        /** The Runs tab's ceiling (106) — "the last 20", never the whole history. */
        const val RUNS_LIMIT = 20

        /** The last-run line's exact stamp — the explorer's own `yyyy-MM-dd HH:mm` reading. */
        private val LAST_RUN_FORMAT =
            java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm")

        /** Hex characters of a nested level's id digest — 64 bits, over one screen's folders. */
        private const val LEVEL_ID_HEX_LENGTH = 16

        private val EMPTY_LEVEL =
            PipelineFolderLevel(emptyList(), foldersTruncated = false, pipelines = emptyList(), total = 0, hasMore = false)

        /**
         * The DOM id of the container that holds one tree level.
         *
         * A prefix cannot be used as an id directly: `/` and `.` are legal in a pipeline name
         * and would need escaping at every htmx selector, and a naive substitution would map
         * `a/b` and `a-b` onto the same id. A digest is unambiguous, bounded, and — this is
         * the point — **derived** in one place, so the placeholder the folder renders and the
         * root of the fragment that replaces it cannot disagree.
         */
        fun levelId(prefix: String?): String {
            if (prefix.isNullOrEmpty()) return ROOT_LEVEL_ID
            val digest = MessageDigest.getInstance("SHA-256").digest(prefix.toByteArray(Charsets.UTF_8))
            return "pl-level-" + digest.joinToString("") { "%02x".format(it) }.take(LEVEL_ID_HEX_LENGTH)
        }
    }
}

/**
 * One virtual folder, as the tree fragment needs it.
 *
 * [PipelineFolder] is the repository's shape and knows nothing about the DOM; this adds the
 * one thing the markup needs and cannot derive for itself — the id of the container that will
 * hold the folder's level. Deriving it HERE, once, is what keeps the placeholder the folder
 * renders and the root of the fragment that replaces it from disagreeing.
 */
data class PipelineFolderView(
    val path: String,
    val segment: String,
    val pipelineCount: Int,
    val levelId: String,
) {
    constructor(folder: PipelineFolder) : this(
        folder.path,
        folder.segment,
        folder.pipelineCount,
        PipelineBrowseModel.levelId(folder.path),
    )
}
