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
 * implementation spec's §6.3, ui-screens.md §4.x): the tree page and the sidebar's lazy tree
 * render the SAME one-level-per-request fragment, so the screen and the fragment cannot
 * disagree about what a level holds.
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
) {
    /**
     * Fills [model] for one **tree level** — [prefix] `null`/empty is the root — and returns the
     * view name. [scope] names the instance the level renders in (`page` or `nav`): the sidebar's
     * tree and the page's tree coexist in one document, so their level ids are derived per scope
     * and the placeholder a folder renders can never collide with the other instance's.
     */
    fun fillLevel(
        model: Model,
        workspaceId: UUID,
        view: LensedView,
        prefix: String?,
        offset: Int,
        scope: String,
    ): String {
        // A prefix is user input that becomes a LIKE pattern: bound and escaped in the
        // repository, but a value that is not a legal artifact name cannot name a real folder
        // either, and an arbitrary-length string would make a level request an arbitrary-length
        // pattern match. An illegal prefix renders an ordinary EMPTY level, never an error —
        // the templates and pipelines browsers settled that (a level that cannot exist is not a
        // client fault worth a 400).
        if (!prefix.isNullOrEmpty() && !PipelineNameGrammar.matchesPrefix(prefix)) {
            return emptyLevel(model, prefix, scope)
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
        model.addAttribute("scope", scope)
        model.addAttribute("levelId", levelId(scope, prefix))
        model.addAttribute("folders", folders.map { DashboardFolderView(it, scope) })
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
        scope: String,
    ): String {
        model.addAttribute("prefix", prefix)
        model.addAttribute("scope", scope)
        model.addAttribute("levelId", levelId(scope, prefix))
        model.addAttribute("folders", emptyList<DashboardFolderView>())
        model.addAttribute("dashboards", emptyList<DashboardLeafView>())
        model.addAttribute("offset", 0)
        model.addAttribute("hasMore", false)
        model.addAttribute("total", 0)
        return LEVEL_VIEW
    }

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
        const val REFRESHES_VIEW = "partials/dashboard-refreshes"

        /** The DOM id of the NAV instance's root level — the sidebar tree's lazy container. */
        const val NAV_ROOT_ID = "dash-tree-nav"

        /** Hex characters of a nested level's id digest — 64 bits, over one screen's folders. */
        private const val LEVEL_ID_HEX_LENGTH = 16

        /**
         * The DOM id of the container that holds one tree level, per instance scope.
         *
         * A prefix cannot be an id (`/` and `.` are legal in a name), and the sidebar's tree and
         * the page's tree coexist in one document — so the id is derived from the scope AND a
         * digest of the prefix, in one place, so the placeholder a folder renders and the root of
         * the fragment that replaces it cannot disagree (the pipelines explorer's rule, one scope up).
         */
        fun levelId(
            scope: String,
            prefix: String?,
        ): String {
            if (prefix.isNullOrEmpty()) return if (scope == SCOPE_NAV) NAV_ROOT_ID else PAGE_ROOT_ID
            val digest = MessageDigest.getInstance("SHA-256").digest(prefix.toByteArray(Charsets.UTF_8))
            return "dash-level-$scope-" + digest.joinToString("") { "%02x".format(it) }.take(LEVEL_ID_HEX_LENGTH)
        }

        const val SCOPE_PAGE = "page"
        const val SCOPE_NAV = "nav"

        /** The page instance's root container id (the page tree's stable swap root). */
        const val PAGE_ROOT_ID = "dash-tree-page"
    }
}
