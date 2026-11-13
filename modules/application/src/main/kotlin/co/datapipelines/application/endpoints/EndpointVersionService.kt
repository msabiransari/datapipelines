package co.datapipelines.application.endpoints

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.guard.MetadataWriterGuard
import org.springframework.transaction.support.TransactionOperations
import java.util.UUID

/**
 * An API's version lifecycle outside a release (dependency-graph record §24 "API lifecycle (R4)"; brief A.3): the draft
 * save under `If-Match`, the draft discard (a PURGE — §24 has no discarded-draft status), discard / restore / purge of
 * an exact version and the explicit current switch (A06). Releasing is the coordinator's ([EndpointReleaseSteps]).
 *
 * Every verb is an authoring operation: a deployment with authoring disabled refuses it with `endpoint.authoring_disabled`
 * regardless of role (§12). Every write joins the G0 protocol (record §15.1): the workspace guard first, the registry
 * key only where path ABSENCE changes (a purge of the sole version deletes the entity), then the entity row lock
 * (class 4), then the version CAS. Audits are emitted after the write returns, with ids, numbers and names only.
 *
 * Inbound protection: no family pins an API version today (record §14 — APIs have no inbound pinners); [refuseIfPinned]
 * is the hook a future pinner plugs into, consulted by discard and purge exactly where the pipeline verbs consult theirs.
 */
@Suppress("LongParameterList", "TooManyFunctions") // one collaborator per concern; one function per lifecycle verb
class EndpointVersionService(
    private val endpoints: PublishedEndpointRepository,
    private val versions: PublishedEndpointVersionRepository,
    private val pins: EndpointPinValidator,
    private val restorePins: EndpointRestorePins,
    registry: EndpointRegistry,
    private val audit: AuditEventSink,
    private val timeouts: EndpointPublishService.TimeoutBounds,
    private val authoring: AuthoringGuard,
    private val lens: PromoterLens? = null,
    transactions: TransactionOperations? = null,
    writeGuard: MetadataWriterGuard = MetadataWriterGuard.NONE,
) {
    private val writes = EndpointWrites(endpoints, versions, registry, transactions, writeGuard)

    /** The authored draft fields; a null timeout or description keeps the base version's value. */
    data class DraftInput(
        val pipelineName: String,
        val pipelineVersion: Int,
        val timeoutSeconds: Int?,
        val description: String?,
    )

    /** A version as the surfaces list it, with whether it is current and eligible under the caller's posture. */
    data class VersionView(
        val version: PublishedEndpointVersion,
        val isCurrent: Boolean,
        val eligible: Boolean,
    )

    /** What a pointer-moving verb did: the version touched and the pointer before and after. */
    data class Moved(
        val version: Int,
        val currentBefore: Int?,
        val currentAfter: Int?,
    )

    /** What a purge removed: the version, or the whole entity when it was the sole version. */
    data class Purged(
        val version: Int,
        val entityDeleted: Boolean,
    )

    /** Every version of a visible API, newest first; null when the API is not visible to [principal]. */
    fun versions(
        principal: AuthenticatedPrincipal,
        endpointId: UUID,
    ): List<VersionView>? {
        val endpoint = visible(principal, endpointId) ?: return null
        val view = lens?.viewFor(principal)?.takeIf { it.isLensed }?.pipelines
        return versions
            .list(endpoint.id)
            .filter { view == null || view.admits(it.body.pipelineName) }
            .map { VersionView(it, it.version == endpoint.current?.version, pins.eligible(it.status)) }
    }

    /** One version of a visible API, or null. */
    fun version(
        principal: AuthenticatedPrincipal,
        endpointId: UUID,
        version: Int,
    ): VersionView? = versions(principal, endpointId)?.firstOrNull { it.version.version == version }

    /**
     * Saves the working DRAFT: the existing draft's body at the same number under `If-Match: <its body_hash>`, or a new
     * draft at `max+1` under `If-Match: <the current version's body_hash>` when none exists (a stale base is a conflict
     * either way). The pin passes R3 at save (`endpoint.pipeline_version.not_eligible`).
     */
    fun saveDraft(
        principal: AuthenticatedPrincipal,
        endpointId: UUID,
        input: DraftInput,
        ifMatch: String,
    ): PublishedEndpointVersion {
        EndpointWrites.requireAuthoring(authoring)
        val saved =
            guarded(principal, endpointId) { endpoint ->
                val draft = versions.findDraft(endpoint.id)
                val base = draft ?: versions.findCurrent(endpoint.id) ?: versions.list(endpoint.id).firstOrNull()
                if (base == null || base.bodyHash != ifMatch) throw conflict(endpoint, base)
                val body =
                    EndpointWrites.bodyOf(
                        input.pipelineName,
                        input.pipelineVersion,
                        timeouts.clamp(input.timeoutSeconds ?: base.body.timeoutSeconds),
                        input.description ?: base.body.description,
                    )
                pins.check(endpoint.workspaceId, endpoint.parsed, body, EndpointPinValidator.Admission.ELIGIBLE)
                val written =
                    if (draft != null) {
                        versions.updateDraft(endpoint.id, draft.version, body, ifMatch, principal.userId) ?: throw conflict(endpoint, draft)
                    } else {
                        versions.insertDraft(endpoint.id, body, principal.userId)
                    }
                // The pipeline's creation rule: a draft becomes current only when there is no current and it is eligible.
                if (endpoint.current == null && pins.eligible(written.status)) versions.setCurrent(endpoint.id, written.version)
                endpoint to written
            }
        auditOf(principal, AUDIT_DRAFT_SAVED, saved.first, saved.second.version, bodyDetails(saved.second) + ("created" to false))
        return saved.second
    }

    /** Purges the working DRAFT under `If-Match` (the editor's discard; §24: no discarded-draft status). */
    fun discardDraft(
        principal: AuthenticatedPrincipal,
        endpointId: UUID,
        ifMatch: String,
    ): Purged {
        EndpointWrites.requireAuthoring(authoring)
        return purge(principal, endpointId) { endpoint ->
            val draft = versions.findDraft(endpoint.id) ?: throw notFound(endpoint.id, version = null)
            if (draft.bodyHash != ifMatch) throw conflict(endpoint, draft)
            draft
        }
    }

    /** Purges DRAFT [version] (drafts only); the sole version takes the entity with it. */
    fun purgeVersion(
        principal: AuthenticatedPrincipal,
        endpointId: UUID,
        version: Int,
    ): Purged {
        EndpointWrites.requireAuthoring(authoring)
        return purge(principal, endpointId) { endpoint ->
            val found = versions.find(endpoint.id, version) ?: throw notFound(endpoint.id, version)
            if (found.status != PipelineVersionStatus.DRAFT) throw wrongStatus(PipelineErrorCodes.Endpoint.VERSION_NOT_DRAFT, found)
            found
        }
    }

    /** RELEASED [version] → DISCARDED; when it was current the pointer recomputes (latest RELEASED, else the eligible draft). */
    fun discardVersion(
        principal: AuthenticatedPrincipal,
        endpointId: UUID,
        version: Int,
    ): Moved {
        EndpointWrites.requireAuthoring(authoring)
        val (endpoint, moved) =
            guarded(principal, endpointId) { endpoint ->
                val found = versions.find(endpoint.id, version) ?: throw notFound(endpoint.id, version)
                if (found.status !=
                    PipelineVersionStatus.RELEASED
                ) {
                    throw wrongStatus(PipelineErrorCodes.Endpoint.VERSION_NOT_RELEASED, found)
                }
                refuseIfPinned(endpoint, found)
                versions.discard(endpoint.id, version, principal.userId, draftEligible = authoring.developmentPosture)
                endpoint to Moved(version, endpoint.current?.version, pointerOf(endpoint.id))
            }
        auditOf(principal, AUDIT_VERSION_DISCARDED, endpoint, version, pointerDetails(moved))
        return moved
    }

    /** DISCARDED [version] → RELEASED after the outbound recheck ([EndpointRestorePins]); the pointer moves only upward. */
    fun restoreVersion(
        principal: AuthenticatedPrincipal,
        endpointId: UUID,
        version: Int,
    ): Moved {
        EndpointWrites.requireAuthoring(authoring)
        val (endpoint, moved) =
            guarded(principal, endpointId) { endpoint ->
                val found = versions.find(endpoint.id, version) ?: throw notFound(endpoint.id, version)
                if (found.status != PipelineVersionStatus.DISCARDED) {
                    throw wrongStatus(PipelineErrorCodes.Endpoint.VERSION_NOT_DISCARDED, found)
                }
                restorePins.recheck(endpoint.workspaceId, endpoint.pathPattern, found, principal.userId)
                versions.restore(endpoint.id, version)
                endpoint to Moved(version, endpoint.current?.version, pointerOf(endpoint.id))
            }
        auditOf(principal, AUDIT_VERSION_RESTORED, endpoint, version, pointerDetails(moved))
        return moved
    }

    /**
     * The explicit API-current switch (A06): `current = version`, which must be eligible under the posture, and whose pin
     * must still pass R3 — a switch never points serving at a version that would refuse.
     */
    fun switchCurrent(
        principal: AuthenticatedPrincipal,
        endpointId: UUID,
        version: Int,
    ): Moved {
        EndpointWrites.requireAuthoring(authoring)
        val (endpoint, moved) =
            guarded(principal, endpointId) { endpoint ->
                val found = versions.find(endpoint.id, version) ?: throw notFound(endpoint.id, version)
                if (!pins.eligible(found.status)) throw wrongStatus(PipelineErrorCodes.Endpoint.VERSION_NOT_ELIGIBLE, found)
                pins.check(endpoint.workspaceId, endpoint.parsed, found.body, EndpointPinValidator.Admission.ELIGIBLE)
                versions.setCurrent(endpoint.id, version)
                endpoint to Moved(version, endpoint.current?.version, version)
            }
        auditOf(principal, AUDIT_CURRENT_SWITCHED, endpoint, version, mapOf("from" to moved.currentBefore, "to" to moved.currentAfter))
        return moved
    }

    /** The shared purge: the registry key is taken because a sole-version purge deletes the entity (path absence). */
    private fun purge(
        principal: AuthenticatedPrincipal,
        endpointId: UUID,
        target: (PublishedEndpoint) -> PublishedEndpointVersion,
    ): Purged {
        val (endpoint, purged) =
            guarded(principal, endpointId, registryKey = true) { endpoint ->
                val found = target(endpoint)
                refuseIfPinned(endpoint, found)
                val sole = versions.list(endpoint.id).size == 1
                if (sole) {
                    endpoints.deleteById(endpoint.id)
                    writes.invalidateAfterCommit()
                } else {
                    versions.purgeDraft(endpoint.id, found.version, draftEligible = authoring.developmentPosture)
                }
                endpoint to Purged(found.version, entityDeleted = sole)
            }
        auditOf(
            principal,
            AUDIT_VERSION_PURGED,
            endpoint,
            purged.version,
            mapOf("scope" to if (purged.entityDeleted) "entity" else "version"),
        )
        return purged
    }

    /**
     * [block] in the metadata transaction under the G0 protocol: the workspace guard, the registry key when asked, then
     * the entity row lock; the entity is re-read under them, so a concurrent write is judged on what is stored.
     */
    private fun <T> guarded(
        principal: AuthenticatedPrincipal,
        endpointId: UUID,
        registryKey: Boolean = false,
        block: (PublishedEndpoint) -> T,
    ): T {
        val workspaceId = principal.requireWorkspace().id
        return writes.inTransaction {
            writes.writeGuard.workspaces(listOf(workspaceId), PipelineErrorCodes.Endpoint.WRITE_BUSY)
            if (registryKey) writes.writeGuard.endpointRegistry(PipelineErrorCodes.Endpoint.WRITE_BUSY)
            versions.lockEntity(workspaceId, endpointId) ?: throw notFound(endpointId, version = null)
            val endpoint = endpoints.findById(workspaceId, endpointId) ?: throw notFound(endpointId, null)
            block(endpoint)
        }
    }

    /** An API of the caller's workspace the lens admits, or null (absent and hidden answer alike). */
    private fun visible(
        principal: AuthenticatedPrincipal,
        endpointId: UUID,
    ): PublishedEndpoint? {
        val endpoint = endpoints.findById(principal.requireWorkspace().id, endpointId) ?: return null
        val view = lens?.viewFor(principal)?.takeIf { it.isLensed }?.pipelines ?: return endpoint
        return endpoint.takeIf { e -> e.pipelineName?.let(view::admits) ?: false }
    }

    /** The hook for an inbound pinner of an API version (none today, record §14); refuses `endpoint.version.pinned`. */
    @Suppress("UnusedParameter") // the hook's shape is the pipeline verbs'; no family pins an API version yet
    private fun refuseIfPinned(
        endpoint: PublishedEndpoint,
        version: PublishedEndpointVersion,
    ) = Unit

    private fun pointerOf(endpointId: UUID): Int? = versions.lockEntity(endpointId)?.currentVersion

    private fun bodyDetails(version: PublishedEndpointVersion): Map<String, Any?> =
        mapOf(
            "pipeline" to version.body.pipelineName,
            "pipeline_version" to version.body.pipelineVersion,
            "timeout_seconds" to version.body.timeoutSeconds,
        )

    private fun pointerDetails(moved: Moved): Map<String, Any?> =
        mapOf("current_version_before" to moved.currentBefore, "current_version_after" to moved.currentAfter)

    private fun auditOf(
        principal: AuthenticatedPrincipal,
        event: String,
        endpoint: PublishedEndpoint,
        version: Int,
        details: Map<String, Any?>,
    ) = audit.log(
        event = event,
        userId = principal.userId,
        keyId = principal.keyId,
        details =
            mapOf(
                "endpoint_id" to endpoint.id.toString(),
                "path_pattern" to endpoint.pathPattern,
                "version" to version,
                "workspace_id" to endpoint.workspaceId.toString(),
            ) + details,
    )

    private fun notFound(
        endpointId: UUID,
        version: Int?,
    ) = DatapipelinesException(
        code = PipelineErrorCodes.Endpoint.VERSION_NOT_FOUND,
        message = if (version == null) "No such API, or it has no such version." else "This API has no version $version.",
        details =
            buildMap {
                put("endpoint_id", endpointId.toString())
                if (version != null) put("version", version)
            },
    )

    private fun wrongStatus(
        code: String,
        version: PublishedEndpointVersion,
    ) = DatapipelinesException(
        code = code,
        message = "Version ${version.version} of this API is ${version.status.name}; that verb does not apply to it.",
        details = mapOf("endpoint_id" to version.endpointId.toString(), "version" to version.version, "status" to version.status.name),
    )

    private fun conflict(
        endpoint: PublishedEndpoint,
        base: PublishedEndpointVersion?,
    ) = DatapipelinesException(
        code = PipelineErrorCodes.Endpoint.VERSION_CONFLICT,
        message = "This API changed since you read it: re-read its versions and save on top of the current body_hash.",
        details =
            buildMap {
                put("endpoint_id", endpoint.id.toString())
                base?.let { put("body_hash", it.bodyHash) }
            },
    )

    companion object {
        /** enums.md — the API version lifecycle's audit kinds (brief A.3). */
        const val AUDIT_DRAFT_SAVED = EndpointPublishService.AUDIT_DRAFT_SAVED
        const val AUDIT_VERSION_DISCARDED = "endpoint.version_discarded"
        const val AUDIT_VERSION_RESTORED = "endpoint.version_restored"
        const val AUDIT_VERSION_PURGED = "endpoint.version_purged"
        const val AUDIT_CURRENT_SWITCHED = "endpoint.current_switched"
    }
}
