package co.datapipelines.web.release

import co.datapipelines.application.dependencies.PostgresStatementCeiling
import co.datapipelines.application.dependencies.ReleaseDependencyFamilies
import co.datapipelines.application.dependencies.ReleasePlanningDeadline
import co.datapipelines.application.dependencies.ReleasePlanningWalk
import co.datapipelines.dependencies.FamilyDescriptor
import co.datapipelines.dependencies.FamilyRegistry
import co.datapipelines.pipeline.PipelineVersionStatus.DISCARDED
import co.datapipelines.pipeline.PipelineVersionStatus.DRAFT
import co.datapipelines.pipeline.release.ReleaseBounds
import co.datapipelines.pipeline.release.ReleaseClosure
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.TreeHash
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.web.SharedPostgres
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import java.time.Duration

/**
 * #873 §A.2 parity on a real PostgreSQL, through the PRODUCTION descriptor bean methods of [ReleaseConfiguration]: the
 * native template provider and G1's adapted [TemplateDependencySource] walk identical closures — ids, edges, paths, depth
 * and the `tree_hash` canonical string — over the record §5.4 worked graph, the A02 import diamond and a DISCARDED
 * import. The providers' own conformance, batching and worked-graph suite is the application module's
 * `ArtifactReleaseProvidersPostgresTest` (the providers live there).
 */
class TemplateProviderParityPostgresTest {
    private val seed = ReleaseArtifactSeed(NamedParameterJdbcTemplate(SharedPostgres.dataSource()))
    private val dataSource = SharedPostgres.dataSource()
    private val manager = DataSourceTransactionManager(dataSource)
    private val ceiling = PostgresStatementCeiling(dataSource)
    private val release = ReleaseConfiguration()
    private val descriptors =
        listOf(
            release.pipelineDependencyFamily(ceiling),
            release.templateDependencyFamily(ceiling),
            release.parameterSetDependencyFamily(ceiling),
            release.visualizationDependencyFamily(ceiling),
            release.dashboardDependencyFamily(ceiling),
        )

    /** G1's adapted template source over the same ceiling — the parity oracle, never registered in production. */
    private val adaptedTemplate =
        ReleaseDependencyFamilies.descriptor(ReleaseFamily.TEMPLATE, TemplateDependencySource(TemplateRepository(ceiling.jdbc)), ceiling)

    @BeforeEach
    fun reset() {
        seed.reset()
    }

    @Test
    fun `the native template provider and the adapted TemplateDependencySource walk identical closures`() {
        workedGraph()
        val worked = seed.identity(ReleaseFamily.DASHBOARD, "sales", 3)
        importDiamond()
        val diamond = seed.identity(ReleaseFamily.TEMPLATE, "report", 7)
        seed.template("library", 1, DISCARDED, imports = listOf("common" to 2))
        seed.template("importer", 1, DRAFT, imports = listOf("library" to 1, "dates" to 3))
        val discarded = seed.identity(ReleaseFamily.TEMPLATE, "importer", 1)

        listOf(worked, diamond, discarded).forEach { root ->
            val native = plan(root)
            val adapted = plan(root, template = adaptedTemplate)
            withClue("$root: ids, edges, paths, depth and the canonical string") {
                native shouldBe adapted
                TreeHash.canonical(native) shouldBe TreeHash.canonical(adapted)
            }
        }
        withClue("non-vacuous: the import edges exist in both") {
            plan(diamond).edges shouldHaveSize 3
        }
    }

    /** Record §5.4, artifact-only (U1): the datasource `warehouse` is named by the selector but emits no edge yet. */
    private fun workedGraph() {
        seed.template("common", 4, library = true)
        seed.template("query", 6, imports = listOf("common" to 4))
        seed.template("selector", 5, imports = listOf("common" to 4))
        seed.template("formatter", 3)
        seed.parameterSet("filters", 2, DRAFT, selectors = listOf("selector" to 5))
        seed.pipeline("totals", 2, templates = listOf("query" to 6))
        seed.pipeline("report", 7, DRAFT, children = listOf("totals" to 2))
        seed.visualization("chart", 2, DRAFT, transform = "formatter" to 3)
        seed.dashboard(
            "sales",
            3,
            DRAFT,
            set = "filters" to 2,
            pipelines = listOf("report" to 7),
            visualizations = listOf("chart" to 2),
            bound = listOf("p0"),
        )
    }

    /** A02: `report@7 → dates@3 → common@2` plus the direct `report@7 → common@2`. */
    private fun importDiamond() {
        seed.template("common", 2, library = true)
        seed.template("dates", 3, library = true, imports = listOf("common" to 2))
        seed.template("report", 7, DRAFT, imports = listOf("dates" to 3, "common" to 2))
    }

    private fun plan(
        root: ReleaseNodeIdentity,
        template: FamilyDescriptor<*> = descriptors.single { it.key.wire == "template" },
    ): ReleaseClosure {
        val registry = FamilyRegistry.production(descriptors.filter { it.key.wire != "template" } + template)
        val walk =
            ReleasePlanningWalk(
                registry,
                ReleaseBounds(),
                ReleasePlanningWalk.planningTransaction(manager),
                ReleasePlanningWalk.speculativeReads(manager),
                ReleasePlanningDeadline.after(Duration.ofSeconds(BUDGET_SECONDS)),
            )
        return checkNotNull(walk.plan(root, seed.userId)) { "$root resolves" }
    }

    private companion object {
        const val BUDGET_SECONDS = 30L
    }
}
