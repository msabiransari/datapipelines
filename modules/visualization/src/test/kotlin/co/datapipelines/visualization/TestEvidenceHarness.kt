package co.datapipelines.visualization

import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.TemplateReleaser
import co.datapipelines.pipeline.TemplateVersionStatuses
import co.datapipelines.pipeline.WriteSurface
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/**
 * The L4a harness: the REAL repositories and services over [VisualizationTestDb] — the runs repository,
 * the session service, the mechanical check (deep renderer validator, a SCRIPTED fixture evaluator) and
 * the release evidence — beside a real [VisualizationService] to author drafts with (its own gate faked
 * PASS; the gate itself is proven by [VisualizationReleaseEvidenceIntegrationTest]).
 *
 * The fixture evaluator is scripted per test: [fixtureRows] answer [FixtureEvaluation.Rows],
 * [fixtureRefusal] answers a refusal — the evaluator adapter's real composition is `web`'s (its focused
 * test there), and the DB-level suites judge the persistence, the capability lifecycle and the races.
 */
internal class TestEvidenceHarness(
    val evidence: ReleaseEvidence? = null,
) {
    val jdbc = VisualizationTestDb.jdbc
    val transactions = TransactionTemplate(DataSourceTransactionManager(VisualizationTestDb.dataSource))
    val visualizationRepository = VisualizationRepository(jdbc)
    val runRepository = TestRunRepository(jdbc)

    /** Scripted fixture-evaluator answers; reset per test. */
    val fixtureRows = mutableListOf<Map<String, Any?>>()
    var fixtureRefusal: FixtureEvaluation.Refused? = null

    /** The template statuses by `name@version`; the transform pin's answer. */
    val templateStatuses = mutableMapOf<ArtifactRef, PipelineVersionStatus>(ValidatorFakes.TRANSFORM_REF to PipelineVersionStatus.RELEASED)
    val templatesReleased = mutableListOf<TemplateRef>()

    val templates: TemplateContractFacts =
        TemplateContractFacts { _, ref ->
            val status = templateStatuses[ref]
            when (status) {
                null -> TemplatePin.VersionNotFound
                else -> TemplatePin.Transform(status, ValidatorFakes.CONTRACT)
            }
        }

    private val fixtureEvaluator =
        TestFixtureEvaluator { _, _, _ ->
            fixtureRefusal ?: FixtureEvaluation.Rows(fixtureRows.toList())
        }

    val mechanical =
        VisualizationMechanicalCheck(
            renderers = RendererConfigValidators.deep(),
            fixtures = fixtureEvaluator,
            templates = templates,
            rendered = RenderedStateCheck.NOT_AVAILABLE,
        )

    val config = VisualizationConfig()

    val visualizations =
        VisualizationService(
            repository = visualizationRepository,
            validator = VisualizationValidator(templates, RendererConfigValidators.deep()),
            dashboards = DashboardRepository(jdbc),
            authoring = AuthoringGuard(true),
            templateStatuses = TemplateVersionStatuses { _, name, version -> templateStatuses[ArtifactRef(name, version)] },
            templateReleaser =
                TemplateReleaser { _, name, version, _ -> TemplateRef(name, version).also { templatesReleased += it } },
            evidence = evidence ?: VisualizationReleaseEvidence(runRepository, mechanical),
            transactions = transactions,
        )

    val sessions =
        VisualizationTestSessionService(
            runs = runRepository,
            visualizations = visualizationRepository,
            mechanical = mechanical,
            config = config,
            transactions = transactions,
        )

    /** The dashboard side, for the cascade test — the same faked dependency facts the lifecycle harness uses. */
    private val fakes = ValidatorFakes.Fakes()

    val dashboards =
        DashboardService(
            repository = DashboardRepository(jdbc),
            validator = DashboardValidator(fakes.pipelineFacts, fakes.setFacts, visualizationRepository.pins),
            visualizations = visualizations,
            sets = fakes.setFacts,
            authoring = AuthoringGuard(true),
            transactions = transactions,
        )

    /** The spec's dashboard document, its one visualization pin pointed at [version] of [visualizationName]. */
    fun dashboardDocument(
        visualizationName: String,
        version: Int,
    ): DashboardDocument {
        val tree = DocumentFixtures.dashboard().put("name", DocumentFixtures.DASHBOARD_NAME)
        (tree.get("visualizations").get(0) as com.fasterxml.jackson.databind.node.ObjectNode)
            .putObject("visualization")
            .put("name", visualizationName)
            .put("version", version)
        return ValidatorFakes.dashboardDocument(tree)
    }

    fun reset() {
        VisualizationTestDb.reset()
        fixtureRows.clear()
        fixtureRefusal = null
        templatesReleased.clear()
        templateStatuses.clear()
        templateStatuses[ValidatorFakes.TRANSFORM_REF] = PipelineVersionStatus.RELEASED
    }

    /** A visualization created as v1 DRAFT from the spec's document, in the harness workspace. */
    fun createVisualization(name: String = DocumentFixtures.VISUALIZATION_NAME): ArtifactVersion<VisualizationBody> =
        visualizations.create(
            VisualizationTestDb.WORKSPACE,
            VisualizationDocument(name, ValidatorFakes.visualizationDocument(DocumentFixtures.visualization()).body),
            VisualizationTestDb.AUTHOR,
            WriteSurface.MCP,
        )

    fun now(): Instant = Instant.now()

    companion object {
        val WORKSPACE: UUID = VisualizationTestDb.WORKSPACE
        val OTHER_WORKSPACE: UUID = VisualizationTestDb.OTHER_WORKSPACE
        val AUTHOR: UUID = VisualizationTestDb.AUTHOR
        val OTHER_AUTHOR: UUID = VisualizationTestDb.OTHER_AUTHOR
    }
}
