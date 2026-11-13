package co.datapipelines.application.dependencies

import co.datapipelines.application.endpoints.EndpointVersionBody
import co.datapipelines.application.endpoints.PublishedEndpointVersionRepository
import co.datapipelines.dependencies.Operational
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.release.PipelineDependencySource
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.ReleasePin
import co.datapipelines.pipeline.release.ResolvedNode

/**
 * The API family's read of the release graph (#786; dependency-graph record §6 `PIPELINE_PIN`, §8 "an API draft version
 * is a releasable root", §11.1): an API version is looked up by its workspace and its IMMUTABLE path (the family's pin
 * key — a path change makes another entity, §24) at an exact version; its one outgoing pin is the exact pipeline version
 * its body names, at field path `pipeline_version`. Registered through G1's [ReleaseDependencyFamilies.descriptor], so
 * the walk, the batching and the savepoint isolation are G1's; this class adds a family, not a walker.
 *
 * A DISCARDED version resolves (a restore recheck names it) but its pin is never walked, the rule every release source
 * follows. An absent path or version answers null — the graph's MISSING.
 *
 * #902 (record §11.1 `api_endpoint { enabled }`): the node's operational fact is the entity's `is_enabled`, read by the
 * same `findByPath` statement as the version row — entity-level, so a DISCARDED version shows it too; shown, never
 * hashed (§5.3).
 */
class EndpointDependencySource(
    private val versions: PublishedEndpointVersionRepository,
) : OperationalDependencySource {
    override fun read(identity: ReleaseNodeIdentity): SourceRead? {
        if (identity.family != ReleaseFamily.ENDPOINT) return null
        val (entity, version) = versions.findByPath(identity.workspaceId, identity.entityId, identity.version) ?: return null
        val status = PipelineDependencySource.statusOf(version.status)
        val pins = if (version.status == PipelineVersionStatus.DISCARDED) emptyList() else pinsOf(identity.workspaceId, version.body)
        return SourceRead(
            ResolvedNode(identity, identity.entityId, status, version.bodyHash, entity.currentVersion, pins, entity.endpointId),
            Operational.ApiEndpoint(entity.enabled),
        )
    }

    companion object {
        /** The field path of an API version's one pin — what a blocker's `path` names. */
        const val PIN_PATH = PublishedEndpointVersionRepository.PIN_PATH

        /**
         * The API family's raw-body EXTRACTION (#877 §E.1, the A27 oracle): the one pin a stored API body names — the
         * exact pipeline version at [PIN_PATH] — whatever the version's status ([read] suppresses a DISCARDED body's
         * pins; that is traversal, not extraction).
         */
        fun pinsOf(
            workspaceId: java.util.UUID,
            body: EndpointVersionBody,
        ): List<ReleasePin> =
            listOf(
                ReleasePin(
                    PipelineDependencySource.identity(workspaceId, body.pipelineName, body.pipelineVersion),
                    body.pipelineName,
                    PIN_PATH,
                ),
            )

        /** The release identity of the API at [pathPattern], version [version], in [workspaceId]. */
        fun identity(
            workspaceId: java.util.UUID,
            pathPattern: String,
            version: Int,
        ): ReleaseNodeIdentity = ReleaseNodeIdentity(workspaceId, ReleaseFamily.ENDPOINT, pathPattern, version)
    }
}
