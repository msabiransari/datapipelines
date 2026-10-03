package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.pipeline.PipelineNameGrammar
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.DashboardBody
import co.datapipelines.visualization.DashboardService
import co.datapipelines.web.dashboards.runtime.DashboardRuntime
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.ui.Model
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * The dashboards screens' model, in one place for the page controller and the partial
 * controllers — [PipelineBrowseModel]'s shape copied for the second artifact family (the
 * implementation spec's §6.3, ui-screens.md §4.x). Since #400 the PAGE is the flat catalog
 * (the pipelines catalog's shape, owner ruling 2026-10-02): the folder tree lives only in the
 * sidebar's lazy branch, so the tree level and the sidebar render the SAME one-level-per-request
 * fragment and can never disagree about what a level holds — and the catalog and its search
 * partial render the SAME flat list.
 *
 * The tree is backed by server-side prefix queries through [DashboardService] — the family's
 * own lensed reads (`listChildFolders`/`listChildren`/`countChildren`): under the everything
 * lens the level is the repository's parameterised SQL; under a narrowing lens (the promoter's)
 * it is derived from the admitted current RELEASED set by the same rule the pipelines explorer's
 * service applies ("the SQL level cannot take a per-request name set"). Nothing here filters in
 * a template, and nothing ships an unfiltered list to the browser.
 *
 * A folder is virtual, exactly as in the pipelines explorer (§3.1 of the template-hierarchy
 * design): derived per request from the live rows beneath it, no CRUD, no empty-folder state.
 * The ROOT level renders folders only — a dashboard name needs a folder, so the root is a
 * directory of folders (the 077 rule the pipelines tree states).
 *
 * Declared as an explicit `@Bean` in [UiConfig], not by a stereotype: the house rule is zero
 * DI stereotypes in production code with **no** allowlist (015 / module-structure §8.4), and
 * `ArchitectureGuardTest` enforces it. No repository is read here — every read goes through
 * the service or the runtime (O3).
 */
class DashboardBrowseModel(
    private val dashboards: DashboardService,
    private val runtime: DashboardRuntime,
    /**
     * #400 — the Overview tab's pin facts: the SAME three ports the validator and the release
     * guard read (the save-time/status question answered once, never a second copy), so the
     * tab's pin statuses are the family's own evidence, not a screen-shaped guess.
     */
    private val pipelines: co.datapipelines.visualization.PipelineReleaseFacts,
    private val sets: co.datapipelines.visualization.ParameterSetFacts,
    private val pins: co.datapipelines.visualization.VisualizationPins,
    /** #400 — the Keys tab's rows: the workspace's `dashboard` keys and their folders. */
    private val apiKeys: co.datapipelines.auth.ApiKeyRepository,
    private val dashboardBindings: co.datapipelines.application.dashboards.DashboardKeyBindingRepository,
) {
    /**
     * Fills [model] for one **tree level** of the SIDEBAR's lazy branch — [prefix] `null`/empty
     * is the root — and returns the view name. #400: the L3b tree PAGE is retired (the catalog
     * replaced it), so this fill is the NAV instance's alone — the scope is always
     * [SCOPE_NAV], and a level's ids are always nav-derived.
     */
    fun fillLevel(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        prefix: String?,
        offset: Int,
    ): String {
        // A prefix is user input that becomes a LIKE pattern: bound and escaped in the
        // repository, but a value that is not a legal artifact name cannot name a real folder
        // either, and an arbitrary-length string would make a level request an arbitrary-length
        // pattern match. An illegal prefix renders an ordinary EMPTY level, never an error —
        // the templates and pipelines browsers settled that (a level that cannot exist is not a
        // client fault worth a 400).
        if (!prefix.isNullOrEmpty() && !PipelineNameGrammar.matchesPrefix(prefix)) {
            return emptyLevel(model, prefix)
        }
        val page = maxOf(0, offset)
        val folders = dashboards.listChildFolders(workspaceId, view.dashboards, prefix?.takeIf { it.isNotEmpty() })
        val children =
            dashboards.listChildren(
                workspaceId,
                view.dashboards,
                prefix?.takeIf { it.isNotEmpty() },
                page,
                PAGE_SIZE,
            )
        val total = dashboards.countChildren(workspaceId, view.dashboards, prefix?.takeIf { it.isNotEmpty() })
        // THE ROOT HOLDS FOLDERS ONLY (077, the pipelines tree's rule): every dashboard name
        // has a folder, so the root is a directory of folders and nothing else.
        val leaves = if (prefix.isNullOrEmpty()) emptyList() else children
        model.addAttribute("lensUnavailable", view.unavailable)
        model.addAttribute("prefix", prefix ?: "")
        model.addAttribute("scope", SCOPE_NAV)
        model.addAttribute("levelId", levelId(SCOPE_NAV, prefix))
        model.addAttribute("folders", folders.map { DashboardFolderView(it, SCOPE_NAV) })
        model.addAttribute("dashboards", leaves.map(::DashboardLeafView))
        model.addAttribute("offset", page)
        model.addAttribute("hasMore", page + leaves.size < total)
        model.addAttribute("total", total)
        return LEVEL_VIEW
    }

    /** A level that cannot exist: rendered as an ordinary empty level, never as an error. */
    private fun emptyLevel(
        model: Model,
        prefix: String,
    ): String {
        model.addAttribute("prefix", prefix)
        model.addAttribute("scope", SCOPE_NAV)
        model.addAttribute("levelId", levelId(SCOPE_NAV, prefix))
        model.addAttribute("folders", emptyList<DashboardFolderView>())
        model.addAttribute("dashboards", emptyList<DashboardLeafView>())
        model.addAttribute("offset", 0)
        model.addAttribute("hasMore", false)
        model.addAttribute("total", 0)
        return LEVEL_VIEW
    }

    /**
     * Fills [model] for a **flat list of dashboards** — the sidebar search's results
     * ([DashboardListScope.NAV], `q` non-empty) or the `/dashboards` catalog
     * ([DashboardListScope.PAGE], where an absent `q` lists every dashboard the caller may
     * read), in [PipelineBrowseModel.fillSearch]'s shape: a page of the lensed flat listing,
     * the truthful total, the pager bound to the scope's stable root.
     *
     * The two paths mirror the pipelines page's rule: with no `q` under the everything lens the
     * page is taken in SQL (`LIMIT size+1 OFFSET offset`) and the total is a `COUNT(*)`; with a
     * `q` (or a lensed principal whose admitted set is a per-request name set) the rows are
     * filtered in memory — the name contains the query, case-insensitive, the only flat-search
     * semantics the REST flat listing has — and the total is the filtered size.
     */
    fun fillList(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        q: String?,
        offset: Int,
        scope: DashboardListScope,
    ): String {
        val page = maxOf(0, offset)
        val needle = q?.trim()?.takeIf { it.isNotEmpty() }
        if (needle == null && view.dashboards.isEverything) {
            // One past the page size decides `hasMore` — the honest-pager rule (034 E3).
            val rows = dashboards.listAll(workspaceId, view.dashboards, page, PAGE_SIZE + 1)
            val shown = rows.take(PAGE_SIZE).map(::DashboardLeafView)
            model.addAttribute("hasMore", rows.size > PAGE_SIZE)
            model.addAttribute("total", dashboards.countAll(workspaceId, view.dashboards))
            model.addAttribute("dashboards", shown)
        } else {
            val match = needle?.lowercase()
            val all =
                dashboards
                    .listAll(
                        workspaceId,
                        view.dashboards,
                        0,
                        co.datapipelines.visualization.ArtifactRepository.MAX_PAGE_LIMIT,
                    ).filter {
                        match == null ||
                            it.record.name
                                .lowercase()
                                .contains(match)
                    }.map(::DashboardLeafView)
            val shown = all.drop(page).take(PAGE_SIZE)
            model.addAttribute("hasMore", all.size > page + PAGE_SIZE)
            model.addAttribute("total", all.size)
            model.addAttribute("dashboards", shown)
        }
        model.addAttribute("lensUnavailable", view.unavailable)
        model.addAttribute("searching", true)
        model.addAttribute("scope", scope.wire)
        model.addAttribute("rootId", scope.rootId)
        model.addAttribute("q", needle.orEmpty())
        model.addAttribute("offset", page)
        return SEARCH_VIEW
    }

    /**
     * Fills [model] for whichever presentation [q] and [scope] select, and returns the
     ** dispatcher** view whose one root element is the scope's stable swap root either way.
     * In the sidebar ([DashboardListScope.NAV]) an empty `q` is the tree's ROOT level and a
     * non-empty one the flat list, so clearing the box returns to the tree by construction;
     * the catalog ([DashboardListScope.PAGE]) has no tree to return to: it is always the flat
     * list.
     */
    fun fillWrapper(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        q: String?,
        offset: Int,
        scope: DashboardListScope,
    ): String {
        if (scope == DashboardListScope.NAV && q.isNullOrEmpty()) {
            fillLevel(model, workspaceId, view, prefix = null, offset = offset)
        } else {
            fillList(model, workspaceId, view, q, offset, scope)
        }
        return WRAPPER_VIEW
    }

    /**
     * Fills [model] for the workspace's **Overview tab** (#400) — the viewed version's
     * definition, read-only: the display name and description, the sources with their pinned
     * pipeline releases and statuses, the parameter set with its status, the pinned
     * visualizations with their versions and each pin's status (RELEASED, or DRAFT with the
     * release hint — R1: pins are judged RELEASED-only, the hint is the engineer's way out),
     * and the layout summary. There is deliberately no authoring control: the browser never
     * authors a dashboard (#396). The body is the caller's lensed read
     * ([DashboardService.findServedVersion] — the same admission the page resolved), so a
     * version the caller cannot see is the family's 404 before any fact is read.
     */
    fun fillOverview(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        id: UUID,
        version: Int,
    ): String {
        val loaded =
            dashboards.findServedVersion(workspaceId, view.dashboards, id, version)
                ?: throw overviewNotFound(id, version)
        val body = loaded.body
        val setPin = body.parameterSet?.let { ref -> ref to sets.setOf(workspaceId, ref)?.status }
        model.addAttribute("dashboardId", id.toString())
        model.addAttribute("version", version)
        model.addAttribute("status", loaded.detail.status.name)
        model.addAttribute("displayName", body.displayName)
        model.addAttribute("description", body.description)
        model.addAttribute(
            "sources",
            body.sources.map { source ->
                val status = pipelines.releaseOf(workspaceId, source.pipeline)?.status
                PinView(source.name, source.pipeline.toString(), status?.name)
            },
        )
        model.addAttribute(
            "parameterSet",
            setPin?.let { (ref, status) -> PinView(ref.name, "v${ref.version}", status?.name) },
        )
        model.addAttribute(
            "visualizations",
            body.visualizations.map { occurrence ->
                val status = pins.pinOf(workspaceId, occurrence.visualization)?.status
                PinView(occurrence.name, occurrence.visualization.toString(), status?.name)
            },
        )
        model.addAttribute("gridCount", body.layout.grid.size)
        model.addAttribute("columns", body.layout.columns)
        model.addAttribute("groups", body.groups.map { it.name })
        return OVERVIEW_VIEW
    }

    /**
     * Fills [model] for the workspace's **Versions tab** (#400) — the admitted history,
     * newest first, each row marked with the served (current RELEASED) pointer, the DRAFT
     * state and the DISCARDED state, so the lifecycle verbs' dialogs (the row's ⋯ actions)
     * open onto the truth. The read is [DashboardService.listVersions]' — the same lensed
     * history the REST versions route answers — and the RELEASED-only pin rule (R1) rides the
     * draft badge's hint, never a second judgement.
     */
    fun fillVersions(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        id: UUID,
        servedVersion: Int?,
        now: Instant = Instant.now(),
    ): String {
        val versions = dashboards.listVersions(workspaceId, view.dashboards, id)
        model.addAttribute("dashboardId", id.toString())
        model.addAttribute("servedVersion", servedVersion)
        model.addAttribute(
            "versions",
            versions.map { detail ->
                VersionDetailView(
                    version = detail.version,
                    status = detail.status.name,
                    createdAt = detail.createdAt,
                    createdAgo = RelativeTime.since(detail.createdAt, now),
                    createdAbsolute = RelativeTime.absolute(detail.createdAt),
                    releasedAt = detail.releasedAt,
                    releasedAgo = detail.releasedAt?.let { RelativeTime.since(it, now) },
                    releasedAbsolute = detail.releasedAt?.let { RelativeTime.absolute(it) },
                    isServed = detail.version == servedVersion,
                    isDraft = detail.status == co.datapipelines.pipeline.PipelineVersionStatus.DRAFT,
                    isDiscarded = detail.status == co.datapipelines.pipeline.PipelineVersionStatus.DISCARDED,
                )
            },
        )
        return VERSIONS_VIEW
    }

    /**
     * Fills [model] for the workspace's **Keys tab** (#400) — the `dashboard` keys whose
     * bindings cover THIS dashboard's name (the folder itself or an ancestor), read-only, each
     * row linking the Keys page's editor. The route floors at `dashboard.key.bind` — the
     * lowest row whose cells match the tab's visibility — and the read is the Keys page's own
     * pair (the workspace's `dashboard` keys, the workspace's binding rows): no second copy of
     * what a binding is. A key revoked elsewhere keeps its rows until its binding is removed
     * — the row says so, like the Keys page's own table.
     */
    fun fillKeys(
        model: Model,
        workspaceId: UUID,
        id: UUID,
        name: String,
    ): String {
        val keys = apiKeys.findByWorkspaceAndKind(workspaceId, co.datapipelines.auth.ApiKeyKind.DASHBOARD)
        val bindings = dashboardBindings.findByWorkspace(workspaceId)
        val covering = bindings.filter { name == it.namePrefix || name.startsWith("${it.namePrefix}/") }
        val byKey = keys.associateBy { it.id }
        model.addAttribute("dashboardId", id.toString())
        model.addAttribute(
            "keyRows",
            covering
                .groupBy { it.apiKeyId }
                .map { (keyId, rows) ->
                    val key = byKey[keyId]
                    val expires = key?.expiresAt
                    val now = java.time.Instant.now()
                    DashboardKeyRowView(
                        keyName = key?.name ?: keyId,
                        keyId = keyId,
                        prefixes = rows.map { it.namePrefix }.sorted(),
                        live = key != null && !key.isRevoked && (expires == null || expires.isAfter(now)),
                        revoked = key?.isRevoked == true,
                        expired = key != null && !key.isRevoked && expires != null && expires.isBefore(now),
                    )
                }.sortedBy { it.keyName },
        )
        return KEYS_VIEW
    }

    /** One Overview pin row: the name it goes by here, the pinned ref, the status word. */
    data class PinView(
        val label: String,
        val ref: String,
        val status: String?,
    ) {
        /** The R1 sentence a DRAFT pin carries — release it first, the cascade aside. */
        val draftHint: Boolean get() = status == "DRAFT"
    }

    /** One Versions-tab row: the lifecycle facts the markers and the ⋯ actions read. */
    data class VersionDetailView(
        val version: Int,
        val status: String,
        val createdAt: Instant,
        /** `3 days ago`, and [createdAbsolute] (`2026-10-01 09:00 UTC`) for the cell's `title` — the keys page's shape ([RelativeTime]). */
        val createdAgo: String,
        val createdAbsolute: String,
        val releasedAt: Instant?,
        val releasedAgo: String?,
        val releasedAbsolute: String?,
        val isServed: Boolean,
        val isDraft: Boolean,
        val isDiscarded: Boolean,
    )

    /** One Keys-tab row: a `dashboard` key and the folders of its bindings covering this dashboard. */
    data class DashboardKeyRowView(
        val keyName: String,
        val keyId: String,
        val prefixes: List<String>,
        val live: Boolean,
        val revoked: Boolean,
        val expired: Boolean,
    )

    /** The Overview's 404 — the same answer the page resolved before the partial was asked. */
    private fun overviewNotFound(
        id: UUID,
        version: Int,
    ) = co.datapipelines.typesystem.DatapipelinesException(
        code = co.datapipelines.visualization.DashboardErrorCodes.NOT_FOUND,
        message = "Dashboard '$id' version $version not found.",
        details = mapOf("dashboard_id" to id.toString(), "version" to version.toString()),
    )

    /**
     * Fills [model] for the board page's **events pane** — the caller's refreshes of [id],
     * newest first (the REST `GET …/refreshes` route's own read, through the runtime, so the
     * lens and the own/`execution.read_all` visibility are the route's, never a second copy).
     *
     * Each row's execution links come from the per-refresh read (`getRefresh`), which applies
     * the SAME rule the REST read route does — the links are shown only to a principal who
     * reads executions; a promoter's refresh names none (rest-api §23.3, dashboards.md §5.7).
     * The per-row call is a primary-key read bounded by [REFRESH_LIMIT].
     */
    fun fillRefreshes(
        model: Model,
        principal: AuthenticatedPrincipal,
        id: UUID,
    ): String {
        val page = runtime.listRefreshes(principal, id, 0, REFRESH_LIMIT)
        val rows =
            page.items.map { node ->
                val refreshId = UUID.fromString(node.get("refresh_id").asText())
                RefreshRowView.of(runtime.getRefresh(principal, id, refreshId))
            }
        model.addAttribute("dashboardId", id.toString())
        model.addAttribute("refreshes", rows)
        return REFRESHES_VIEW
    }

    /** One tree folder, with the scope-aware id of the container that will hold its level. */
    data class DashboardFolderView(
        val path: String,
        val segment: String,
        val count: Int,
        val levelId: String,
        val scope: String,
    ) {
        constructor(folder: co.datapipelines.visualization.ArtifactFolder, scope: String) : this(
            folder.path,
            folder.segment,
            folder.count,
            levelId(scope, folder.path),
            scope,
        )
    }

    /** One dashboard row of the tree, at its listed (working) version — the pipelines leaf's twin. */
    data class DashboardLeafView(
        val id: UUID,
        val name: String,
        val segment: String,
        val displayName: String,
        val version: Int,
        val released: Boolean,
    ) {
        constructor(version: ArtifactVersion<DashboardBody>) : this(
            version.record.id,
            version.record.name,
            version.record.name.substringAfterLast('/'),
            version.body.displayName,
            version.detail.version,
            version.detail.status == co.datapipelines.pipeline.PipelineVersionStatus.RELEASED,
        )
    }

    /** One events-pane row, read off the runtime's refresh JSON (rest-api §23.3's shape). */
    data class RefreshRowView(
        val refreshId: String,
        val status: String,
        val scope: String,
        val startedAt: String,
        val ago: String,
        val finished: Boolean,
        val executions: List<ExecutionLinkView>,
    ) {
        companion object {
            fun of(node: ObjectNode): RefreshRowView {
                val started = Instant.parse(node.get("started_at").asText())
                val executions =
                    node.get("executions")?.let { links ->
                        links.map { link ->
                            ExecutionLinkView(
                                link.get("source").asText(),
                                link.get("execution_id").asText(),
                                link.get("shared").asBoolean(),
                            )
                        }
                    } ?: emptyList()
                return RefreshRowView(
                    refreshId = node.get("refresh_id").asText(),
                    status = node.get("status").asText(),
                    scope = node.get("scope").asText(),
                    startedAt = started.toString(),
                    ago = RelativeTime.since(started, Instant.now()),
                    finished = node.get("finished_at") != null && !node.get("finished_at").isNull,
                    executions = executions,
                )
            }
        }
    }

    /** One execution of a refresh — the link to its own execution page (D52: linked, never duplicated). */
    data class ExecutionLinkView(
        val source: String,
        val executionId: String,
        val shared: Boolean,
    )

    companion object {
        /** The explorers' page size — the value the pipelines and templates trees use. */
        const val PAGE_SIZE = 25

        /** The events pane shows the most recent refreshes; the record holds the rest. */
        const val REFRESH_LIMIT = 10

        const val LEVEL_VIEW = "partials/dashboard-tree-level"
        const val SEARCH_VIEW = "partials/dashboard-search"
        const val REFRESHES_VIEW = "partials/dashboard-refreshes"
        const val OVERVIEW_VIEW = "partials/dashboard-overview"
        const val VERSIONS_VIEW = "partials/dashboard-versions"
        const val KEYS_VIEW = "partials/dashboard-keys"

        /** The dispatcher: `searching ? dashboard-search : dashboard-tree-level` (partials/pipelines' shape). */
        const val WRAPPER_VIEW = "partials/dashboards"

        /** The DOM id of the NAV instance's root level — the sidebar tree's lazy container. */
        const val NAV_ROOT_ID = "dash-tree-nav"

        /** Hex characters of a nested level's id digest — 64 bits, over one screen's folders. */
        private const val LEVEL_ID_HEX_LENGTH = 16

        /**
         * The DOM id of the container that holds one tree level. A prefix cannot be an id (`/`
         * and `.` are legal in a name), so the id is derived from the scope AND a digest of the
         * prefix, in one place, so the placeholder a folder renders and the root of the fragment
         * that replaces it cannot disagree (the pipelines explorer's rule, one scope up). #400:
         * the only instance left is the sidebar's ([SCOPE_NAV]) — the L3b page tree retired
         * with the catalog's arrival.
         */
        fun levelId(
            scope: String,
            prefix: String?,
        ): String {
            if (prefix.isNullOrEmpty() && scope == SCOPE_NAV) return NAV_ROOT_ID
            val key = prefix ?: ""
            val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            return "dash-level-$scope-" + digest.joinToString("") { "%02x".format(it) }.take(LEVEL_ID_HEX_LENGTH)
        }

        const val SCOPE_NAV = "nav"
    }
}

/**
 * One flat-list instance's scope — [PipelineListScope]'s twin: which stable swap root the
 * fragment renders and the pager targets. The sidebar search results swap under
 * [NAV_ROOT_ID]; the `/dashboards` catalog under its own [PAGE_ROOT_ID].
 */
enum class DashboardListScope(
    val wire: String,
    val rootId: String,
) {
    NAV(DashboardBrowseModel.SCOPE_NAV, "dash-tree-nav"),
    PAGE("page", "dash-list-wrapper"),
    ;

    companion object {
        /** The DOM id of the catalog instance's stable swap root (partials/dashboard-search renders it). */
        const val PAGE_ROOT_ID = "dash-list-wrapper"

        fun fromWire(value: String?): DashboardListScope = if (value == NAV.wire) NAV else PAGE
    }
}
