package co.datapipelines.application.dependencies

import co.datapipelines.application.endpoints.PublishedEndpointVersionRepository
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.inverse.InverseRequest
import co.datapipelines.pipeline.inverse.InverseRow
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.visualization.DashboardRepository
import co.datapipelines.visualization.VisualizationRepository

/**
 * What the pipeline executor's stored schedule payload looks like to the schedule → pipeline inverse arm (#877 §B): the
 * `target_ref` prefix, the payload field holding the exact version, the edge's source path (a JSON Pointer) and the first
 * payload schema that stores an exact version. Supplied by the layer that owns the executor's grammar (`web`); the
 * scheduler core stays target-agnostic.
 */
data class ScheduleTargetShape(
    val targetRefPrefix: String,
    val versionField: String,
    val sourcePath: String,
    val exactFromSchema: Int,
)

/**
 * The twelve production inverse arms (#877 §B, record §6 read backwards), grouped by TARGET family — the arm set each
 * target provider's `findIncoming` answers through. Every arm is its DEPENDENT family's repository query (the one
 * definition the domain callers page too); every repository here is built over the provider's [StatementCeiling]'s own
 * template in production, so each page is bounded.
 *
 * - template ← pipeline (`nodes[i].template`), template (`imports[i]`), parameter_set
 *   (`parameters[i].source.template`), visualization (`transform.template`);
 * - pipeline ← pipeline (`nodes[i].pipeline`), dashboard (`sources[i].pipeline`), api_endpoint (`pipeline_version`),
 *   schedule (`/pipeline`);
 * - parameter_set ← dashboard (`parameter_set`);
 * - visualization ← dashboard (`visualizations[i].visualization`);
 * - datasource ← pipeline (`nodes[i].source`, `nodes[i].output.datasource`), parameter_set
 *   (`parameters[i].source.datasource`).
 */
object IncomingArmCatalog {
    fun template(
        pipelines: PipelineRepository,
        templates: TemplateRepository,
        sets: ParameterSetRepository,
        visualizations: VisualizationRepository,
        ceiling: StatementCeiling,
    ): IncomingArms =
        IncomingArms(
            FamilyKey.TEMPLATE,
            listOf(
                versioned(FamilyKey.PIPELINE, pipelines::findTemplatePinOccurrences),
                versioned(FamilyKey.TEMPLATE, templates::findImportPinOccurrences),
                versioned(FamilyKey.PARAMETER_SET, sets::findTemplatePinOccurrences),
                versioned(FamilyKey.VISUALIZATION, visualizations::findTemplatePinOccurrences),
            ),
            ceiling,
        )

    @Suppress("LongParameterList") // one query source per dependent family, plus the executor's payload shape
    fun pipeline(
        pipelines: PipelineRepository,
        dashboards: DashboardRepository,
        endpoints: PublishedEndpointVersionRepository,
        schedules: ScheduleTargetQueries,
        schedule: ScheduleTargetShape,
        ceiling: StatementCeiling,
    ): IncomingArms =
        IncomingArms(
            FamilyKey.PIPELINE,
            listOf(
                versioned(FamilyKey.PIPELINE, pipelines::findChildPinOccurrences),
                versioned(FamilyKey.DASHBOARD, dashboards::findPipelinePinOccurrences),
                versioned(FamilyKey.API_ENDPOINT, endpoints::findPipelinePinOccurrences),
                IncomingArm(FamilyKey.SCHEDULE, versioned = false) { request -> schedules.findTargetOccurrences(request, schedule) },
            ),
            ceiling,
        )

    fun parameterSet(
        dashboards: DashboardRepository,
        ceiling: StatementCeiling,
    ): IncomingArms =
        IncomingArms(FamilyKey.PARAMETER_SET, listOf(versioned(FamilyKey.DASHBOARD, dashboards::findParameterSetPinOccurrences)), ceiling)

    fun visualization(
        dashboards: DashboardRepository,
        ceiling: StatementCeiling,
    ): IncomingArms =
        IncomingArms(FamilyKey.VISUALIZATION, listOf(versioned(FamilyKey.DASHBOARD, dashboards::findVisualizationPinOccurrences)), ceiling)

    fun datasource(
        pipelines: PipelineRepository,
        sets: ParameterSetRepository,
        ceiling: StatementCeiling,
    ): IncomingArms =
        IncomingArms(
            FamilyKey.DATASOURCE,
            listOf(
                versioned(FamilyKey.PIPELINE, pipelines::findDatasourceOccurrences),
                versioned(FamilyKey.PARAMETER_SET, sets::findDatasourceOccurrences),
            ),
            ceiling,
            exactAdmitted = false,
            deploymentScoped = true,
        )

    private fun versioned(
        dependents: FamilyKey,
        read: (InverseRequest) -> List<InverseRow>,
    ): IncomingArm = IncomingArm(dependents, versioned = true, read = read)
}
