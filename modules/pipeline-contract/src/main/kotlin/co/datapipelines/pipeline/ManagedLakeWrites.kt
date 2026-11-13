package co.datapipelines.pipeline

import co.datapipelines.typesystem.DatapipelinesException
import java.util.UUID

/** Live transitive write facts; mutable pinned child drafts are deliberately not cached between calls. */
internal class ManagedLakeWrites(
    private val datasources: DatasourceRegistry,
    private val pipelines: PipelineResolver,
    private val maxDepth: Int,
) {
    fun writes(
        pipeline: Pipeline,
        workspaceId: UUID,
    ): Boolean {
        val pending = ArrayDeque<Pair<Pipeline, Int>>()
        val visited = mutableSetOf<PipelineNodeRef>()
        pending.add(pipeline to 0)
        while (pending.isNotEmpty()) {
            val (body, depth) = pending.removeFirst()
            if (body.nodes.any { managedAppend(it, workspaceId) }) return true
            body.nodes.filter { it.type == NodeType.PIPELINE }.forEach { node ->
                val reference = node.pipeline ?: throw missingChild()
                if (visited.add(reference)) {
                    pending.add(resolve(reference, depth, workspaceId) to depth + 1)
                }
            }
        }
        return false
    }

    private fun resolve(
        reference: PipelineNodeRef,
        depth: Int,
        workspaceId: UUID,
    ): Pipeline {
        if (depth >= maxDepth) {
            throw DatapipelinesException(PipelineErrorCodes.Validation.COMPOSITION_TOO_DEEP, "Pipeline composition exceeds its bound.")
        }
        return (pipelines.resolve(workspaceId, reference.name, reference.version) ?: throw missingChild()).pipeline
    }

    private fun managedAppend(
        node: Node,
        workspaceId: UUID,
    ): Boolean {
        val output = node.output as? NodeOutput.Datasource ?: return false
        return output.mode == WriteMode.APPEND && datasources.describe(output.datasource, workspaceId)?.managedLake == true
    }

    private fun missingChild(): DatapipelinesException =
        DatapipelinesException(
            PipelineErrorCodes.Validation.PIPELINE_VERSION_NOT_FOUND,
            "A pinned child could not be resolved while determining managed lake writes.",
        )
}
