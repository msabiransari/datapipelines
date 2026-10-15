package co.datapipelines.web.ui

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.web.api.currentPrincipal
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
 * - the CATALOG's swap root, `GET /partials/visualizations` (the sidebar's tree levels,
 *   `GET /partials/visualizations/tree`, are gone with #466 — the sidebar has read
 *   `/api/v1/visualizations/tree` since #460);
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
