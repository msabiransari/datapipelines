package co.datapipelines.web.visualizations

import co.datapipelines.application.templates.TemplateEvaluateService
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateDryRenderer
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.TransformContractView
import co.datapipelines.templates.TransformTestInput
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.ArtifactRef
import co.datapipelines.visualization.FixtureEvaluation
import co.datapipelines.visualization.TestFixtureEvaluator
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * The authoring path's fixture evaluation (the brief's §B): the mechanical test's step 3 over the REAL
 * bounded evaluator, [TemplateEvaluateService] — the evaluation pool, the §5.1 input check and the type
 * gate, so the fixtures a case submits are judged exactly as the runtime judges production rows. It is a
 * NEW composition, not [co.datapipelines.web.dashboards.runtime.WebDashboardTransformer]: the authoring
 * path admits a DRAFT transform pin (the release cascade releases it later), and an explicitly selected draft dashboard also admits it; published runtime requires RELEASED pins.
 *
 * Input shaping follows the contract: a `ROW`-mode transform receives the (single) table as `rows`; every
 * other mode receives each table under `inputs[name]` (the shape authoring test cases use). The output
 * must be a TABLE — a list of row objects — because bindings name columns of it; anything else is refused
 * with the transformer's own shape code rather than bound blind.
 */
class WebVisualizationFixtureEvaluator(
    private val evaluate: TemplateEvaluateService,
    private val contracts: TemplateDryRenderer,
) : TestFixtureEvaluator {
    override fun evaluate(
        workspaceId: UUID,
        pin: ArtifactRef,
        fixtures: Map<String, List<ObjectNode>>,
    ): FixtureEvaluation =
        try {
            val mode = contracts.transformContract(workspaceId, TemplateRef(pin.name, pin.version))?.mode
            val input =
                if (mode == TransformContractView.Mode.ROW) {
                    TransformTestInput(
                        rows =
                            fixtures.values
                                .singleOrNull()
                                .orEmpty()
                                .map(::toRow),
                        inputs = emptyMap(),
                    )
                } else {
                    TransformTestInput(inputs = fixtures.mapValues { (_, rows) -> rows.map(::toRow) })
                }
            val evaluation = evaluate.evaluate(workspaceId, pin.name, pin.version, input)

            @Suppress("UNCHECKED_CAST") // the type gate has already shaped the output; the cast only labels it
            val rows =
                evaluation.output as? List<Map<String, Any?>>
                    ?: return FixtureEvaluation.Refused(OUTPUT_SHAPE_REFUSED, "The transform's output is not a table of rows.")
            FixtureEvaluation.Rows(rows)
        } catch (e: DatapipelinesException) {
            FixtureEvaluation.Refused(e.code, e.message ?: "The fixture run refused.")
        }

    private fun toRow(row: ObjectNode): Map<String, Any?> =
        MAPPER.convertValue(
            row,
            object : TypeReference<Map<String, Any?>>() {},
        )

    private companion object {
        val MAPPER =
            com.fasterxml.jackson.databind
                .ObjectMapper()

        /** The shape refusal the runtime transformer uses for a non-table output — the same verdict here. */
        val OUTPUT_SHAPE_REFUSED = PipelineErrorCodes.Transform.ROW_SHAPE_MISMATCH
    }
}
