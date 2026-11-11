package co.datapipelines.application.endpoints

import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.ReleaseRefusals
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.guard.MetadataWriterGuard
import java.util.UUID

/**
 * The API family's two release steps for the release coordinator's participant (record §8, A42; brief C.2), split at the
 * transaction boundary like every family's:
 *
 * - [prepare], OUTSIDE the metadata transaction: the authoring capability, the entity and its exact DRAFT at the
 *   consented hash, and the pin's native checks with a DRAFT pipeline admitted — the coordinator's blockers already
 *   refused a draft pin the plan does not flip, so one that reaches here flips first in the same transaction.
 * - [commit], INSIDE it, after the workspace guard and every dependency's flip: the entity row lock, the pin rechecked
 *   RELEASED-only (now that the pinned pipeline version is), then the one-statement CAS flip at the consented hash, which
 *   also moves the API's current pointer to the released version (the pipeline rule). A CAS miss is the ROOT's
 *   `plan_conflict`.
 */
class EndpointReleaseSteps(
    private val endpoints: PublishedEndpointRepository,
    private val versions: PublishedEndpointVersionRepository,
    private val pins: EndpointPinValidator,
    private val authoring: AuthoringGuard,
    registry: EndpointRegistry,
) {
    /** The registry snapshot carries each entity's current body: a release moves it, so the commit invalidates it. */
    private val writes = EndpointWrites(endpoints, versions, registry, transactions = null, writeGuard = MetadataWriterGuard.NONE)

    /** A prepared API release: what the commit flips. */
    data class Prepared(
        val endpoint: PublishedEndpoint,
        val version: PublishedEndpointVersion,
        val expectedHash: String,
        val actor: UUID,
    )

    /** The API's exact release identity from its id — the root a release request names; null when the API is absent. */
    fun rootIdentity(
        workspaceId: UUID,
        endpointId: UUID,
    ): ReleaseNodeIdentity? {
        val endpoint = endpoints.findById(endpointId)?.takeIf { it.workspaceId == workspaceId } ?: return null
        val draft = versions.findDraft(endpoint.id) ?: throw noDraft(endpoint)
        return ReleaseNodeIdentity(workspaceId, ReleaseFamily.ENDPOINT, endpoint.pathPattern, draft.version)
    }

    /** Step 4: the native checks of the API draft at [identity], against [expectedHash] (the root's `If-Match`). */
    @Suppress("ThrowsCount") // plan conflict, not-a-draft and the If-Match conflict are distinct catalogued refusals
    fun prepare(
        identity: ReleaseNodeIdentity,
        entityUuid: UUID?,
        expectedHash: String,
        actor: UUID,
    ): Prepared {
        EndpointWrites.requireAuthoring(authoring)
        val endpoint =
            entityUuid?.let(endpoints::findById)?.takeIf {
                it.workspaceId == identity.workspaceId && it.pathPattern == identity.entityId
            } ?: throw ReleaseRefusals.planConflict(ReleaseFamily.ENDPOINT)
        val version = versions.find(endpoint.id, identity.version) ?: throw ReleaseRefusals.planConflict(ReleaseFamily.ENDPOINT)
        if (version.status != PipelineVersionStatus.DRAFT) throw noDraft(endpoint)
        if (version.bodyHash != expectedHash) throw conflict(endpoint, version)
        pins.check(identity.workspaceId, endpoint.parsed, version.body, EndpointPinValidator.Admission.LIVE)
        return Prepared(endpoint, version, expectedHash, actor)
    }

    /** Step 6, the API last: the pin rechecked RELEASED-only, then the CAS flip; the released version. */
    fun commit(prepared: Prepared): PublishedEndpointVersion {
        val endpoint = prepared.endpoint
        versions.lockEntity(endpoint.id) ?: throw ReleaseRefusals.planConflict(ReleaseFamily.ENDPOINT)
        pins.check(endpoint.workspaceId, endpoint.parsed, prepared.version.body, EndpointPinValidator.Admission.RELEASED_ONLY)
        val released =
            versions.release(endpoint.id, prepared.version.version, prepared.expectedHash, prepared.actor)
                ?: throw ReleaseRefusals.planConflict(ReleaseFamily.ENDPOINT)
        writes.invalidateAfterCommit()
        return released
    }

    private fun noDraft(endpoint: PublishedEndpoint) =
        DatapipelinesException(
            code = PipelineErrorCodes.Endpoint.VERSION_NOT_DRAFT,
            message = "This API has no working draft to release; save a draft first.",
            details = mapOf("endpoint_id" to endpoint.id.toString()),
        )

    private fun conflict(
        endpoint: PublishedEndpoint,
        version: PublishedEndpointVersion,
    ) = DatapipelinesException(
        code = PipelineErrorCodes.Endpoint.VERSION_CONFLICT,
        message = "The draft changed since you read it: re-read it and release the current body_hash.",
        details = mapOf("endpoint_id" to endpoint.id.toString(), "version" to version.version, "body_hash" to version.bodyHash),
    )
}
