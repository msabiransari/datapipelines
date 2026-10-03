package co.datapipelines.web.ui

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.web.api.currentPrincipal
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * The visualizations family's fragments (#399, parent #396), every one a READ on `visualization.read` — the
 * floor the page routes declare (no new permission, no ScopeMatrix row; `ReadFloorTest`'s rule). Partials
 * under `/partials` are default-deny: an undeclared handler here would be refused, never served.
 *
 * - the SIDEBAR tree level and its search results, `GET /partials/visualizations/tree` (every response carries
 *   [VisualizationBrowseModel.NAV_STAMP_HEADER], so the rail refuses a level rendered under another workspace
 *   or lens). The rail sends `scope=nav` like its siblings; this route is ALWAYS the nav presentation — the
 *   catalog has its own route below — so `scope` is accepted and not bound;
 * - the CATALOG's swap root, `GET /partials/visualizations`;
 * - the workspace's lazy tabs, `GET /partials/visualizations/{id}/{preview|overview|evidence|used-by|versions}`
 *   and one run's detail, `GET /partials/visualizations/{id}/evidence/{runId}`.
 *
 * Every read goes through the caller's lens; a hidden visualization, version or run is the family's 404.
 */
@Controller
@RequestMapping("/partials/visualizations")
class VisualizationPartialController(
    private val browse: VisualizationBrowseModel,
    private val tabs: VisualizationTabModel,
    /** 178 — the promoter lens: every fragment here renders the caller's view. */
    private val lens: PromoterLens,
) {
    @GetMapping("/tree")
    @RequiredScope(Permission.VISUALIZATION_READ)
    fun tree(
        model: Model,
        response: HttpServletResponse,
        @RequestParam(required = false) prefix: String?,
        @RequestParam(required = false) q: String?,
        // The pager's offset (the level and search templates' prev/next links); page one when absent.
        @RequestParam(required = false) offset: Int?,
    ): String {
        val principal = currentPrincipal()
        val workspace = principal.requireWorkspace()
        val view = lens.viewFor(principal)
        response.setHeader(VisualizationBrowseModel.NAV_STAMP_HEADER, VisualizationBrowseModel.navStamp(workspace.name, view))
        return if (q.isNullOrBlank() && prefix != null) {
            browse.fillLevel(model, workspace.id, view, prefix, offset ?: 0)
        } else {
            browse.fillWrapper(model, workspace.id, view, q, offset ?: 0, VisualizationListScope.NAV)
        }
    }

    /** The `/visualizations` catalog's swap root: the flat list, every readable visualization or the matches of `q`. */
    @GetMapping
    @RequiredScope(Permission.VISUALIZATION_READ)
    fun catalog(
        model: Model,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) offset: Int?,
    ): String {
        val principal = currentPrincipal()
        return browse.fillWrapper(
            model,
            principal.requireWorkspace().id,
            lens.viewFor(principal),
            q,
            offset ?: 0,
            VisualizationListScope.PAGE,
        )
    }

    /** Preview — the viewed version in fixture mode over its own test cases (no live data, no network call). */
    @GetMapping("/{id}/preview")
    @RequiredScope(Permission.VISUALIZATION_READ)
    fun preview(
        model: Model,
        @PathVariable id: UUID,
        @RequestParam(required = false) version: String?,
    ): String {
        val principal = currentPrincipal()
        return tabs.fillPreview(model, principal.requireWorkspace().id, lens.viewFor(principal), id, requiredVersion(version))
    }

    @GetMapping("/{id}/overview")
    @RequiredScope(Permission.VISUALIZATION_READ)
    fun overview(
        model: Model,
        @PathVariable id: UUID,
        @RequestParam(required = false) version: String?,
    ): String {
        val principal = currentPrincipal()
        return tabs.fillOverview(model, principal.requireWorkspace().id, lens.viewFor(principal), id, requiredVersion(version))
    }

    @GetMapping("/{id}/evidence")
    @RequiredScope(Permission.VISUALIZATION_READ)
    fun evidence(
        model: Model,
        @PathVariable id: UUID,
    ): String {
        val principal = currentPrincipal()
        return tabs.fillEvidence(model, principal.requireWorkspace().id, lens.viewFor(principal), id)
    }

    @GetMapping("/{id}/evidence/{runId}")
    @RequiredScope(Permission.VISUALIZATION_READ)
    fun evidenceRun(
        model: Model,
        @PathVariable id: UUID,
        @PathVariable runId: UUID,
    ): String {
        val principal = currentPrincipal()
        return tabs.fillEvidenceRun(model, principal.requireWorkspace().id, lens.viewFor(principal), id, runId)
    }

    @GetMapping("/{id}/used-by")
    @RequiredScope(Permission.VISUALIZATION_READ)
    fun usedBy(
        model: Model,
        @PathVariable id: UUID,
    ): String {
        val principal = currentPrincipal()
        return tabs.fillUsedBy(model, principal.requireWorkspace().id, lens.viewFor(principal), id)
    }

    /** Versions — the admitted history; the verbs' affordances ride THIS model too (the htmx fetch is a fresh model). */
    @GetMapping("/{id}/versions")
    @RequiredScope(Permission.VISUALIZATION_READ)
    fun versions(
        model: Model,
        @PathVariable id: UUID,
        @RequestParam(required = false) version: String?,
    ): String {
        val principal = currentPrincipal()
        VisualizationUiController.stampAffordances(model, principal)
        return tabs.fillVersions(
            model,
            principal.requireWorkspace().id,
            lens.viewFor(principal),
            id,
            VisualizationWorkspaceModel.parseRequestedVersion(version),
        )
    }

    /** A tab that renders ONE version needs it named: absent is the house 400, malformed the same non-echoing 400. */
    private fun requiredVersion(raw: String?): Int =
        VisualizationWorkspaceModel.parseRequestedVersion(raw)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, VisualizationWorkspaceModel.BAD_VERSION)
}
