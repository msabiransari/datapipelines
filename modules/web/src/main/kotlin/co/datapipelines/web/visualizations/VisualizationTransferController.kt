package co.datapipelines.web.visualizations

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.visualization.ArtifactImported
import co.datapipelines.visualization.ArtifactTransferService
import co.datapipelines.visualization.VisualizationService
import co.datapipelines.web.api.ApiResponse
import co.datapipelines.web.api.currentPrincipal
import co.datapipelines.web.pipelines.LifecycleVerbs
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The visualization transfer routes (rest-api.md §22; the implementation spec's §6.1/§12) — the parameter-set
 * transfer mould ([co.datapipelines.web.parameters.ParameterSetsController] `export`/`import`), a separate class on
 * the same prefix as [VisualizationsController] (Spring allows it). The acts live in [ArtifactTransferService];
 * what is left is REST: the lens before the export, the body read, the audit.
 *
 * ## The lens precedes the export
 * A promoter's hidden visualization is the family's 404 — the lens check runs on the record's NAME before the
 * envelope is built (the set mould; never an existence oracle). The export itself is released-only.
 *
 * ## The import is a workspace-admin verb
 * The owner's ruling (2026-09-29, the `api_key.bind` cells): the envelope lands RELEASED with no evidence re-run —
 * the D56 promise traveled WITH the exported release — so an author never holds the verb. The receiver lands
 * promoted artifacts through the promotion WIRE, never through this route.
 *
 * ## Audit (#332's rule for the transfer verbs)
 * `visualization.exported` / `visualization.imported` — the pipelines' lifecycle-audit shape: ids, versions and
 * counts, and `imported_with_evidence` recording the envelope manifest's evidence summary verbatim (`false` until
 * the evidence tables land, L4 — the manifest says `evidence: null`).
 */
@RestController
@RequestMapping("/api/v1/visualizations")
class VisualizationTransferController(
    private val transfer: ArtifactTransferService,
    private val visualizations: VisualizationService,
    private val lens: PromoterLens,
    private val audit: AuditEventSink,
) {
    /** §22 — export (§12): the current release's envelope, its transform pin's templates bundled. */
    @GetMapping("/{id}/export")
    @RequiredScope(Permission.VISUALIZATION_READ)
    fun export(
        @PathVariable id: UUID,
    ): ApiResponse<JsonNode> {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        // The promoter lens on the export read (the set mould): a hidden visualization exports as an absent one.
        admit(principal, workspaceId, id)
        val envelope = transfer.exportVisualization(workspaceId, id)
        LifecycleVerbs.audit(
            audit,
            AUDIT_EXPORTED,
            principal,
            workspaceId,
            mapOf(
                "visualization_id" to id.toString(),
                "version" to envelope.path("manifest").path("visualization_version").asInt(),
            ),
        )
        return ApiResponse.of(envelope)
    }

    /** §22 — import (§12): templates first, then the visualization; the exported id is KEPT (P24; C29 refuses a taken id). */
    @PostMapping("/import")
    @RequiredScope(Permission.VISUALIZATION_IMPORT)
    fun import(
        @RequestBody body: String,
    ): ApiResponse<Map<String, Any?>> {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val envelope = ArtifactHttp.readTree(FAMILY, body)
        val imported = transfer.importVisualization(workspaceId, envelope, principal.userId)
        LifecycleVerbs.audit(
            audit,
            AUDIT_IMPORTED,
            principal,
            workspaceId,
            mapOf(
                "visualization_id" to imported.detail.artifactId.toString(),
                "version" to imported.detail.version,
                // The manifest's evidence summary recorded VERBATIM — `false` until L4's evidence tables exist.
                "imported_with_evidence" to envelope.path("manifest").path("evidence").let { !it.isMissingNode && !it.isNull },
            ),
        )
        return ApiResponse.of(imported.asResponse())
    }

    private fun admit(
        principal: AuthenticatedPrincipal,
        workspaceId: UUID,
        id: UUID,
    ) {
        // The working read names the record; under a narrowing lens it answers RELEASED-only, so a hidden or
        // draft-only artifact is the same 404 an absent id gets (never a hint). ONE view per request — the
        // derivation reads every released dashboard's body (#330).
        val view = lens.viewFor(principal).visualizations
        val loaded =
            visualizations.findWorking(workspaceId, view, id)
                ?: throw FAMILY.notFound(id.toString())
        if (!view.admits(loaded.record.name)) throw FAMILY.notFound(id.toString())
    }

    companion object {
        private val FAMILY = ArtifactFamily.VISUALIZATION

        /** enums.md §15 — the transfer's audit events (the pipelines' lifecycle shape). */
        const val AUDIT_EXPORTED = "visualization.exported"
        const val AUDIT_IMPORTED = "visualization.imported"

        private fun ArtifactImported.asResponse(): Map<String, Any?> =
            ArtifactResponses.lifecycleSummary(detail) +
                mapOf("import_created" to created, "import_unchanged" to unchanged)
    }
}
