package co.datapipelines.application.visualization

import co.datapipelines.application.endpoints.ReadOnlyPipelineRule
import co.datapipelines.pipeline.CallerNodeResolver
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.TransformContractView
import co.datapipelines.visualization.ArtifactRef
import co.datapipelines.visualization.OutputColumn
import co.datapipelines.visualization.PipelineParameterFact
import co.datapipelines.visualization.PipelineReleaseFact
import co.datapipelines.visualization.PipelineReleaseFacts
import java.util.UUID

/**
 * The production [PipelineReleaseFacts] (the implementation spec's §3.2 `sources[]`, D1, D38): what the dashboard
 * validator asks of a pinned pipeline release, answered from the SAME three sources the rest of the product reads.
 *
 * - **status** — the pinned version's own lifecycle status, off [PipelineResolver] (the save-time composition
 *   port; `repositoryPipelineResolver` reads it from `PipelineRepository`, failing closed on a missing detail).
 * - **readOnly** — [ReadOnlyPipelineRule], the published-endpoint rule, transitively through child pipelines: a
 *   dashboard refresh re-runs its sources on every view, exactly as a `GET` does, so the same rule decides.
 * - **parameters** — the release's declared parameters; one a caller MUST supply is `required` with no default.
 * - **outputColumns** — the caller node's DECLARED columns, and nothing inferred. A release declares them only when
 *   its caller node is a TRANSFORM whose pinned contract has a table output (the contract names every column,
 *   type and nullability). A release with NO caller node returns no rows: empty. Any other caller node — a SQL
 *   node, whose columns exist only once it runs — declares none: null, and the validator skips the save-time
 *   input check rather than guess (the runtime judges real columns, L2). Two greps found no other derivation:
 *   no `callerOutput`/`resultSchema`/`outputSchema` function exists in any module's main sources.
 *
 * Null for a name or version the workspace does not hold — the validator's `dependency_not_found`.
 */
class PipelineReleaseFactsReader(
    private val pipelines: PipelineResolver,
    private val readOnly: ReadOnlyPipelineRule,
    /** A transform template version's contract — `TemplateDryRenderer.transformContract` in production. */
    private val contracts: (UUID, TemplateRef) -> TransformContractView?,
) : PipelineReleaseFacts {
    override fun releaseOf(
        workspaceId: UUID,
        ref: ArtifactRef,
    ): PipelineReleaseFact? {
        val resolved = pipelines.resolve(workspaceId, ref.name, ref.version) ?: return null
        val pipeline = resolved.pipeline
        return PipelineReleaseFact(
            status = resolved.versionStatus,
            readOnly = readOnly.check(pipeline, workspaceId).isValid,
            parameters =
                pipeline.parameters.map { (name, parameter) ->
                    PipelineParameterFact(name, required = parameter.required && !parameter.hasDefault)
                },
            outputColumns = callerColumns(workspaceId, pipeline),
        )
    }

    /** The caller node's declared columns — empty with no caller node, null when the caller node declares none. */
    private fun callerColumns(
        workspaceId: UUID,
        pipeline: Pipeline,
    ): List<OutputColumn>? {
        val caller = CallerNodeResolver.resolve(pipeline) ?: return emptyList()
        if (caller.type != NodeType.TRANSFORM) return null
        return when (val output = contracts(workspaceId, caller.template)?.output) {
            is TransformContractView.Output.Table -> output.columns.map { OutputColumn(it.name, it.type, it.nullable) }
            is TransformContractView.Output.Value, TransformContractView.Output.Obj, null -> null
        }
    }
}
