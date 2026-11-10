package co.datapipelines.application.endpoints

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.typesystem.guard.MetadataWriterGuard
import org.springframework.transaction.support.TransactionOperations

/**
 * Creating, listing and deleting API entities (published-endpoints design §4.2, §6; dependency-graph record §24, #786) —
 * the one validated path the REST surface and the MCP tool share.
 *
 * ## Creation saves a DRAFT (R4, owner ruling 4 of 2026-11-08)
 *
 * [create] writes the entity AND its version 1 — a DRAFT pinned to the EXACT pipeline version the caller names — in one
 * transaction under the G0 protocol: the workspace guard, the pin's checks under it, then the registry key (path
 * absence matters) and the inserts. Creation is an authoring operation: a deployment with authoring disabled refuses it
 * (`endpoint.authoring_disabled`). In an authoring-enabled deployment that draft is the entity's current version and
 * serves under the normal runtime checks; the pin never moves when the pipeline's current version moves. Releasing it
 * is the release coordinator's (`POST /api/v1/endpoints/{id}/release`), never a creation shortcut.
 *
 * ## Pipelines are named, not id'd
 *
 * A version names its pipeline the way everything portable does (pipeline-contract §11.1): the NAME and the exact
 * version number, so the same body means the same thing on every deployment (record §5.3, A44).
 *
 * ## Order of checks
 *
 * Capability, grammar, then the pin (existence, exact version, eligibility, read-only, path variables — the shared
 * [EndpointPinValidator]), then the clamp, then the conflict: cheapest and most specific first.
 */
@Suppress("LongParameterList") // one collaborator per concern of the one creation path
class EndpointPublishService(
    private val endpoints: PublishedEndpointRepository,
    versions: PublishedEndpointVersionRepository,
    private val pins: EndpointPinValidator,
    registry: EndpointRegistry,
    private val audit: AuditEventSink,
    private val timeouts: TimeoutBounds,
    private val authoring: AuthoringGuard,
    /**
     * 178 — the promoter lens on the reads: an API is visible iff the pipeline its current version pins is (auth.md §7.6
     * puts `endpoints_list/get` and `GET /api/v1/endpoints` in the promoter's `lens` cell), so a hidden pipeline never
     * leaks through the API that serves it. Null keeps the unlensed behaviour.
     */
    private val lens: PromoterLens? = null,
    /**
     * #841 (G0) — the metadata transaction creation and deletion run in (joining the caller's when there is one). Null
     * runs the block bare — the directly constructed unit-test shape, with [writeGuard] at [MetadataWriterGuard.NONE].
     */
    transactions: TransactionOperations? = null,
    /** #841 — the writer guard; an exhausted wait refuses `endpoint.write.busy` (409) with nothing written. */
    writeGuard: MetadataWriterGuard = MetadataWriterGuard.NONE,
) {
    private val writes = EndpointWrites(endpoints, versions, registry, transactions, writeGuard)

    /** The §5.5 clamp, passed in so `modules/application` reads no configuration itself. */
    data class TimeoutBounds(
        val defaultSeconds: Int,
        val minSeconds: Int,
        val maxSeconds: Int,
    ) {
        fun clamp(requested: Int?): Int = (requested ?: defaultSeconds).coerceIn(minSeconds, maxSeconds)
    }

    /**
     * Creates the API at [pathPattern] with its version 1 DRAFT pinned to [pipelineName] at exactly [pipelineVersion]
     * (R3: the pinned version must be eligible under this deployment's posture — `endpoint.pipeline_version.not_eligible`).
     */
    @Suppress("LongParameterList") // the authored creation inputs, each its own field of the request
    fun create(
        principal: AuthenticatedPrincipal,
        pathPattern: String,
        pipelineName: String,
        pipelineVersion: Int,
        timeoutSeconds: Int?,
        description: String,
    ): PublishedEndpoint {
        EndpointWrites.requireAuthoring(authoring)
        val workspaceId = principal.requireWorkspace().id
        val parsed = EndpointWrites.parsePath(pathPattern)
        val body = EndpointWrites.bodyOf(pipelineName, pipelineVersion, timeouts.clamp(timeoutSeconds), description)
        val endpoint =
            writes.inTransaction {
                // #841: workspace guard → the pin's checks under it → registry key → overlap check + inserts.
                writes.writeGuard.workspaces(listOf(workspaceId), PipelineErrorCodes.Endpoint.WRITE_BUSY)
                val resolved = pins.check(workspaceId, parsed, body, EndpointPinValidator.Admission.ELIGIBLE)
                writes.createWithFirstVersion(workspaceId, parsed, resolved.record.id, body, PipelineVersionStatus.DRAFT, principal.userId)
            }
        audit.log(
            event = AUDIT_DRAFT_SAVED,
            userId = principal.userId,
            keyId = principal.keyId,
            details =
                mapOf(
                    "endpoint_id" to endpoint.id.toString(),
                    "path_pattern" to endpoint.pathPattern,
                    "version" to EndpointWrites.FIRST_VERSION,
                    "pipeline" to body.pipelineName,
                    "pipeline_version" to body.pipelineVersion,
                    "timeout_seconds" to body.timeoutSeconds,
                    "created" to true,
                ),
        )
        return endpoint
    }

    /**
     * Deletes the API at a path, every version with it (entity deletion, unchanged). Returns false when nothing exists
     * there. Reads the ROW (valid or legacy): deleting a legacy row IS the fix for it (#274).
     */
    fun unpublish(
        principal: AuthenticatedPrincipal,
        pathPattern: String,
    ): Boolean {
        val workspaceId = principal.requireWorkspace().id
        // Normalised like a creation (R-EP5): the stored form never carries the `/api` prefix.
        val stored = EndpointPath.normalize(pathPattern)
        val existing = endpoints.findRowByPath(stored) ?: return false
        // Another workspace's API is invisible rather than forbidden — the not-found discipline of every scoped read.
        if (existing.workspaceId != workspaceId && !principal.isSuperAdmin) return false

        // #841: the delete changes path absence, so it takes the row's workspace guard and the registry key; the row is
        // re-read under them, so a concurrent delete/re-create is judged on what is stored.
        val removed =
            writes.inTransaction {
                writes.writeGuard.workspaces(listOf(existing.workspaceId), PipelineErrorCodes.Endpoint.WRITE_BUSY)
                writes.writeGuard.endpointRegistry(PipelineErrorCodes.Endpoint.WRITE_BUSY)
                val deleted =
                    endpoints.findRowByPath(stored)?.takeIf { it.id == existing.id }?.let { endpoints.deleteByPath(stored) } ?: false
                if (deleted) writes.invalidateAfterCommit()
                deleted
            }
        if (removed) {
            audit.log(
                event = AUDIT_UNPUBLISHED,
                userId = principal.userId,
                keyId = principal.keyId,
                details = mapOf("endpoint_id" to existing.id.toString(), "path_pattern" to stored),
            )
        }
        return removed
    }

    /** The APIs of the caller's workspace (§6's listing), through the promoter lens. */
    fun list(principal: AuthenticatedPrincipal): List<PublishedEndpoint> {
        val all = endpoints.findByWorkspace(principal.requireWorkspace().id)
        val lensed = pipelineLens(principal) ?: return all
        return all.filter { visible(it, lensed) }
    }

    /** The legacy rows of the caller's workspace, flagged (#274), through the same lens as [list]. */
    fun listLegacy(principal: AuthenticatedPrincipal): List<EndpointRow.Legacy> {
        val legacy = endpoints.findLegacy(principal.requireWorkspace().id)
        val lensed = pipelineLens(principal) ?: return legacy
        return legacy.filter { row -> row.pipelineName?.let(lensed::admits) ?: false }
    }

    /** One API by path, or null when absent, in another workspace, or serving a pipeline the lens hides. */
    fun get(
        principal: AuthenticatedPrincipal,
        pathPattern: String,
    ): PublishedEndpoint? = visibleOrNull(principal, endpoints.findByPath(EndpointPath.normalize(pathPattern)))

    /** One API by id, under the same visibility as [get]. */
    fun getById(
        principal: AuthenticatedPrincipal,
        id: java.util.UUID,
    ): PublishedEndpoint? = visibleOrNull(principal, endpoints.findById(id))

    private fun visibleOrNull(
        principal: AuthenticatedPrincipal,
        endpoint: PublishedEndpoint?,
    ): PublishedEndpoint? {
        val found = endpoint?.takeIf { it.workspaceId == principal.requireWorkspace().id || principal.isSuperAdmin } ?: return null
        val lensed = pipelineLens(principal) ?: return found
        return found.takeIf { visible(it, lensed) }
    }

    /** The promoter's pipeline lens, or null when the view does not narrow (every role but the promoter — no cost). */
    private fun pipelineLens(principal: AuthenticatedPrincipal): ReadLens? {
        val view = lens?.viewFor(principal) ?: return null
        return view.pipelines.takeIf { view.isLensed }
    }

    /** An API with no current version pins nothing a lens could admit, so a lensed reader never sees it. */
    private fun visible(
        endpoint: PublishedEndpoint,
        lensed: ReadLens,
    ): Boolean = endpoint.pipelineName?.let(lensed::admits) ?: false

    companion object {
        /** enums.md — every API draft save, creation included (`created: true`); `endpoint.published` is history only. */
        const val AUDIT_DRAFT_SAVED = "endpoint.draft_saved"
        const val AUDIT_UNPUBLISHED = "endpoint.unpublished"
    }
}
