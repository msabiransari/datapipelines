package co.datapipelines.pipeline.release

import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineDeserializer
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionStatus
import java.util.UUID

/**
 * The pipeline family's read of the release graph (record §4.2): a pipeline version's exact template pins and child
 * pipeline pins, its status, body hash and the entity's current pointer.
 *
 * Pins identify pipelines and templates by registry NAME (`entityId`) and exact version (`nodes[i].template`,
 * `nodes[i].pipeline`); a MISSING pin keeps the identity it spelled. A pipeline draft may propose a new name,
 * so the coordinator resolves the root by its registry name until the release flip adopts the draft's metadata.
 *
 * Lives with the aggregate rather than in `web` because it reads nothing but this module's
 * repository; the template source needs `templates` and is assembled in `web`.
 */
class PipelineDependencySource(
    private val pipelines: PipelineRepository,
    private val deserializer: PipelineDeserializer = PipelineDeserializer(),
) : DependencySource {
    override fun resolve(identity: ReleaseNodeIdentity): ResolvedNode? {
        val record = pipelines.findByNameAnyStatus(identity.workspaceId, identity.entityId) ?: return null
        val detail = pipelines.findVersionDetail(identity.workspaceId, record.id, identity.version) ?: return null
        val status = statusOf(detail.status)
        val pins =
            if (status == ReleaseNodeStatus.DISCARDED) {
                emptyList()
            } else {
                val body = pipelines.findVersionBody(identity.workspaceId, record.id, identity.version) ?: return null
                pinsOf(identity.workspaceId, deserializer.readOrThrow(body))
            }
        return ResolvedNode(identity, record.name, status, detail.bodyHash, record.currentVersion, pins, record.id)
    }

    companion object {
        /** The release status of a stored version. */
        fun statusOf(status: PipelineVersionStatus): ReleaseNodeStatus =
            when (status) {
                PipelineVersionStatus.DRAFT -> ReleaseNodeStatus.DRAFT
                PipelineVersionStatus.RELEASED -> ReleaseNodeStatus.RELEASED
                PipelineVersionStatus.DISCARDED -> ReleaseNodeStatus.DISCARDED
            }

        /** The pipeline identity a release of [name] at [version] walks from. */
        fun identity(
            workspaceId: UUID,
            name: String,
            version: Int,
        ): ReleaseNodeIdentity = ReleaseNodeIdentity(workspaceId, ReleaseFamily.PIPELINE, name, version)

        /**
         * Every pin occurrence of [pipeline] in node order: a PIPELINE node pins a child pipeline, a CALCULATOR node
         * evaluates a catalog function and pins nothing, every other node pins its template (the filter #142 closed for
         * the release gate, `PipelineReleaseService.templatePins`).
         */
        fun pinsOf(
            workspaceId: UUID,
            pipeline: Pipeline,
        ): List<ReleasePin> =
            pipeline.nodes.withIndex().mapNotNull { (index, node) ->
                when (node.type) {
                    NodeType.CALCULATOR -> {
                        null
                    }

                    NodeType.PIPELINE -> {
                        node.pipeline?.let { child ->
                            ReleasePin(identity(workspaceId, child.name, child.version), child.name, "nodes[$index].pipeline")
                        }
                    }

                    else -> {
                        val template = node.template
                        ReleasePin(
                            ReleaseNodeIdentity(workspaceId, ReleaseFamily.TEMPLATE, template.id, template.version),
                            template.id,
                            "nodes[$index].template",
                        )
                    }
                }
            }
    }
}
