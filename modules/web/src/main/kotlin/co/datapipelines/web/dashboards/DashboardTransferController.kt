package co.datapipelines.web.dashboards

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.visualization.ArtifactTransferService
import co.datapipelines.visualization.DashboardImport
import co.datapipelines.visualization.DashboardService
import co.datapipelines.web.api.ApiResponse
import co.datapipelines.web.api.currentPrincipal
import co.datapipelines.web.dashboards.runtime.DashboardAuditEvents
import co.datapipelines.web.pipelines.LifecycleVerbs
import co.datapipelines.web.visualizations.ArtifactFamily
import co.datapipelines.web.visualizations.ArtifactHttp
import co.datapipelines.web.visualizations.ArtifactResponses
import co.datapipelines.web.visualizations.VisualizationAuditEvents
import co.datapipelines.web.visualizations.VisualizationTransferController
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The dashboard transfer routes (rest-api.md §23; the implementation spec's §6.2/§12) — [VisualizationTransferController]'s
 * twin on the dashboards prefix, the parameter-set transfer mould. The acts live in [ArtifactTransferService]; what
 * is left is REST: the lens before the export, the body read, the audit.
 *
 * The dashboard's envelope bundles its pinned visualizations' OWN envelopes; its pinned pipelines and set travel BY
 * REFERENCE — a dashboard export assumes they were promoted first, and the import refuses
 * `dashboard.import.missing_dependency` when the receiver lacks a pin. The import is the workspace-admin verb
 * (the owner's ruling, exactly `visualization.import`'s row); `dashboard.exported` / `dashboard.imported` audit it.
 */
@RestController
@RequestMapping("/api/v1/dashboards")
class DashboardTransferController(
    private val transfer: ArtifactTransferService,
    private val dashboards: DashboardService,
    private val lens: PromoterLens,
    private val audit: AuditEventSink,
) {
    /** §23 — export (§12): the current release's envelope, its pinned visualizations' envelopes inside it. */
    @GetMapping("/{id}/export")
    @RequiredScope(Permission.DASHBOARD_READ)
    fun export(
        @PathVariable id: UUID,
    ): ApiResponse<JsonNode> {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        admit(principal, workspaceId, id)
        val envelope = transfer.exportDashboard(workspaceId, id)
        LifecycleVerbs.audit(
            audit,
            DashboardAuditEvents.EXPORTED,
            principal,
            workspaceId,
            mapOf(
                "dashboard_id" to id.toString(),
                "version" to envelope.path("manifest").path("dashboard_version").asInt(),
            ),
        )
        return ApiResponse.of(envelope)
    }

    /** §23 — import (§12): every bundled visualization (D61), then the dashboard; the exported id is KEPT (P24; C29). */
    @PostMapping("/import")
    @RequiredScope(Permission.DASHBOARD_IMPORT)
    fun import(
        @RequestBody body: String,
    ): ApiResponse<Map<String, Any?>> {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val envelope = ArtifactHttp.readTree(FAMILY, body)
        val imported = transfer.importDashboard(workspaceId, envelope, principal.userId)
        val dashboardDetail = imported.dashboard.detail
        LifecycleVerbs.audit(
            audit,
            DashboardAuditEvents.IMPORTED,
            principal,
            workspaceId,
            mapOf(
                "dashboard_id" to dashboardDetail.artifactId.toString(),
                "version" to dashboardDetail.version,
                "visualizations" to imported.visualizations.size,
                // The manifest's evidence summary recorded VERBATIM — `false` until L4's evidence tables exist.
                "imported_with_evidence" to envelope.path("manifest").path("evidence").let { !it.isMissingNode && !it.isNull },
            ),
        )
        // F1 (the L1c pass): every LANDED artifact is audited — one `visualization.imported` row per
        // bundled visualization, after the import's commit, each naming its id, its version and ITS OWN
        // envelope manifest's evidence flag. The atomic import (one transaction) means these rows never
        // name a visualization a refused dashboard left behind.
        imported.visualizations.forEachIndexed { index, visualization ->
            LifecycleVerbs.audit(
                audit,
                VisualizationAuditEvents.IMPORTED,
                principal,
                workspaceId,
                mapOf(
                    "visualization_id" to visualization.detail.artifactId.toString(),
                    "version" to visualization.detail.version,
                    "imported_with_evidence" to
                        envelope
                            .path("visualizations")
                            .path(index)
                            .path("manifest")
                            .path("evidence")
                            .let { !it.isMissingNode && !it.isNull },
                ),
            )
        }
        return ApiResponse.of(imported.asResponse())
    }

    private fun admit(
        principal: AuthenticatedPrincipal,
        workspaceId: UUID,
        id: UUID,
    ) {
        // ONE view per request (the lens reads the pins-and-sources projection once, #330); the working read
        // names the record, a hidden or draft-only dashboard is the family's 404.
        val view = lens.viewFor(principal).dashboards
        val loaded =
            dashboards.findWorking(workspaceId, view, id)
                ?: throw FAMILY.notFound(id.toString())
        if (!view.admits(loaded.record.name)) throw FAMILY.notFound(id.toString())
    }

    companion object {
        private val FAMILY = ArtifactFamily.DASHBOARD

        private fun DashboardImport.asResponse(): Map<String, Any?> =
            mapOf(
                "dashboard" to (
                    ArtifactResponses.lifecycleSummary(dashboard.detail) +
                        mapOf("import_created" to dashboard.created, "import_unchanged" to dashboard.unchanged)
                ),
                "visualizations" to
                    visualizations.map {
                        ArtifactResponses.lifecycleSummary(it.detail) +
                            mapOf("import_created" to it.created, "import_unchanged" to it.unchanged)
                    },
            )
    }
}
