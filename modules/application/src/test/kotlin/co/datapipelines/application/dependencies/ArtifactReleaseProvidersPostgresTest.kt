package co.datapipelines.application.dependencies

import co.datapipelines.application.SharedPostgres
import co.datapipelines.application.dependencies.dashboard.DashboardReleaseProvider
import co.datapipelines.application.dependencies.datasource.DatasourceDependencyFamily
import co.datapipelines.application.dependencies.parameterset.ParameterSetReleaseProvider
import co.datapipelines.application.dependencies.template.TemplateReleaseProvider
import co.datapipelines.application.dependencies.visualization.VisualizationReleaseProvider
import co.datapipelines.datasources.DatasourceRepository
import co.datapipelines.dependencies.Authority
import co.datapipelines.dependencies.Constraint
import co.datapipelines.dependencies.CycleHandling
import co.datapipelines.dependencies.Deadline
import co.datapipelines.dependencies.DependencyWalker
import co.datapipelines.dependencies.FamilyDescriptor
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.FamilyRegistry
import co.datapipelines.dependencies.MetadataSnapshot
import co.datapipelines.dependencies.ObjectRequest
import co.datapipelines.dependencies.Outcome
import co.datapipelines.dependencies.Purpose
import co.datapipelines.dependencies.ReadIsolation
import co.datapipelines.dependencies.Resolution
import co.datapipelines.dependencies.ResolveContext
import co.datapipelines.dependencies.conformance.ProviderConformance
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionStatus.DISCARDED
import co.datapipelines.pipeline.PipelineVersionStatus.DRAFT
import co.datapipelines.pipeline.PipelineVersionStatus.RELEASED
import co.datapipelines.pipeline.release.ReleaseBounds
import co.datapipelines.pipeline.release.ReleaseClosure
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.ReleasePrerequisite
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.visualization.DashboardRepository
import co.datapipelines.visualization.VisualizationRepository
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * #873 §A on a real PostgreSQL, over descriptors built exactly as `ReleaseConfiguration`'s beans build them: the four
 * native artifact providers (template, parameter set, visualization, dashboard) pass G1's [ProviderConformance] harness
 * over seeded rows; `resolveMany` is ONE statement per call whatever the request count (review R1-8) and the walk batches
 * a frontier into ⌈n / 256⌉ calls; the record §5.4 worked graph walks to exactly 10 nodes and 11 edges with `warehouse`
 * loaded once (U2, #873 §F) while its release closure stays U1's 9 nodes and 9 edges; the import diamond loads
 * `common@2` once with two edge occurrences (A02).
 *
 * The providers live in this module, so their database suite does too (the module's coverage is its own code's). The
 * §A.2 parity with the adapted `TemplateDependencySource` (a web class) is web's `TemplateProviderParityPostgresTest`,
 * over the production beans; one descriptor per family is web's `ReleaseStatementCeilingWiringTest`.
 */
class ArtifactReleaseProvidersPostgresTest {
    private val statements = ObservedStatements(SharedPostgres.dataSource())
    private val seed = ReleaseArtifactSeed(NamedParameterJdbcTemplate(SharedPostgres.dataSource()))
    private val manager = DataSourceTransactionManager(statements.dataSource)
    private val ceiling = PostgresStatementCeiling(statements.dataSource)
    private val datasources = NonArtifactSeed(NamedParameterJdbcTemplate(SharedPostgres.dataSource()))

    /** The descriptors exactly as `ReleaseConfiguration`'s beans build them (web's parity and wiring tests read the beans). */
    private val descriptors =
        mapOf(
            ReleaseFamily.PIPELINE to PipelineReleaseFamily.descriptor(PipelineRepository(ceiling.jdbc), ceiling),
            ReleaseFamily.TEMPLATE to TemplateReleaseProvider(TemplateRepository(ceiling.jdbc), ceiling).descriptor(),
            ReleaseFamily.PARAMETER_SET to
                ParameterSetReleaseProvider(ParameterSetRepository(ceiling.jdbc), TemplateRepository(ceiling.jdbc), ceiling).descriptor(),
            ReleaseFamily.VISUALIZATION to VisualizationReleaseProvider(VisualizationRepository(ceiling.jdbc), ceiling).descriptor(),
            ReleaseFamily.DASHBOARD to DashboardReleaseProvider(DashboardRepository(ceiling.jdbc), ceiling).descriptor(),
        )

    @BeforeEach
    fun reset() {
        seed.reset()
    }

    // ------------------------------------------------------------------------------------------------ conformance

    @Test
    fun `the template provider conforms - one IMPORT reference per imports occurrence`() {
        seed.template("lib", 1, library = true)
        seed.template("lib", 2, library = true)
        seed.template("report", 1, DISCARDED, imports = listOf("lib" to 1))
        seed.template("report", 2, RELEASED, imports = listOf("lib" to 1))
        seed.template("report", 3, DRAFT, imports = listOf("lib" to 1, "lib" to 2))

        conforms(
            ReleaseFamily.TEMPLATE,
            present = listOf("report" to 1, "report" to 2, "report" to 3, "lib" to 1),
            absent = listOf("report" to 9, "ghost" to 1),
            fields = mapOf(("report" to 1) to setOf("imports[0]"), ("report" to 2) to setOf("imports[0]"), ("report" to 3) to IMPORTS_2),
        )
    }

    @Test
    fun `the parameter-set provider conforms - a template and a datasource reference per selector`() {
        seed.template("states", 1)
        seed.template("cities", 4)
        seed.parameterSet("filters", 1, DRAFT, selectors = listOf("states" to 1, "cities" to 4))
        seed.parameterSet("filters", 2, RELEASED)

        conforms(
            ReleaseFamily.PARAMETER_SET,
            present = listOf("filters" to 1, "filters" to 2),
            absent = listOf("filters" to 3),
            fields = mapOf(("filters" to 1) to SELECTOR_FIELDS),
        )
    }

    @Test
    fun `the visualization provider conforms - the transform template is its one reference`() {
        seed.template("formatter", 3)
        seed.visualization("chart", 1, RELEASED, transform = "formatter" to 3)
        seed.visualization("chart", 2, DRAFT)

        conforms(
            ReleaseFamily.VISUALIZATION,
            present = listOf("chart" to 1, "chart" to 2),
            absent = listOf("chart" to 3),
            fields = mapOf(("chart" to 1) to setOf("transform.template")),
        )
    }

    @Test
    fun `the dashboard provider conforms - set, every source pipeline and every visualization occurrence`() {
        seed.parameterSet("filters", 1)
        seed.pipeline("report", 1)
        seed.visualization("chart", 1)
        seed.dashboard(
            "sales",
            1,
            DRAFT,
            set = "filters" to 1,
            pipelines = listOf("report" to 1),
            visualizations = listOf("chart" to 1, "chart" to 1),
            bound = listOf("country"),
        )
        seed.dashboard("sales", 2, RELEASED)

        conforms(
            ReleaseFamily.DASHBOARD,
            present = listOf("sales" to 1, "sales" to 2),
            absent = listOf("sales" to 3),
            fields =
                mapOf(
                    ("sales" to 1) to
                        setOf("parameter_set", "sources[0].pipeline", "visualizations[0].visualization", "visualizations[1].visualization"),
                ),
        )
    }

    @Test
    fun `the dashboard's set edge alone carries parameter_names - the distinct bound names sorted, a literal adds none`() {
        seed.parameterSet("filters", 1)
        seed.pipeline("report", 1)
        seed.pipeline("totals", 1)
        seed.visualization("chart", 1)
        seed.dashboard(
            "sales",
            1,
            DRAFT,
            set = "filters" to 1,
            pipelines = listOf("report" to 1, "totals" to 1),
            visualizations = listOf("chart" to 1),
            bound = listOf("year", "country"),
        )

        val references = outgoing(ReleaseFamily.DASHBOARD, "sales" to 1)

        references.associate { it.sourcePath to it.constraints } shouldBe
            mapOf(
                "parameter_set" to listOf(Constraint.ParameterNames(listOf("country", "year"))),
                "sources[0].pipeline" to emptyList(),
                "sources[1].pipeline" to emptyList(),
                "visualizations[0].visualization" to emptyList(),
            )
    }

    @Test
    fun `another workspace's version is MISSING to every provider - the batch read is workspace-scoped`() {
        seed.template("lib", 1)
        val foreign = ReleaseNodeIdentity(UUID.randomUUID(), ReleaseFamily.TEMPLATE, "lib", 1)

        val provider = descriptors.getValue(ReleaseFamily.TEMPLATE).provider
        val outcomes = inTransaction { provider.resolveMany(listOf(request(foreign)), context()) }

        outcomes.values.single() shouldBe Outcome.Missing
    }

    // ------------------------------------------------------------------------------------------- bounded batches

    @ParameterizedTest
    @EnumSource(value = ReleaseFamily::class, names = ["TEMPLATE", "PARAMETER_SET", "VISUALIZATION", "DASHBOARD"])
    fun `resolveMany is one statement per call at 256 and at 257 distinct requests - no per-node loop`(family: ReleaseFamily) {
        (1..BATCH + 1).forEach { version -> seedVersion(family, version) }
        val provider = descriptors.getValue(family).provider

        listOf(BATCH, BATCH + 1).forEach { count ->
            statements.observed.clear()
            val requests = (1..count).map { request(seed.identity(family, NAME, it)) }

            val outcomes = inTransaction { provider.resolveMany(requests, context()) }

            withClue("$family, $count requests:\n${statements.observed.map { it.first }}") {
                outcomes.values.filterIsInstance<Outcome.Resolved<*>>() shouldHaveSize count
                statements.observed shouldHaveSize 1
            }
        }
    }

    @Test
    fun `the walk reads a template frontier of 256 imports in one call and of 257 in two`() {
        (1..BATCH + 1).forEach { seed.template("lib", it, library = true) }
        seed.template("wide", 1, RELEASED, imports = (1..BATCH).map { "lib" to it })
        seed.template("wide", 2, DRAFT, imports = (1..BATCH + 1).map { "lib" to it })

        listOf(1 to 2, 2 to 3).forEach { (version, expected) ->
            statements.observed.clear()
            val closure = plan(seed.identity(ReleaseFamily.TEMPLATE, "wide", version))
            withClue("root read + ⌈frontier / 256⌉ batch reads, version $version:\n${statements.observed.map { it.first }}") {
                closure.nodes shouldHaveSize 1 + BATCH + version - 1
                statements.observed shouldHaveSize expected
            }
        }
    }

    // --------------------------------------------------------------------------------- the §5.4 graph and A02

    /**
     * U2 (#873 §F) re-runs the U1 fixture with the datasource family registered: the walk loads `warehouse` ONCE as a node
     * reached by the two §5.4 datasource edges (10 nodes, 11 edges); the release closure is the U1 closure unchanged (9
     * nodes, 9 edges — the datasource is a prerequisite, never a closure node) and the plan lists `warehouse` once.
     */
    @Test
    fun `the record's worked graph - the walk loads warehouse once, 10 nodes and 11 edges, the closure stays 9 and 9`() {
        workedGraph()
        val root = seed.identity(ReleaseFamily.DASHBOARD, "sales", 3)

        val graph = inTransaction { walker(withDatasources()).walk(listOf(request(root)), memberContext()) }

        graph.nodes.map { it.requestedAddress.lookupKey } shouldContainExactlyInAnyOrder
            listOf("sales", "filters", "selector", "common", "report", "totals", "query", "chart", "formatter", "warehouse")
        graph.edges shouldHaveSize 11
        val datasourceEdges =
            graph.edges
                .filter { graph.node(it.toNodeId).family == FamilyKey.DATASOURCE }
                .map { "${graph.node(it.fromNodeId).name} -${it.sourcePath}-> ${graph.node(it.toNodeId).name}" }
        datasourceEdges shouldContainExactlyInAnyOrder
            listOf("filters -parameters[0].source.datasource-> warehouse", "totals -nodes[0].source-> warehouse")
        graph.nodes.single { it.family == FamilyKey.DATASOURCE }.resolution shouldBe Resolution.RESOLVED

        val planned = checkNotNull(planningWalk(withDatasources()).planned(root, seed.userId))
        planned.closure.nodes.map { "${it.identity.entityId}@${it.identity.version}" } shouldContainExactlyInAnyOrder
            listOf("sales@3", "filters@2", "selector@5", "common@4", "report@7", "totals@2", "query@6", "chart@2", "formatter@3")
        planned.closure.nodes.map { it.nodeId } shouldContainExactly (0 until 9).map { "n$it" }
        val edges = planned.closure.edges.map { "${it.from.entityId}@${it.from.version} -${it.path}-> ${it.to.entityId}@${it.to.version}" }
        edges shouldContainExactlyInAnyOrder
            listOf(
                "sales@3 -parameter_set-> filters@2",
                "sales@3 -sources[0].pipeline-> report@7",
                "sales@3 -visualizations[0].visualization-> chart@2",
                "filters@2 -parameters[0].source.template-> selector@5",
                "selector@5 -imports[0]-> common@4",
                "report@7 -nodes[0].pipeline-> totals@2",
                "totals@2 -nodes[0].template-> query@6",
                "query@6 -imports[0]-> common@4",
                "chart@2 -transform.template-> formatter@3",
            )
        planned.prerequisites shouldContainExactly listOf(ReleasePrerequisite("warehouse", available = true, readonly = false))
        withClue("the artifact-only closure (U1's walk, no datasource family) is the same closure") {
            plan(root) shouldBe planned.closure
        }
    }

    @Test
    fun `the import diamond loads common@2 once with two edge occurrences`() {
        importDiamond()

        val closure = plan(seed.identity(ReleaseFamily.TEMPLATE, "report", 7))

        closure.nodes.map { "${it.identity.entityId}@${it.identity.version}" } shouldContainExactlyInAnyOrder
            listOf("report@7", "dates@3", "common@2")
        val common = seed.identity(ReleaseFamily.TEMPLATE, "common", 2)
        closure.edges.filter { it.to == common }.map { "${it.from.entityId} ${it.path}" } shouldContainExactlyInAnyOrder
            listOf("report imports[1]", "dates imports[0]")
        closure.dependencyFirst.map { it.identity.entityId } shouldContainExactly listOf("common", "dates", "report")
    }

    // ------------------------------------------------------------------------------------------------ fixtures

    /**
     * Record §5.4: the selector of `filters@2` and the DQL node of `totals@2` both name the datasource `warehouse`
     * (granted to the seed's workspace); the PIPELINE node of `report@7` writes to tempdb, which is no datasource.
     */
    private fun workedGraph() {
        datasources.adopt(seed.userId, seed.workspaceId)
        datasources.datasource("warehouse")
        seed.template("common", 4, library = true)
        seed.template("query", 6, imports = listOf("common" to 4))
        seed.template("selector", 5, imports = listOf("common" to 4))
        seed.template("formatter", 3)
        seed.parameterSet("filters", 2, DRAFT, selectors = listOf("selector" to 5))
        seed.pipeline("totals", 2, templates = listOf("query" to 6), source = "warehouse")
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

    private fun seedVersion(
        family: ReleaseFamily,
        version: Int,
    ) {
        when (family) {
            ReleaseFamily.TEMPLATE -> seed.template(NAME, version)
            ReleaseFamily.PARAMETER_SET -> seed.parameterSet(NAME, version)
            ReleaseFamily.VISUALIZATION -> seed.visualization(NAME, version)
            ReleaseFamily.DASHBOARD -> seed.dashboard(NAME, version)
            else -> error("not a G2 family: $family")
        }
    }

    private fun conforms(
        family: ReleaseFamily,
        present: List<Pair<String, Int>>,
        absent: List<Pair<String, Int>>,
        fields: Map<Pair<String, Int>, Set<String>>,
    ) {
        @Suppress("UNCHECKED_CAST")
        val descriptor = descriptors.getValue(family) as FamilyDescriptor<Any>
        val fixture =
            ProviderConformance.Fixture(
                descriptor = descriptor,
                context = context(),
                present = present.map { request(seed.identity(family, it.first, it.second)) },
                absent =
                    absent.map { request(seed.identity(family, it.first, it.second)) } +
                        request(ReleaseNodeIdentity(UUID.randomUUID(), family, present.first().first, present.first().second)),
                references =
                    ProviderConformance.References.Fields(
                        fields.entries.associate { (pin, paths) -> request(seed.identity(family, pin.first, pin.second)) to paths },
                    ),
            )
        inTransaction { ProviderConformance.check(fixture) }.shouldBeEmpty()
    }

    private fun outgoing(
        family: ReleaseFamily,
        pin: Pair<String, Int>,
    ): List<co.datapipelines.dependencies.Reference> =
        inTransaction {
            @Suppress("UNCHECKED_CAST")
            val provider = descriptors.getValue(family).provider as co.datapipelines.dependencies.DependencyProvider<Any>
            val resolved = provider.resolveMany(listOf(request(seed.identity(family, pin.first, pin.second))), context()).values.single()
            provider.outgoing(resolved.shouldBeInstanceOf<Outcome.Resolved<Any>>())
        }

    private fun plan(root: ReleaseNodeIdentity): ReleaseClosure =
        checkNotNull(planningWalk(FamilyRegistry.production(descriptors.values.toList())).plan(root, seed.userId)) { "$root resolves" }

    private fun planningWalk(registry: FamilyRegistry) =
        ReleasePlanningWalk(
            registry,
            ReleaseBounds(),
            ReleasePlanningWalk.planningTransaction(manager),
            ReleasePlanningWalk.speculativeReads(manager),
            ReleasePlanningDeadline.after(Duration.ofSeconds(BUDGET_SECONDS)),
        )

    /** The release descriptors plus G3's datasource family, as production registers them. */
    private fun withDatasources(): FamilyRegistry =
        FamilyRegistry.production(
            descriptors.values.toList() + DatasourceDependencyFamily.descriptor(DatasourceRepository(ceiling.jdbc), ceiling),
        )

    private fun walker(registry: FamilyRegistry) =
        DependencyWalker(
            registry,
            ReleaseGraphLimits.of(ReleaseBounds(), ReleasePlanningLimits.DEFAULT),
            Clock.systemUTC(),
            CycleHandling.STOP,
        )

    /** The planning walk's own authority: the caller as a workspace member (a datasource resolves within its grants). */
    private fun memberContext() = context().copy(authority = Authority.Member(seed.userId))

    private fun context() =
        ResolveContext(
            seed.workspaceId,
            MetadataSnapshot.READ_COMMITTED,
            Purpose.RELEASE_PLANNING,
            Authority.System,
            Deadline.after(Duration.ofSeconds(BUDGET_SECONDS)).withStatementLimit(Duration.ofSeconds(BUDGET_SECONDS)),
            ReadIsolation.NONE,
        )

    private fun request(identity: ReleaseNodeIdentity): ObjectRequest = ReleaseDependencyFamilies.request(identity)

    private fun <T> inTransaction(read: () -> T): T = checkNotNull(TransactionTemplate(manager).execute { read() })

    private companion object {
        const val NAME = "acme/wide/object"
        const val BATCH = 256
        const val BUDGET_SECONDS = 30L
        val IMPORTS_2 = setOf("imports[0]", "imports[1]")
        val SELECTOR_FIELDS =
            setOf(
                "parameters[0].source.template",
                "parameters[0].source.datasource",
                "parameters[1].source.template",
                "parameters[1].source.datasource",
            )
    }
}
