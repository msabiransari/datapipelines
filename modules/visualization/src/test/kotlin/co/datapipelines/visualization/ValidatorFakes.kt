package co.datapipelines.visualization

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TransformContractView
import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * Fakes for the four ports, seeded so the spec's two worked documents are VALID: `revenue_bars@2` is a RELEASED
 * transform whose `rows` input is the visualization's `revenue` columns and whose output carries the two bound
 * columns; `monthly_revenue@7` is a RELEASED read-only pipeline with `year` (required) and `currency`, whose
 * caller output is `month`/`amount`; the set pins `year` and `currency` (neither a parent); the visualization
 * pin is the spec's own visualization body.
 */
internal object ValidatorFakes {
    val WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-00000000000a")

    val TRANSFORM_REF = ArtifactRef("finance/transforms/revenue_bars", 2)
    val PIPELINE_REF = ArtifactRef("finance/pipelines/monthly_revenue", 7)
    val SET_REF = ArtifactRef("finance/parameters/reporting_period", 1)
    val VISUALIZATION_REF = ArtifactRef("finance/visualizations/monthly_revenue", 3)

    private fun column(
        name: String,
        type: LogicalType,
        nullable: Boolean = false,
    ) = TransformContractView.Column(name, type, nullable)

    val CONTRACT =
        TransformContractView(
            mode = TransformContractView.Mode.TABLE,
            inputs =
                mapOf(
                    "rows" to
                        TransformContractView.Input.Table(listOf(column("month", LogicalType.DATE), column("amount", LogicalType.DECIMAL))),
                ),
            output =
                TransformContractView.Output.Table(
                    listOf(column("month_labels", LogicalType.STRING), column("amounts", LogicalType.DECIMAL)),
                ),
            rejects = false,
        )

    /** Mutable pins, reset per test by constructing a fresh [Fakes]. */
    class Fakes {
        val templates =
            mutableMapOf<ArtifactRef, TemplatePin>(TRANSFORM_REF to TemplatePin.Transform(PipelineVersionStatus.RELEASED, CONTRACT))
        val pipelines =
            mutableMapOf(
                PIPELINE_REF to
                    PipelineReleaseFact(
                        status = PipelineVersionStatus.RELEASED,
                        readOnly = true,
                        parameters =
                            listOf(
                                PipelineParameterFact("year", required = true),
                                PipelineParameterFact("currency", required = false),
                            ),
                        outputColumns =
                            listOf(
                                OutputColumn("month", LogicalType.DATE, false),
                                OutputColumn("amount", LogicalType.DECIMAL, false),
                            ),
                    ),
            )
        val sets =
            mutableMapOf(
                SET_REF to
                    ParameterSetFact(
                        PipelineVersionStatus.RELEASED,
                        listOf(SetParameterFact("year", emptySet()), SetParameterFact("currency", emptySet())),
                    ),
            )
        val visualizations =
            mutableMapOf(
                VISUALIZATION_REF to
                    PinnedVisualization(
                        PipelineVersionStatus.RELEASED,
                        VisualizationReader().readOrThrow(DocumentFixtures.visualization()).body,
                    ),
            )

        val templateFacts = TemplateContractFacts { _, ref -> templates[ref] ?: TemplatePin.NotFound }
        val pipelineFacts = PipelineReleaseFacts { _, ref -> pipelines[ref] }
        val setFacts = ParameterSetFacts { _, ref -> sets[ref] }
        val visualizationPins = VisualizationPins { _, ref -> visualizations[ref] }

        fun visualizationValidator() = VisualizationValidator(templateFacts)

        /** The most distinct executions one refresh may run — the runtime's `max-executions-per-refresh`; a case lowers it. */
        var maxExecutionsPerRefresh: Int = DashboardValidator.DEFAULT_MAX_EXECUTIONS_PER_REFRESH

        fun dashboardValidator() =
            DashboardValidator(
                pipelineFacts,
                setFacts,
                visualizationPins,
                maxExecutionsPerRefresh = maxExecutionsPerRefresh,
            )
    }

    fun visualizationDocument(tree: ObjectNode): VisualizationDocument = VisualizationReader().readOrThrow(tree)

    fun dashboardDocument(tree: ObjectNode): DashboardDocument = DashboardReader().readOrThrow(tree)
}
