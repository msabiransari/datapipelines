package co.datapipelines.application.visualization

import co.datapipelines.application.endpoints.ReadOnlyPipelineRule
import co.datapipelines.pipeline.CallerNodeResolver
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.pipeline.ResolvedPipeline
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.TransformContractView
import co.datapipelines.typesystem.LogicalType
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
 * - **outputColumns** — the caller node's columns, in #328's precedence (§3.3.1): a TRANSFORM caller's pinned
 *   contract names them (DECLARED — the record, when one exists, never overrides the contract); a release with no
 * caller node returns EMPTY (it returns no rows); any other caller node — a SQL node — answers the version's
 *   RECORDED columns (`caller_output_json`, the D1 record its release copied from the version's latest run),
 *   and null when there is none — the validator skips the save-time input check rather than guess (the runtime
 *   judges real columns, L2). A record's absent-or-null `nullable` reads as `true`: unknown admits.
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
            outputColumns = callerColumns(workspaceId, resolved, pipeline),
        )
    }

    /**
     * The caller node's columns, in #328's precedence: EMPTY with no caller node; the contract's table
     * columns for a TRANSFORM caller; otherwise the version's RECORDED columns, null when none — never a
     * guess, and never the record over a declared contract.
     */
    private fun callerColumns(
        workspaceId: UUID,
        resolved: ResolvedPipeline,
        pipeline: Pipeline,
    ): List<OutputColumn>? {
        val caller = CallerNodeResolver.resolve(pipeline) ?: return emptyList()
        if (caller.type == NodeType.TRANSFORM) {
            return when (val output = contracts(workspaceId, caller.template)?.output) {
                is TransformContractView.Output.Table -> output.columns.map { OutputColumn(it.name, it.type, it.nullable) }
                is TransformContractView.Output.Value, TransformContractView.Output.Obj, null -> null
            }
        }
        return recordedColumns(resolved.callerOutputJson)
    }

    /**
     * #328 (D4) — the D2 record read back: an array of `{name, type, nullable}`, `type` a
     * `LogicalType` wire value; an absent or JSON-null `nullable` reads as `true` (unknown
     * admits). A malformed element fails the read loudly — the record was bounded at write
     * and validated at promotion, so one that cannot parse is corruption, never a guess.
     */
    private fun recordedColumns(record: String?): List<OutputColumn>? {
        if (record == null) return null
        val array = MAPPER.readTree(record)
        require(array.isArray) { "caller_output record is not an array" }
        return array.map { element ->
            val name = element.path("name").textValue()
            require(!name.isNullOrBlank()) { "caller_output record has a column without a name" }
            val typeWire = element.path("type").textValue()
            require(!typeWire.isNullOrBlank()) { "caller_output record column '$name' has no type" }
            val nullable = element.path("nullable").takeIf { it.isBoolean }?.asBoolean() ?: true
            OutputColumn(name, LogicalType.fromWire(typeWire), nullable)
        }
    }

    private companion object {
        private val MAPPER = PipelineJson.objectMapper()
    }
}
