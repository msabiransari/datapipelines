package co.datapipelines.visualization

import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.TemplateReleaser
import co.datapipelines.pipeline.TemplateVersionStatuses
import co.datapipelines.visualization.VisualizationTestDb.WORKSPACE
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * The real repositories and services over [VisualizationTestDb], with a REAL transaction manager (the atomicity
 * cases need a rollback that means something) and the validators' ports faked as [ValidatorFakes] seeds them. The
 * template side of the visualization release is two recording fakes: [templateStatuses] answers the pin's status,
 * [templateReleaser] records what the cascade released. [evidence] defaults to PASS — the gate's own default
 * (`NOT_INSTALLED`, refusing) is proven by its own cases.
 */
internal class LifecycleHarness(
    authoringEnabled: Boolean = true,
    evidence: ReleaseEvidence = ReleaseEvidence { _, _ -> EvidenceVerdict.Pass },
) {
    val jdbc = VisualizationTestDb.jdbc
    val fakes = ValidatorFakes.Fakes()
    val transactions = TransactionTemplate(DataSourceTransactionManager(VisualizationTestDb.dataSource))
    val visualizationRepository = VisualizationRepository(jdbc)
    val dashboardRepository = DashboardRepository(jdbc)

    /** The transform pins' statuses by `name@version`; a pin absent here is MISSING. */
    val templateStatuses = mutableMapOf(ValidatorFakes.TRANSFORM_REF to PipelineVersionStatus.RELEASED)
    val templatesReleased = mutableListOf<TemplateRef>()

    val visualizations =
        VisualizationService(
            repository = visualizationRepository,
            validator = VisualizationValidator(fakes.templateFacts),
            dashboards = dashboardRepository,
            authoring = AuthoringGuard(authoringEnabled),
            templateStatuses = TemplateVersionStatuses { _, name, version -> templateStatuses[ArtifactRef(name, version)] },
            templateReleaser =
                TemplateReleaser { _, name, version, _ ->
                    TemplateRef(name, version).also { templatesReleased += it }
                },
            evidence = evidence,
            transactions = transactions,
        )

    val dashboards =
        DashboardService(
            repository = dashboardRepository,
            validator = DashboardValidator(fakes.pipelineFacts, fakes.setFacts, visualizationRepository.pins),
            visualizations = visualizations,
            sets = fakes.setFacts,
            authoring = AuthoringGuard(authoringEnabled),
            transactions = transactions,
        )

    fun visualizationDocument(
        name: String = DocumentFixtures.VISUALIZATION_NAME,
        edit: (com.fasterxml.jackson.databind.node.ObjectNode) -> Unit = {},
    ): VisualizationDocument = ValidatorFakes.visualizationDocument(DocumentFixtures.visualization().put("name", name).also(edit))

    /** The spec's dashboard, its visualization pin pointed at the version this database holds. */
    fun dashboardDocument(
        visualizationVersion: Int,
        name: String = DocumentFixtures.DASHBOARD_NAME,
        edit: (com.fasterxml.jackson.databind.node.ObjectNode) -> Unit = {},
    ): DashboardDocument {
        val tree = DocumentFixtures.dashboard().put("name", name)
        (
            (
                tree
                    .get(
                        "visualizations",
                    ).get(0) as com.fasterxml.jackson.databind.node.ObjectNode
            ).get("visualization") as com.fasterxml.jackson.databind.node.ObjectNode
        ).put("version", visualizationVersion)
        return ValidatorFakes.dashboardDocument(tree.also(edit))
    }

    /** A visualization created as v1 DRAFT (MCP) in [WORKSPACE]. */
    fun createVisualization(name: String = DocumentFixtures.VISUALIZATION_NAME): ArtifactVersion<VisualizationBody> =
        visualizations.create(
            WORKSPACE,
            visualizationDocument(name),
            VisualizationTestDb.AUTHOR,
            co.datapipelines.pipeline.WriteSurface.MCP,
        )
}
