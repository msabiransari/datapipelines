package co.datapipelines.web.dashboards.runtime

import co.datapipelines.application.dashboards.DashboardTransformer
import co.datapipelines.application.dashboards.TransformOutcome
import co.datapipelines.application.templates.TemplateEvaluateService
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.TemplateDryRenderer
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.TransformContractView
import co.datapipelines.templates.TemplateService
import co.datapipelines.templates.TransformTestInput
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.ArtifactRef
import co.datapipelines.visualization.DashboardErrorCodes
import java.time.Instant
import java.util.UUID

/**
 * A dashboard's transform (the implementation spec's §9 step 6) is the SAME evaluation authoring runs:
 * [TemplateEvaluateService] — the bounded evaluation pool, the §5.1 input check and the TypeGate — so what a person saw in
 * the editor is what the dashboard computes, under the same limits. Blocking; the engine calls it on its blocking
 * dispatcher. It is a delegated act (D50): `template.evaluate` is not consulted for the refreshing viewer.
 *
 * Input shaping follows the contract: a `ROW`-mode transform receives the (single) table as `rows`; every other mode
 * receives each table under `inputs[name]` (the shape the authoring test cases use).
 *
 * The output must be a TABLE — a list of row objects — because bindings name columns of it. Anything else (a scalar or
 * an object, which the visualization validator's binding rules make unreachable for a saved document) is refused
 * `pipeline.transform.output_shape` here rather than bound blind.
 */
class WebDashboardTransformer(
    private val evaluate: TemplateEvaluateService,
    private val contracts: TemplateDryRenderer,
    private val templates: TemplateService,
) : DashboardTransformer {
    override fun transform(
        workspaceId: UUID,
        template: ArtifactRef,
        tables: Map<String, List<Map<String, Any?>>>,
        now: Instant,
    ): TransformOutcome {
        if (templates.findVersionStatus(workspaceId, ReadLens.Everything, template.name, template.version) !=
            PipelineVersionStatus.RELEASED
        ) {
            return TransformOutcome.Refused(DashboardErrorCodes.RUNTIME_DEPENDENCY_MISSING)
        }
        val mode = contracts.transformContract(workspaceId, TemplateRef(template.name, template.version))?.mode
        val input =
            if (mode == TransformContractView.Mode.ROW) {
                TransformTestInput(rows = tables.values.singleOrNull().orEmpty(), inputs = emptyMap())
            } else {
                TransformTestInput(inputs = tables)
            }
        return try {
            val evaluation = evaluate.evaluate(workspaceId, template.name, template.version, input, now)

            @Suppress("UNCHECKED_CAST")
            val rows = evaluation.output as? List<Map<String, Any?>>
            if (rows == null) TransformOutcome.Refused(SHAPE_REFUSED) else TransformOutcome.Rows(rows)
        } catch (e: DatapipelinesException) {
            TransformOutcome.Refused(e.code)
        }
    }

    private companion object {
        const val SHAPE_REFUSED = PipelineErrorCodes.Transform.ROW_SHAPE_MISMATCH
    }
}
