package co.datapipelines.application.dependencies

import co.datapipelines.application.SharedPostgres
import co.datapipelines.application.dependencies.dashboard.DashboardReleaseProvider
import co.datapipelines.application.dependencies.parameterset.ParameterSetReleaseProvider
import co.datapipelines.application.dependencies.template.TemplateReleaseProvider
import co.datapipelines.application.dependencies.visualization.VisualizationReleaseProvider
import co.datapipelines.dependencies.Authority
import co.datapipelines.dependencies.Deadline
import co.datapipelines.dependencies.DependencyProvider
import co.datapipelines.dependencies.DependencyWalker
import co.datapipelines.dependencies.FamilyRegistry
import co.datapipelines.dependencies.GraphSnapshot
import co.datapipelines.dependencies.MetadataSnapshot
import co.datapipelines.dependencies.Operational
import co.datapipelines.dependencies.Outcome
import co.datapipelines.dependencies.Purpose
import co.datapipelines.dependencies.ReadIsolation
import co.datapipelines.dependencies.ResolveContext
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionStatus.DRAFT
import co.datapipelines.pipeline.PipelineVersionStatus.RELEASED
import co.datapipelines.pipeline.release.PipelineDependencySource
import co.datapipelines.pipeline.release.ReleaseBounds
import co.datapipelines.pipeline.release.ReleaseClosure
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.TreeHash
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.visualization.ArtifactValidationException
import co.datapipelines.visualization.DashboardErrorCodes
import co.datapipelines.visualization.DashboardRepository
import co.datapipelines.visualization.StoredEvidence
import co.datapipelines.visualization.VisualizationRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
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
import java.time.Duration
import java.time.Instant

/**
 * #873 round 2 on a real PostgreSQL, through the production descriptors and the neutral walker:
 *
 * - R1 (#891): a visualization node's `evidence_ready` is decided from its STORED latest run for the exact stored hash —
 *   the repository read classifies every evidence class apart (GREEN exact-hash, absent, mismatched, stale, open, expired,
 *   RED, INCOMPLETE) and only READY sets the fact; the batch stays ONE statement at 256 and 257 requests with runs.
 * - R2 (#893): every declared import alias rides its own edge occurrence (a diamond, a target imported twice), and
 *   rewriting the stored aliases leaves the plan's `tree_hash` byte-identical.
 * - R3 (#892, A34): a dashboard whose stored body binds a name its pinned set version lacks (written past the save
 *   validator) refuses through the PRODUCTION planning walk — at plan and on the guarded re-walk — with the dashboard's
 *   native `parameter_unbound` located at the set edge; the satisfied, literal and repeated-binding pairs plan.
 */
class ArtifactGraphFactsPostgresTest {
    private val statements = ObservedStatements(SharedPostgres.dataSource())
    private val seed = ReleaseArtifactSeed(NamedParameterJdbcTemplate(SharedPostgres.dataSource()))
    private val manager = DataSourceTransactionManager(statements.dataSource)
    private val ceiling = PostgresStatementCeiling(statements.dataSource)
    private val visualizations = VisualizationRepository(ceiling.jdbc)

    private val descriptors =
        listOf(
            ReleaseDependencyFamilies.descriptor(ReleaseFamily.PIPELINE, PipelineDependencySource(PipelineRepository(ceiling.jdbc)), ceiling),
            TemplateReleaseProvider(TemplateRepository(ceiling.jdbc), ceiling).descriptor(),
            ParameterSetReleaseProvider(ParameterSetRepository(ceiling.jdbc), ceiling).descriptor(),
            VisualizationReleaseProvider(visualizations, ceiling).descriptor(),
            DashboardReleaseProvider(DashboardRepository(ceiling.jdbc), ceiling).descriptor(),
        )
    private val registry = FamilyRegistry.production(descriptors)

    @BeforeEach
    fun reset() {
        seed.reset()
    }

    // ------------------------------------------------------------------------------------- R1: evidence_ready

    /** One stored-evidence situation of `chart@2` (stored body hash `a-chart-2`) and the class the gate's ladder gives it. */
    enum class Evidence(
        val expected: StoredEvidence,
    ) {
        GREEN_EXACT_HASH(StoredEvidence.READY),
        GREEN_LATEST_OVER_OLDER_RED(StoredEvidence.READY),
        ABSENT(StoredEvidence.NO_RUNS),
        GREEN_ON_ANOTHER_VERSION_ONLY(StoredEvidence.NO_RUNS),
        GREEN_FOR_OTHER_CONTENT(StoredEvidence.HASH_MISMATCH),
        STALE_GREEN_UNDER_LATER_RED(StoredEvidence.NOT_GREEN),
        OPEN_SESSION(StoredEvidence.OPEN),
        RUNNING_PAST_ITS_DEADLINE(StoredEvidence.EXPIRED),
        SWEPT_EXPIRED(StoredEvidence.EXPIRED),
        RED(StoredEvidence.NOT_GREEN),
        INCOMPLETE(StoredEvidence.NOT_GREEN),
    }

    private fun seedEvidence(evidence: Evidence) {
        seed.visualization("chart", 1)
        seed.visualization("chart", 2, DRAFT)
        when (evidence) {
            Evidence.GREEN_EXACT_HASH -> seed.testRun("chart", 2, "GREEN")
            Evidence.GREEN_LATEST_OVER_OLDER_RED -> {
                seed.testRun("chart", 2, "RED", startedSecondsAgo = 60)
                seed.testRun("chart", 2, "GREEN")
            }
            Evidence.ABSENT -> Unit
            Evidence.GREEN_ON_ANOTHER_VERSION_ONLY -> seed.testRun("chart", 1, "GREEN")
            Evidence.GREEN_FOR_OTHER_CONTENT -> seed.testRun("chart", 2, "GREEN", bodyHash = "a-chart-2-edited-since")
            Evidence.STALE_GREEN_UNDER_LATER_RED -> {
                seed.testRun("chart", 2, "GREEN", startedSecondsAgo = 60)
                seed.testRun("chart", 2, "RED")
            }
            Evidence.OPEN_SESSION -> seed.testRun("chart", 2, "RUNNING")
            Evidence.RUNNING_PAST_ITS_DEADLINE -> seed.testRun("chart", 2, "RUNNING", expiresInSeconds = -1)
            Evidence.SWEPT_EXPIRED -> seed.testRun("chart", 2, "EXPIRED")
            Evidence.RED -> seed.testRun("chart", 2, "RED")
            Evidence.INCOMPLETE -> seed.testRun("chart", 2, "INCOMPLETE")
        }
    }

    @ParameterizedTest
    @EnumSource(Evidence::class)
    fun `the stored latest run decides evidence_ready - the read classifies every class apart, only READY is ready`(evidence: Evidence) {
        seedEvidence(evidence)

        val (version, latest) = inTransaction { visualizations.findVersionsWithLatestRun(seed.workspaceId, listOf("chart" to 2)) }.single()
        val classified = StoredEvidence.classify(latest, version.detail.bodyHash, Instant.now())
        val node = walk(seed.identity(ReleaseFamily.VISUALIZATION, "chart", 2)).nodes.first()

        withClue("$evidence: read ${latest?.status} ${latest?.bodyHash}") {
            classified shouldBe evidence.expected
            node.operational shouldBe Operational.Visualization(evidenceReady = evidence.expected == StoredEvidence.READY)
        }
    }

    @Test
    fun `a dashboard's walked visualization node carries the stored fact - the graph, not only the provider`() {
        seed.visualization("ready", 1)
        seed.visualization("red", 1)
        seed.testRun("ready", 1, "GREEN")
        seed.testRun("red", 1, "RED")
        seed.dashboard("board", 1, DRAFT, visualizations = listOf("ready" to 1, "red" to 1))

        val graph = walk(seed.identity(ReleaseFamily.DASHBOARD, "board", 1))

        graph.nodes.filter { it.name in setOf("ready", "red") }.associate { it.name to it.operational } shouldBe
            mapOf("ready" to Operational.Visualization(true), "red" to Operational.Visualization(false))
        graph.nodes.first().operational shouldBe null
    }

    @Test
    fun `with stored runs the visualization batch is still ONE statement at 256 and at 257 requests`() {
        (1..BATCH + 1).forEach { version ->
            seed.visualization(NAME, version)
            seed.testRun(NAME, version, if (version % 2 == 0) "GREEN" else "RED", startedSecondsAgo = 5)
            seed.testRun(NAME, version, "RED", startedSecondsAgo = 60)
        }
        val provider = provider(ReleaseFamily.VISUALIZATION)

        listOf(BATCH, BATCH + 1).forEach { count ->
            statements.observed.clear()
            val requests = (1..count).map { ReleaseDependencyFamilies.request(seed.identity(ReleaseFamily.VISUALIZATION, NAME, it)) }

            val outcomes = inTransaction { provider.resolveMany(requests, context()) }

            withClue("$count requests:\n${statements.observed.map { it.first }}") {
                statements.observed shouldHaveSize 1
                val ready =
                    requests.map { request ->
                        val resolved = outcomes.getValue(request).shouldBeInstanceOf<Outcome.Resolved<*>>()
                        (resolved.facts.operational as Operational.Visualization).evidenceReady
                    }
                ready shouldBe (1..count).map { it % 2 == 0 }
            }
        }
    }

    // ------------------------------------------------------------------------------------------- R2: aliases

    @Test
    fun `a diamond keeps every declared alias on its own occurrence`() {
        seed.template("common", 2, library = true)
        seed.template("dates", 3, library = true, imports = listOf("common" to 2), aliases = listOf("base"))
        seed.template("report", 7, DRAFT, imports = listOf("dates" to 3, "common" to 2), aliases = listOf("cal", "shared"))

        val graph = walk(seed.identity(ReleaseFamily.TEMPLATE, "report", 7))

        aliases(graph) shouldContainExactlyInAnyOrder listOf("report imports[0] dates cal", "report imports[1] common shared", "dates imports[0] common base")
    }

    @Test
    fun `a target imported twice under two aliases is two edges, each with its own alias`() {
        seed.template("common", 2, library = true)
        seed.template("report", 1, DRAFT, imports = listOf("common" to 2, "common" to 2), aliases = listOf("left", "right"))

        val graph = walk(seed.identity(ReleaseFamily.TEMPLATE, "report", 1))

        graph.nodes shouldHaveSize 2
        aliases(graph) shouldContainExactlyInAnyOrder listOf("report imports[0] common left", "report imports[1] common right")
    }

    @Test
    fun `rewriting the stored aliases leaves the plan's tree_hash byte-identical - provenance is never hashed`() {
        seed.template("common", 2, library = true)
        seed.template("report", 1, DRAFT, imports = listOf("common" to 2, "common" to 2), aliases = listOf("left", "right"))
        val root = seed.identity(ReleaseFamily.TEMPLATE, "report", 1)
        val before = plan(root)

        NamedParameterJdbcTemplate(SharedPostgres.dataSource()).update(
            "UPDATE template_versions SET imports_json = CAST(:imports AS jsonb)" +
                " WHERE version = 1 AND template_id = (SELECT id FROM templates WHERE name = 'report' AND workspace_id = :ws)",
            mapOf(
                "imports" to """[{"id":"common","version":2,"alias":"west"},{"id":"common","version":2,"alias":"east"}]""",
                "ws" to seed.workspaceId,
            ),
        )
        val after = plan(root)

        aliases(walk(root)) shouldContainExactlyInAnyOrder listOf("report imports[0] common west", "report imports[1] common east")
        TreeHash.canonical(after) shouldBe TreeHash.canonical(before)
        TreeHash.of(after, ReleaseBounds().maxWireBytes) shouldBe TreeHash.of(before, ReleaseBounds().maxWireBytes)
    }

    // ------------------------------------------------------------------------------------------------ R3: A34

    /** `board@1` → set `filters@1` declaring [declared]; one source per pipeline binds [bound] and the literals [literals]. */
    private fun boardOver(
        declared: List<String>,
        bound: List<String>,
        literals: List<String> = emptyList(),
        pipelines: Int = 1,
    ) {
        seed.template("selector", 1)
        seed.parameterSet("filters", 1, selectors = declared.map { "selector" to 1 }, parameterNames = declared)
        (1..pipelines).forEach { seed.pipeline("report$it", 1) }
        seed.dashboard(
            "board",
            1,
            DRAFT,
            set = "filters" to 1,
            pipelines = (1..pipelines).map { "report$it" to 1 },
            bound = bound,
            literals = literals,
        )
    }

    @Test
    fun `a bound name the pinned set version lacks refuses the plan natively, located at the set edge`() {
        boardOver(declared = listOf("country"), bound = listOf("country", "year", "region"))
        val root = seed.identity(ReleaseFamily.DASHBOARD, "board", 1)
        val setEdge = walk(root).edges.single { it.sourcePath == "parameter_set" }

        val refusal = shouldThrow<ArtifactValidationException> { plan(root) }

        refusal.code shouldBe DashboardErrorCodes.PARAMETER_UNBOUND
        refusal.result.failures.map { Triple(it.code, it.path, it.details["parameter"]) } shouldBe
            listOf(
                Triple(DashboardErrorCodes.PARAMETER_UNBOUND, "parameter_set", "region"),
                Triple(DashboardErrorCodes.PARAMETER_UNBOUND, "parameter_set", "year"),
            )
        refusal.result.failures.forEach { failure ->
            failure.details["edge_id"] shouldBe setEdge.edgeId
            failure.details["reason"] shouldBe "set_parameter_unknown"
            failure.details["parameter_set"] shouldBe mapOf("name" to "filters", "version" to 1)
            failure.message.contains("region") shouldBe false
        }
    }

    @Test
    fun `the guarded re-walk refuses the same edge again - the commit path, inside the caller's transaction`() {
        boardOver(declared = listOf("country"), bound = listOf("year"))
        val root = seed.identity(ReleaseFamily.DASHBOARD, "board", 1)

        val refusal = shouldThrow<ArtifactValidationException> { inTransaction { planningWalk().walkInCurrentTransaction(root, seed.userId) } }

        refusal.result.failures.map { it.path to it.details["parameter"] } shouldBe listOf("parameter_set" to "year")
    }

    @Test
    fun `the satisfied pair plans - one extra batched read of the pinned set, its closure unchanged`() {
        boardOver(declared = listOf("country", "year"), bound = listOf("country", "year"))

        statements.observed.clear()
        val closure = plan(seed.identity(ReleaseFamily.DASHBOARD, "board", 1))

        closure.nodes.map { it.identity.entityId } shouldContainExactlyInAnyOrder listOf("board", "filters", "selector", "report1")
        withClue(statements.observed.map { it.first }.toString()) {
            statements.observed.count { it.first.contains("parameter_set_versions") } shouldBe 2
        }
    }

    @Test
    fun `a literal binding and a repeated valid binding add nothing - A34's other two cases`() {
        boardOver(declared = listOf("country"), bound = listOf("country"), literals = listOf("year"), pipelines = 3)

        val closure = plan(seed.identity(ReleaseFamily.DASHBOARD, "board", 1))

        closure.nodes.map { it.identity.entityId } shouldContainExactlyInAnyOrder listOf("board", "filters", "selector", "report1", "report2", "report3")
    }

    @Test
    fun `a dashboard with no set binds nothing and reads no set - the check is silent`() {
        seed.pipeline("report1", 1)
        seed.dashboard("board", 1, DRAFT, pipelines = listOf("report1" to 1))

        statements.observed.clear()
        plan(seed.identity(ReleaseFamily.DASHBOARD, "board", 1)).nodes shouldHaveSize 2

        statements.observed.count { it.first.contains("parameter_set_versions") } shouldBe 0
    }

    // ------------------------------------------------------------------------------------------------ fixtures

    private fun aliases(graph: GraphSnapshot): List<String> =
        graph.edges.map { edge -> "${graph.node(edge.fromNodeId).name} ${edge.sourcePath} ${graph.node(edge.toNodeId).name} ${edge.alias}" }

    private fun walk(root: ReleaseNodeIdentity): GraphSnapshot =
        inTransaction {
            DependencyWalker(registry, ReleaseGraphLimits.of(ReleaseBounds())).walk(listOf(ReleaseDependencyFamilies.request(root)), context())
        }

    private fun planningWalk() =
        ReleasePlanningWalk(
            registry,
            ReleaseBounds(),
            ReleasePlanningWalk.planningTransaction(manager),
            ReleasePlanningWalk.speculativeReads(manager),
            ReleasePlanningDeadline.after(Duration.ofSeconds(BUDGET_SECONDS)),
        )

    private fun plan(root: ReleaseNodeIdentity): ReleaseClosure = checkNotNull(planningWalk().plan(root, seed.userId)) { "$root resolves" }

    @Suppress("UNCHECKED_CAST")
    private fun provider(family: ReleaseFamily) = checkNotNull(registry[ReleaseDependencyFamilies.keyOf(family)]).provider as DependencyProvider<Any>

    private fun context() =
        ResolveContext(
            seed.workspaceId,
            MetadataSnapshot.READ_COMMITTED,
            Purpose.RELEASE_PLANNING,
            Authority.System,
            Deadline.after(Duration.ofSeconds(BUDGET_SECONDS)).withStatementLimit(Duration.ofSeconds(BUDGET_SECONDS)),
            ReadIsolation.NONE,
        )

    private fun <T> inTransaction(read: () -> T): T = checkNotNull(TransactionTemplate(manager).execute { read() })

    private companion object {
        const val NAME = "acme/wide/chart"
        const val BATCH = 256
        const val BUDGET_SECONDS = 30L
    }
}
