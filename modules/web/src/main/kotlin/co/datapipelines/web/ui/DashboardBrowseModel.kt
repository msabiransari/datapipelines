package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.pipeline.LifecycleBucket
import co.datapipelines.pipeline.SearchNeedle
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.DashboardBody
import co.datapipelines.visualization.DashboardService
import co.datapipelines.web.dashboards.runtime.DashboardRuntime
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.ui.Model
import java.time.Instant
import java.util.UUID

/**
 * The dashboards screens' model, in one place for the page controller and the partial
 * controllers — [PipelineBrowseModel]'s shape copied for the second artifact family (the
 * implementation spec's §6.3, ui-screens.md §4.x). Since #400 the PAGE is the flat catalog
 * (the pipelines catalog's shape, owner ruling 2026-10-02), and the catalog and its search
 * partial render the SAME flat list. The folder tree is the global sidebar's, read from
 * `/api/v1/dashboards/tree` since #460; its server-rendered levels (`/partials/dashboards/tree`)
 * are gone with #466. Nothing here filters in a template, and nothing ships an unfiltered list
 * to the browser.
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
     * Fills [model] for the `/dashboards` catalog's **flat list of dashboards** — every dashboard
     * the caller may read when `q` is absent, the matches otherwise — in
     * [PipelineBrowseModel.fillSearch]'s shape: a page of the lensed flat listing, the truthful
     * total, the pager bound to the catalog's stable root [PAGE_ROOT_ID].
     *
     * The page and its truthful total come from [DashboardService]'s lensed reads under every
     * lens, never from a filter in this model: with no `q` the page is `listAll` (`LIMIT size+1
     * OFFSET offset`, the extra row deciding `hasMore`) beside `countAll`; with a `q` it is
     * [DashboardService.search] — name, display name or description contains the needle,
     * case-insensitive, LIKE metacharacters literal — whose own `total` is the match count. Under
     * the everything lens both are SQL; under a narrowing one (the promoter's) the lifecycle pages
     * the admitted set (admitted intersected with the matches) so a lensed principal is handled
     * there, not here (#512). The needle is trimmed and truncated to the shared bound
     * ([SearchNeedle.MAX_LENGTH], #504b) before the call: a box truncates its own term, never refuses.
     *
     * #580: a [lifecycle] bucket takes the page and its total from [DashboardService.listIn] — the
     * inventory's membership rule under every lens, `q` composed — so the filtered total is the
     * overview's bucket count; the bucket renders back into the control and rides on the pager.
     */
    fun fillList(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        q: String?,
        offset: Int,
        lifecycle: LifecycleBucket? = null,
    ): String {
        val page = maxOf(0, offset)
        // A box truncates its own term to the shared bound (#504b) before the call, never refuses.
        val needle = q?.trim()?.takeIf { it.isNotEmpty() }?.take(SearchNeedle.MAX_LENGTH)
        if (lifecycle != null) {
            val found = dashboards.listIn(workspaceId, view.dashboards, lifecycle, needle, page, PAGE_SIZE)
            model.addAttribute("hasMore", found.total > page + PAGE_SIZE)
            model.addAttribute("total", found.total)
            model.addAttribute("dashboards", found.items.map(::DashboardLeafView))
        } else if (needle == null) {
            // One past the page size decides `hasMore` — the honest-pager rule (034 E3).
            val rows = dashboards.listAll(workspaceId, view.dashboards, page, PAGE_SIZE + 1)
            model.addAttribute("hasMore", rows.size > PAGE_SIZE)
            model.addAttribute("total", dashboards.countAll(workspaceId, view.dashboards))
            model.addAttribute("dashboards", rows.take(PAGE_SIZE).map(::DashboardLeafView))
        } else {
            val found = dashboards.search(workspaceId, view.dashboards, needle, page, PAGE_SIZE)
            model.addAttribute("hasMore", found.total > page + PAGE_SIZE)
            model.addAttribute("total", found.total)
            model.addAttribute("dashboards", found.items.map(::DashboardLeafView))
        }
        model.addAttribute("lensUnavailable", view.unavailable)
        model.addAttribute("lifecycle", lifecycle?.wire.orEmpty())
        model.addAttribute("lifecycleLabel", lifecycle?.let(CatalogLifecycle::label))
        model.addAttribute("lifecycleOptions", CatalogLifecycle.options(CatalogLifecycle.DASHBOARDS))
        model.addAttribute("searching", true)
        model.addAttribute("rootId", PAGE_ROOT_ID)
        model.addAttribute("q", needle.orEmpty())
        model.addAttribute("offset", page)
        return SEARCH_VIEW
    }

    /**
     * Fills [model] for the catalog's list ([fillList]) and returns the catalog's swap-root
     * view, `partials/dashboards`, whose one root element is [PAGE_ROOT_ID].
     */
    fun fillWrapper(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        q: String?,
        offset: Int,
        lifecycle: LifecycleBucket? = null,
    ): String {
        fillList(model, workspaceId, view, q, offset, lifecycle)
        return WRAPPER_VIEW
    }

    /**
     * Fills [model] for the workspace's **Overview tab** (#400) — the viewed version's
     * definition, read-only: the display name and description, the sources with their pinned
     * pipeline versions and statuses, the parameter set with its status, the pinned
     * visualizations with their versions and each pin's status (DRAFT pins work in draft
     * preview and require release before publication),
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
     * history the REST versions route answers. Draft preview admits live draft dependencies;
     * publication requires released pins.
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
        /** A DRAFT pin is available in draft preview and requires release before publication. */
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

    /** One dashboard row of the catalog, at its listed (working) version — the pipelines row's twin. */
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
        /** The catalogs' page size — the value the pipelines and templates lists use. */
        const val PAGE_SIZE = 25

        /** The events pane shows the most recent refreshes; the record holds the rest. */
        const val REFRESH_LIMIT = 10

        const val SEARCH_VIEW = "partials/dashboard-search"
        const val REFRESHES_VIEW = "partials/dashboard-refreshes"
        const val OVERVIEW_VIEW = "partials/dashboard-overview"
        const val VERSIONS_VIEW = "partials/dashboard-versions"
        const val KEYS_VIEW = "partials/dashboard-keys"

        /** The catalog's swap-root view: it renders `dashboard-search` (partials/pipelines' shape). */
        const val WRAPPER_VIEW = "partials/dashboards"

        /** The DOM id of the catalog's stable swap root (partials/dashboard-search renders it). */
        const val PAGE_ROOT_ID = "dash-list-wrapper"
    }
}
