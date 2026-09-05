package co.datapipelines.web.ui

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.pipeline.PipelineDeserializer
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.web.api.currentPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

/**
 * The pipelines screen's htmx fragments (067; ui-screens.md §4.3, template-hierarchy-design
 * §9.2).
 *
 * Every handler here is under `/partials`, which the [co.datapipelines.auth.ScopeInterceptor]
 * governs as **default-deny**: a partial carrying no [RequiredScope] is refused, so a new
 * fragment endpoint joins the same authorization posture as the one it sits beside rather
 * than quietly opening a hole (§9.1).
 *
 * ## One route, two fragment shapes — chosen by `prefix`
 *
 * - **`prefix` absent** → the wrapper: the whole `#pipeline-list-wrapper`, which is the search
 *   result list when `q` is non-empty and the tree's root level otherwise. This is the target
 *   of the search box and of the search pager — the existing SPA contract, unchanged.
 * - **`prefix` present** (empty string = the root) → that ONE tree level: its direct
 *   sub-folders and its direct pipeline children, and nothing else. This is what a folder's
 *   lazy expansion fetches, so expanding `nyc/mobility` never returns `trade`'s rows and never
 *   returns the whole list (§9.1: the tree is backed by server-side prefix queries).
 *
 * `q` is ignored while `prefix` is present: browse and search are different presentations
 * (§9.2) and a folder expansion is unambiguously a browse.
 */
@Controller
class PipelinePartialController(
    private val pipelines: PipelineService,
    private val browse: PipelineBrowseModel,
) {
    private val deserializer = PipelineDeserializer()

    @GetMapping("/partials/pipelines")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun list(
        model: Model,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) prefix: String?,
        @RequestParam(required = false) offset: Int?,
    ): String {
        val workspaceId = currentPrincipal().requireWorkspace().id
        model.addAttribute("q", q ?: "")
        model.addAttribute("scopes", scopes())
        return if (prefix != null) {
            browse.fillLevel(model, workspaceId, prefix, offset ?: 0)
        } else {
            browse.fillWrapper(model, workspaceId, q?.trim()?.takeIf { it.isNotEmpty() }, offset ?: 0)
        }
    }

    /**
     * The SELECTED pipeline, for the explorer's right pane — the header (full path, badges,
     * Open-in-editor), the working version's settings and declared parameters, and every
     * version with its RELEASED / DRAFT lifecycle badge (versioning §6).
     *
     * A selection swaps this fragment into `#pipeline-detail` with `innerHTML` and touches
     * nothing else: the tree pane's DOM is never re-rendered by a selection, which is the
     * whole point of the two-pane layout.
     *
     * **Read-only (R10).** There is no create, rename, move, delete or edit affordance here or
     * anywhere else on this screen — the pipeline editor owns writes, and a pipeline's name is
     * its identity (§4.5), so a rename control would be a lie about what the server offers.
     *
     * The body shown is the **working version** (versioning §7): the DRAFT when one exists,
     * else the current RELEASED version — the same rule the editor's load follows, so opening
     * the editor from here shows what the pane just showed. A body that fails to parse renders
     * as no settings and no parameters rather than as an error page: the pane is a read of
     * someone else's authored content, and the editor is where a malformed body is repaired.
     *
     * The id travels as a QUERY parameter for symmetry with the templates pane, not out of
     * necessity — a pipeline is UUID-addressed everywhere (§9.6's `%2F` problem cannot arise
     * for a UUID), which is exactly why 067 needs no REST version bump where 043 did.
     */
    @GetMapping("/partials/pipelines/detail")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun detail(
        model: Model,
        @RequestParam id: UUID,
    ): String {
        val workspaceId = currentPrincipal().requireWorkspace().id
        val record = pipelines.findRecord(workspaceId, id)
        model.addAttribute("pipelineId", id)
        model.addAttribute("pipeline", record)
        if (record != null) {
            val working = pipelines.findWorking(workspaceId, id)
            val body = working?.bodyJson?.let { runCatching { deserializer.readOrThrow(it) }.getOrNull() }
            model.addAttribute("workingVersion", working?.version?.version ?: record.currentVersion)
            model.addAttribute("draftVersion", working?.draft?.version)
            model.addAttribute("settings", body?.settings)
            model.addAttribute("parameters", body?.parameters ?: emptyMap<String, Any>())
            model.addAttribute("nodeCount", body?.nodes?.size ?: 0)
            model.addAttribute("versions", pipelines.listVersions(workspaceId, id))
        }
        return "partials/pipeline-detail"
    }

    private fun scopes(): Set<String> {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal
        return principal?.scopes?.map { it.name }?.toSet() ?: emptySet()
    }
}
