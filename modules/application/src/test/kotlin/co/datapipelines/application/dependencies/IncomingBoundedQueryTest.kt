package co.datapipelines.application.dependencies

import co.datapipelines.application.SharedPostgres
import co.datapipelines.application.dependencies.datasource.DatasourceDependencyFamily
import co.datapipelines.application.dependencies.parameterset.ParameterSetReleaseProvider
import co.datapipelines.application.dependencies.template.TemplateReleaseProvider
import co.datapipelines.application.dependencies.visualization.VisualizationReleaseProvider
import co.datapipelines.application.endpoints.EndpointVersionBody
import co.datapipelines.application.endpoints.LegacyEndpointColumns
import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.application.endpoints.PublishedEndpointRepository
import co.datapipelines.application.endpoints.PublishedEndpointVersionRepository
import co.datapipelines.datasources.DatasourceRepository
import co.datapipelines.dependencies.DependencyProvider
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.IncomingEdge
import co.datapipelines.dependencies.IncomingOrder
import co.datapipelines.dependencies.IncomingQuery
import co.datapipelines.dependencies.IncomingResult
import co.datapipelines.dependencies.IncomingScope
import co.datapipelines.dependencies.IncomingTarget
import co.datapipelines.dependencies.OwnerScope
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.PipelineNodeRef
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.PipelineVersionStatus.DISCARDED
import co.datapipelines.pipeline.PipelineVersionStatus.DRAFT
import co.datapipelines.pipeline.PipelineVersionStatus.RELEASED
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.WriteMode
import co.datapipelines.scheduler.Schedule
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.visualization.DashboardRepository
import co.datapipelines.visualization.VisualizationRepository
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/**
 * #877 §B on a real PostgreSQL, for each of the twelve arms through the production arm catalog: paging V OCCURRENCES at
 * page size P costs exactly `max(1, ⌈V / P⌉)` statements (counted on the connection, [ObservedStatements]), each of
 * which READS at most P + 1 rows from the database (the SQL bound; an in-memory trim of an unbounded read is red); every
 * page holds at most P; the pages concatenate to the one-page answer in the §A.3 total order with every occurrence once; a V
 * that is a multiple of P ends on a full page whose `next` is null; V = 0 is one statement and an empty page. Each
 * dependent holds up to TWO occurrences — two nodes of one pipeline version, two references of one dashboard, two
 * parameters of one set — so page size one walks the keyset through a repeated reference. Then each arm's scopes answer
 * what the native SQLs answered: LIVE = DRAFT or RELEASED; ANY = every version of an entity that still has a live one
 * (an API version: ANY ≡ LIVE, a DISCARDED version protects nothing); WORKING = the draft, else the pointer; a schedule:
 * every scope = not deleted, paused and blocked included. And the datasource node-type rule (§B.4).
 */
class IncomingBoundedQueryTest {
    private val raw = SharedPostgres.dataSource()
    private val statements = ObservedStatements(SharedPostgres.dataSource())
    private val jdbc = NamedParameterJdbcTemplate(raw)
    private val seed = ReleaseArtifactSeed(jdbc)
    private val others = NonArtifactSeed(jdbc)
    private val ceiling = PostgresStatementCeiling(statements.dataSource)
    private val pipelines = PipelineRepository(ceiling.jdbc)
    private val dashboards = DashboardRepository(ceiling.jdbc)
    private val sets = ParameterSetRepository(ceiling.jdbc)
    private val templates = TemplateRepository(ceiling.jdbc)
    private val endpoints = mutableMapOf<String, UUID>()
    private val schedules = mutableMapOf<String, Schedule>()

    @BeforeEach
    fun reset() {
        seed.reset()
        others.adopt(seed.userId, seed.workspaceId)
        endpoints.clear()
        schedules.clear()
    }

    @ParameterizedTest(name = "{0}: {1} occurrences at page {2}")
    @MethodSource("sizes")
    fun `one statement per page, every occurrence once, in the total order`(
        arm: Arm,
        occurrences: Int,
        page: Int,
    ) {
        seedOccurrences(arm, occurrences)
        val whole = pages(arm, IncomingScope.LIVE, page = 256).single()
        statements.observed.clear()
        statements.rowsRead.clear()
        val paged = pages(arm, IncomingScope.LIVE, page)
        statements.observed shouldHaveSize maxOf(1, (occurrences + page - 1) / page)
        statements.rowsRead shouldHaveSize statements.observed.size
        withClue("$arm at page $page: page statements that read more than ${page + 1} rows (rows read: ${statements.rowsRead})") {
            statements.rowsRead.filter { rows -> rows > page + 1 }.shouldBeEmpty()
        }
        paged.forEach { (it.edges.size <= page) shouldBe true }
        paged.dropLast(1).forEach { it.next shouldNotBe null }
        paged.last().next shouldBe null
        if (occurrences > 0 && occurrences % page == 0) paged.last().edges shouldHaveSize page
        val edges = paged.flatMap { it.edges }
        edges shouldHaveSize occurrences
        edges.map(::key).toSet() shouldHaveSize occurrences
        edges shouldBe whole.edges
        edges.zipWithNext().forEach { (a, b) -> (IncomingOrder.EDGES.compare(a, b) < 0) shouldBe true }
    }

    @ParameterizedTest
    @EnumSource(Arm::class)
    fun `each scope answers what the native SQL answered`(arm: Arm) {
        if (arm == Arm.SCHEDULE_PIPELINE) {
            val active = pin(arm, "active", 0, RELEASED)
            val paused = pin(arm, "paused", 0, RELEASED).also { others.pause(scheduleOf(it)) }
            val blocked = pin(arm, "blocked", 0, RELEASED).also { others.block(scheduleOf(it)) }
            pin(arm, "deleted", 0, RELEASED).also { others.softDelete(scheduleOf(it)) }
            IncomingScope.entries.forEach { scope ->
                answered(arm, scope) shouldContainExactlyInAnyOrder listOf("$active@0", "$paused@0", "$blocked@0")
            }
            return
        }
        pin(arm, "kept", 1, RELEASED)
        pin(arm, "kept", 2, DISCARDED)
        pin(arm, "dead", 1, DISCARDED)
        pin(arm, "drafty", 1, RELEASED)
        pin(arm, "drafty", 2, DRAFT)
        val live = listOf("kept@1", "drafty@1", "drafty@2")
        answered(arm, IncomingScope.LIVE) shouldContainExactlyInAnyOrder live
        answered(arm, IncomingScope.ANY) shouldContainExactlyInAnyOrder
            if (arm == Arm.API_PIPELINE) live else live + "kept@2"
        answered(arm, IncomingScope.WORKING) shouldContainExactlyInAnyOrder listOf("kept@1", "drafty@2")
    }

    @Test
    fun `a datasource is read and written by the approved node types only`() {
        val output = NodeOutput.Datasource(WAREHOUSE, "t", WriteMode.REPLACE)
        val found = listOf(NodeType.DQL, NodeType.DML, NodeType.DDL)
        val nodes =
            found.flatMap { listOf(node("${it.wire}_read", it, WAREHOUSE), node("${it.wire}_write", it, "pg", output)) } +
                listOf(
                    node("child", NodeType.PIPELINE, "", output, PipelineNodeRef(DAILY, 1)),
                    node("script", NodeType.SCRIPT, WAREHOUSE, output),
                    node("calculator", NodeType.CALCULATOR, WAREHOUSE, output),
                    node("staged", NodeType.DQL, "tempdb"),
                )
        seed.pipelineNodes("loader", 1, RELEASED, nodes)
        val edges = pages(Arm.PIPELINE_DATASOURCE, IncomingScope.LIVE, page = 256).single().edges
        edges.map { it.elementId to it.sourcePath } shouldContainExactlyInAnyOrder
            found
                .flatMap { listOf("${it.wire}_read" to "source", "${it.wire}_write" to "output.datasource") }
                .map { (id, field) -> id to "nodes[${nodes.indexOfFirst { n -> n.id == id }}].$field" } +
            ("child" to "nodes[6].output.datasource")
        val tempdb = IncomingTarget.Entity(FamilyKey.DATASOURCE, OwnerScope.Deployment, "tempdb")
        pages(Arm.PIPELINE_DATASOURCE, IncomingScope.ANY, page = 256, target = tempdb).single().edges.shouldBeEmpty()
    }

    /** Seeds [count] occurrences: dependents `d0, d1, …` with up to two occurrences each (two versions where one holds one). */
    private fun seedOccurrences(
        arm: Arm,
        count: Int,
    ) {
        (0 until count).chunked(if (arm == Arm.SCHEDULE_PIPELINE) 1 else 2).forEachIndexed { index, chunk ->
            if (arm.repeatable) {
                pin(arm, "d$index", 1, RELEASED, chunk.size)
            } else {
                chunk.indices.forEach { pin(arm, "d$index", it + 1, if (it == 0) RELEASED else DRAFT) }
            }
        }
    }

    /** One dependent [name]@[version] in [status] holding [count] references to the arm's target; its answered name. */
    @Suppress("CyclomaticComplexMethod") // one branch per arm: the twelve dependent shapes side by side
    private fun pin(
        arm: Arm,
        name: String,
        version: Int,
        status: PipelineVersionStatus,
        count: Int = 1,
    ): String {
        val lib = List(count) { LIB to 1 }
        when (arm) {
            Arm.PIPELINE_TEMPLATE -> {
                seed.pipeline(name, version, status, templates = lib)
            }

            Arm.TEMPLATE_IMPORT -> {
                seed.template(name, version, status, imports = lib)
            }

            Arm.SET_TEMPLATE, Arm.SET_DATASOURCE -> {
                seed.parameterSet(name, version, status, selectors = lib)
            }

            Arm.VISUALIZATION_TEMPLATE -> {
                seed.visualization(name, version, status, transform = LIB to 1)
            }

            Arm.PIPELINE_CHILD -> {
                seed.pipeline(name, version, status, children = List(count) { DAILY to 1 })
            }

            Arm.DASHBOARD_PIPELINE -> {
                seed.dashboard(name, version, status, pipelines = List(count) { DAILY to 1 })
            }

            Arm.DASHBOARD_SET -> {
                seed.dashboard(name, version, status, set = LIB to 1)
            }

            Arm.DASHBOARD_VISUALIZATION -> {
                seed.dashboard(name, version, status, visualizations = lib)
            }

            Arm.API_PIPELINE -> {
                apiVersion(name, version, status)
            }

            Arm.SCHEDULE_PIPELINE -> {
                return schedules.getOrPut(name) { others.schedule(name, others.pipelinePayload(DAILY, 1)) }.id.toString()
            }

            Arm.PIPELINE_DATASOURCE -> {
                seed.pipelineNodes(
                    name,
                    version,
                    status,
                    List(count) {
                        if (it == 0) {
                            node("read", NodeType.DQL, WAREHOUSE)
                        } else {
                            node(
                                "child",
                                NodeType.PIPELINE,
                                "",
                                NodeOutput.Datasource(WAREHOUSE, "t", WriteMode.REPLACE),
                                PipelineNodeRef(DAILY, 1),
                            )
                        }
                    },
                )
            }
        }
        return name
    }

    private fun apiVersion(
        name: String,
        version: Int,
        status: PipelineVersionStatus,
    ) {
        val versions = PublishedEndpointVersionRepository(jdbc)
        val api = endpoints.getOrPut(name) { endpoint("/inverse/v1/$name") }
        versions.insert(api, version, if (status == DRAFT) DRAFT else RELEASED, EndpointVersionBody(DAILY, 1, 30, ""), seed.userId)
        if (status == RELEASED) versions.setCurrent(api, version)
        if (status == DISCARDED) versions.discard(api, version, seed.userId, draftEligible = false)
    }

    /** The answered `name@version` occurrences at [scope] (an API's name is its path; a schedule's, its id). */
    private fun answered(
        arm: Arm,
        scope: IncomingScope,
    ): List<String> =
        pages(arm, scope, page = 256).single().edges.map { edge ->
            val name = if (arm == Arm.SCHEDULE_PIPELINE) edge.dependent.stableEntityId else edge.dependentName
            "${name.removePrefix("/inverse/v1/")}@${edge.dependentVersion}"
        }

    /** Every page of the arm's question, in order, inside one transaction (the ceiling's statement bound needs it). */
    private fun pages(
        arm: Arm,
        scope: IncomingScope,
        page: Int,
        target: IncomingTarget = targetOf(arm),
    ): List<IncomingResult.Page> =
        requireNotNull(
            TransactionTemplate(DataSourceTransactionManager(statements.dataSource)).execute {
                val answers = mutableListOf<IncomingResult.Page>()
                var query: IncomingQuery? = IncomingQuery(target, arm.dependents, scope, null, page)
                while (query != null) {
                    val answer = providerOf(arm).findIncoming(query, others.context()) as IncomingResult.Page
                    answers += answer
                    query = answer.next?.let(query::after)
                }
                answers
            },
        )

    private fun targetOf(arm: Arm): IncomingTarget {
        val workspace = OwnerScope.Workspace(seed.workspaceId)
        return when (arm.target) {
            FamilyKey.DATASOURCE -> IncomingTarget.Entity(FamilyKey.DATASOURCE, OwnerScope.Deployment, WAREHOUSE)
            FamilyKey.PIPELINE -> IncomingTarget.ExactVersion(FamilyKey.PIPELINE, workspace, DAILY, 1)
            else -> IncomingTarget.ExactVersion(arm.target, workspace, LIB, 1)
        }
    }

    private fun providerOf(arm: Arm): DependencyProvider<*> =
        when (arm.target) {
            FamilyKey.TEMPLATE -> {
                TemplateReleaseProvider(
                    templates,
                    ceiling,
                    IncomingArmCatalog.template(pipelines, templates, sets, VisualizationRepository(ceiling.jdbc), ceiling),
                )
            }

            FamilyKey.PIPELINE -> {
                val arms =
                    IncomingArmCatalog.pipeline(
                        pipelines,
                        dashboards,
                        PublishedEndpointVersionRepository(ceiling.jdbc),
                        ScheduleTargetQueries(ceiling.jdbc),
                        IncomingConformancePostgresTest.SCHEDULE_SHAPE,
                        ceiling,
                    )
                PipelineReleaseFamily.descriptor(pipelines, ceiling, incoming = arms).provider
            }

            FamilyKey.PARAMETER_SET -> {
                ParameterSetReleaseProvider(sets, templates, ceiling, IncomingArmCatalog.parameterSet(dashboards, ceiling))
            }

            FamilyKey.VISUALIZATION -> {
                VisualizationReleaseProvider(
                    VisualizationRepository(ceiling.jdbc),
                    ceiling,
                    incoming = IncomingArmCatalog.visualization(dashboards, ceiling),
                )
            }

            else -> {
                val arms = IncomingArmCatalog.datasource(pipelines, sets, ceiling)
                DatasourceDependencyFamily.descriptor(DatasourceRepository(ceiling.jdbc), ceiling, incoming = arms).provider
            }
        }

    private fun scheduleOf(id: String): Schedule = schedules.values.single { it.id.toString() == id }

    private fun key(edge: IncomingEdge) = Triple(edge.dependent.stableEntityId, edge.dependentVersion, edge.sourcePath)

    private fun node(
        id: String,
        type: NodeType,
        source: String,
        output: NodeOutput = NodeOutput.Caller,
        pipeline: PipelineNodeRef? = null,
    ) = Node(
        id = id,
        description = "",
        type = type,
        source = source,
        template = TemplateRef("t", 1),
        output = output,
        dependsOn = emptyList(),
        pipeline = pipeline,
    )

    private fun endpoint(path: String): UUID {
        if (endpoints.isEmpty()) seed.pipeline(DAILY, 1, RELEASED)
        val pipelineId =
            requireNotNull(
                jdbc.queryForObject(
                    "SELECT id FROM pipelines WHERE name = :name AND workspace_id = :ws",
                    mapOf("name" to DAILY, "ws" to seed.workspaceId),
                    UUID::class.java,
                ),
            )
        val endpoint =
            PublishedEndpoint.of(
                id = UUID.randomUUID(),
                workspaceId = seed.workspaceId,
                pathPattern = path,
                isEnabled = true,
                createdBy = seed.userId,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            )
        return requireNotNull(
            TransactionTemplate(DataSourceTransactionManager(raw)).execute {
                PublishedEndpointRepository(jdbc).insert(endpoint, LegacyEndpointColumns(pipelineId, 30, "")).id
            },
        )
    }

    /** The twelve arms of the §14 table: dependent family → target family; [repeatable] = one version holds two. */
    enum class Arm(
        val dependents: FamilyKey,
        val target: FamilyKey,
        val repeatable: Boolean,
    ) {
        PIPELINE_TEMPLATE(FamilyKey.PIPELINE, FamilyKey.TEMPLATE, true),
        TEMPLATE_IMPORT(FamilyKey.TEMPLATE, FamilyKey.TEMPLATE, true),
        SET_TEMPLATE(FamilyKey.PARAMETER_SET, FamilyKey.TEMPLATE, true),
        VISUALIZATION_TEMPLATE(FamilyKey.VISUALIZATION, FamilyKey.TEMPLATE, false),
        PIPELINE_CHILD(FamilyKey.PIPELINE, FamilyKey.PIPELINE, true),
        DASHBOARD_PIPELINE(FamilyKey.DASHBOARD, FamilyKey.PIPELINE, true),
        API_PIPELINE(FamilyKey.API_ENDPOINT, FamilyKey.PIPELINE, false),
        SCHEDULE_PIPELINE(FamilyKey.SCHEDULE, FamilyKey.PIPELINE, false),
        DASHBOARD_SET(FamilyKey.DASHBOARD, FamilyKey.PARAMETER_SET, false),
        DASHBOARD_VISUALIZATION(FamilyKey.DASHBOARD, FamilyKey.VISUALIZATION, true),
        PIPELINE_DATASOURCE(FamilyKey.PIPELINE, FamilyKey.DATASOURCE, true),
        SET_DATASOURCE(FamilyKey.PARAMETER_SET, FamilyKey.DATASOURCE, true),
    }

    companion object {
        /** NonArtifactSeed's schedules target this pipeline. */
        const val DAILY = "acme/rides/daily"
        const val LIB = "lib"
        const val WAREHOUSE = "warehouse"

        /** V > P with a remainder, V a multiple of P, V = 0, and page size one over repeated references. */
        @JvmStatic
        fun sizes(): List<Arguments> =
            Arm.entries.flatMap { arm -> listOf(5 to 2, 4 to 2, 0 to 2, 5 to 1).map { (v, p) -> Arguments.of(arm, v, p) } }
    }
}
