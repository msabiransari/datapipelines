package co.datapipelines.application.dependencies

import co.datapipelines.application.SharedPostgres
import co.datapipelines.application.dependencies.prerequisites.DatasourceEdges
import co.datapipelines.application.dependencies.template.TemplateReleaseProvider
import co.datapipelines.dependencies.Authority
import co.datapipelines.dependencies.Deadline
import co.datapipelines.dependencies.DeadlineExceeded
import co.datapipelines.dependencies.FamilyDescriptor
import co.datapipelines.dependencies.FamilyRegistry
import co.datapipelines.dependencies.GraphLimits
import co.datapipelines.dependencies.MetadataSnapshot
import co.datapipelines.dependencies.ObjectRequest
import co.datapipelines.dependencies.Outcome
import co.datapipelines.dependencies.Purpose
import co.datapipelines.dependencies.ReadIsolation
import co.datapipelines.dependencies.ResolveContext
import co.datapipelines.pipeline.PipelineDeserializer
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.PipelineVersionStatus.DISCARDED
import co.datapipelines.pipeline.PipelineVersionStatus.DRAFT
import co.datapipelines.pipeline.PipelineVersionStatus.RELEASED
import co.datapipelines.pipeline.release.PipelineDependencySource
import co.datapipelines.pipeline.release.ReleaseBounds
import co.datapipelines.pipeline.release.ReleaseClosure
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.ReleaseNodeStatus
import co.datapipelines.templates.TemplateRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * #887 on a real PostgreSQL: the pipeline family's release provider ([PipelineReleaseFamily.descriptor], built exactly as
 * `ReleaseConfiguration.pipelineDependencyFamily` builds it — a repository over the ceiling's own template) resolves a
 * batch in ONE bounded statement per workspace (record §7.1, §25.4 D12) and answers what the untouched native
 * [PipelineDependencySource] answers, node for node. Statement counter: [ObservedStatements] (every real statement on
 * the connection, the ceiling's own `set_config` excluded).
 *
 * - §C.1 256 distinct versions in one call are ONE statement; a walk whose pipeline frontier is 257 costs two provider
 *   statements for that frontier (`resolveMany` is never per node).
 * - §C.2 parity with the native per-node read over generated DRAFT / RELEASED / DISCARDED / absent mixes, datasource
 *   references included, with a floor of one compared node per status.
 * - §C.3 another workspace's version of the same name and number is [Outcome.Missing], exactly as an absent one.
 * - §C.4 a statement held past its bound and a result read after expiry are both [DeadlineExceeded], never a partial map.
 * - §C.5 one release closure planned through this provider equals the closure planned through the adapted native source.
 */
class PipelineReleaseProviderPostgresTest {
    private val clock = HandClock()
    private var afterStatement: () -> Unit = {}
    private val statements = ObservedStatements(SharedPostgres.dataSource()) { afterStatement() }
    private val jdbc = NamedParameterJdbcTemplate(SharedPostgres.dataSource())
    private val seed = ReleaseArtifactSeed(jdbc)
    private val manager = DataSourceTransactionManager(statements.dataSource)
    private val ceiling = PostgresStatementCeiling(statements.dataSource)
    private val repository = PipelineRepository(ceiling.jdbc)
    private val descriptor = PipelineReleaseFamily.descriptor(repository, ceiling)
    private val native = PipelineDependencySource(repository)
    private val templates = TemplateReleaseProvider(TemplateRepository(ceiling.jdbc), ceiling).descriptor()
    private val lock = HeldTableLock(SharedPostgres.dataSource())

    @BeforeEach
    fun reset() {
        seed.reset()
        afterStatement = {}
    }

    @AfterEach
    fun release() {
        lock.close()
    }

    // ------------------------------------------------------------------------------------------------ C.1 the bound

    @Test
    fun `256 distinct pipeline versions resolve in ONE statement - and a batch of 257 is refused on entry`() {
        (1..BATCH + 1).forEach { seed.pipeline(NAME, it) }
        val requests = (1..BATCH).map { request(seed.identity(ReleaseFamily.PIPELINE, NAME, it)) }

        statements.observed.clear()
        val outcomes = inTransaction { descriptor.provider.resolveMany(requests, context()) }

        withClue("256 requests:\n${statements.observed.map { it.first }}") {
            outcomes.values.filterIsInstance<Outcome.Resolved<*>>() shouldHaveSize BATCH
            statements.observed shouldHaveSize 1
        }
        statements.observed.clear()
        val above = requests + request(seed.identity(ReleaseFamily.PIPELINE, NAME, BATCH + 1))
        shouldThrow<IllegalArgumentException> { inTransaction { descriptor.provider.resolveMany(above, context()) } }
        statements.observed.shouldBeEmpty()
    }

    @Test
    fun `the walk reads a pipeline frontier of 256 children in one call and of 257 in two`() {
        (1..BATCH + 1).forEach { seed.pipeline(LEAF, it) }
        seed.pipeline(NAME, 1, children = (1..BATCH).map { LEAF to it })
        seed.pipeline(NAME, 2, DRAFT, children = (1..BATCH + 1).map { LEAF to it })

        listOf(1 to 2, 2 to 3).forEach { (version, expected) ->
            statements.observed.clear()
            val closure = plan(registry(descriptor), seed.identity(ReleaseFamily.PIPELINE, NAME, version))
            withClue("root read + ⌈frontier / 256⌉ batch reads, version $version:\n${statements.observed.map { it.first }}") {
                closure.nodes shouldHaveSize 1 + BATCH + version - 1
                statements.observed shouldHaveSize expected
            }
        }
    }

    // ----------------------------------------------------------------------------------------------- C.2 parity

    @Test
    fun `every generated mix resolves exactly as the native per-node read - nodes, pins and datasource references`() {
        val compared = HashMap<ReleaseNodeStatus, Int>()
        var missing = 0
        repeat(MIXES) { mix ->
            seed.reset()
            val random = Random(SEED + mix)
            val identities = generatedMix(random)
            val requests = identities.map(::request) + request(identities.first()) // a duplicate answers once per request

            val outcomes = inTransaction { descriptor.provider.resolveMany(requests, context()) }

            outcomes.keys shouldBe requests.toSet()
            identities.forEach { identity ->
                val oracle = TransactionTemplate(manager).execute { native.resolve(identity) }
                val outcome = outcomes.getValue(request(identity))
                withClue("mix $mix, $identity") {
                    if (oracle == null) {
                        outcome shouldBe Outcome.Missing
                        missing++
                    } else {
                        val body = outcome.shouldBeInstanceOf<Outcome.Resolved<PipelineReleaseNode>>().body
                        body.node shouldBe oracle
                        body.datasources shouldBe expectedDatasources(identity, oracle.status, oracle.entityUuid)
                        compared.merge(oracle.status, 1, Int::plus)
                    }
                }
            }
        }
        withClue("non-vacuity: compared ${compared.toSortedMap()} and $missing absent") {
            listOf(ReleaseNodeStatus.DRAFT, ReleaseNodeStatus.RELEASED, ReleaseNodeStatus.DISCARDED).forEach {
                compared.getOrDefault(it, 0) shouldBeGreaterThanOrEqualTo 1
            }
            missing shouldBeGreaterThanOrEqualTo 1
        }
    }

    @Test
    fun `a request of another family is UNSUPPORTED and a batch with no pipeline request sends no statement`() {
        val foreign = request(seed.identity(ReleaseFamily.TEMPLATE, "query", 1))

        statements.observed.clear()
        val outcomes = inTransaction { descriptor.provider.resolveMany(listOf(foreign), context()) }

        outcomes.values.single() shouldBe Outcome.Unsupported
        statements.observed.shouldBeEmpty()
    }

    // ------------------------------------------------------------------------------------------ C.3 workspace scope

    @Test
    fun `another workspace's version of the same name and number is MISSING - indistinguishable from an absent one`() {
        seed.pipeline(SHARED, 1, templates = listOf("query" to 1))
        val other = otherWorkspace()
        copyInto(other, SHARED, 1, versions = listOf(1, 2))
        copyInto(other, THEIRS, 1, versions = listOf(1), sourceName = SHARED)
        val mine = inTransaction { native.resolve(seed.identity(ReleaseFamily.PIPELINE, SHARED, 1)) }

        val asked =
            listOf(
                seed.identity(ReleaseFamily.PIPELINE, SHARED, 1),
                seed.identity(ReleaseFamily.PIPELINE, SHARED, 2),
                seed.identity(ReleaseFamily.PIPELINE, THEIRS, 1),
                seed.identity(ReleaseFamily.PIPELINE, "acme/nobody", 1),
            )
        val outcomes = inTransaction { descriptor.provider.resolveMany(asked.map(::request), context()) }

        withClue("only the caller's own version resolves, with the caller's entity") {
            outcomes.getValue(request(asked[0])).shouldBeInstanceOf<Outcome.Resolved<PipelineReleaseNode>>().body.node shouldBe mine
        }
        withClue("their v2 of a shared name, their only pipeline, and nobody: one answer") {
            asked.drop(1).map { outcomes.getValue(request(it)) } shouldBe List(3) { Outcome.Missing }
        }
    }

    // ------------------------------------------------------------------------------------------------- C.4 deadline

    @Test
    fun `in flight - a read blocked past its bound is cancelled and refused, no partial map`() {
        seed.pipeline(NAME, 1)
        lock.lock("pipeline_versions")
        val witnessed = AtomicBoolean(false)
        val witness =
            Thread {
                while (!witnessed.get() && !Thread.currentThread().isInterrupted) {
                    witnessed.set(lock.waiting("pipeline_versions"))
                }
            }
        witness.isDaemon = true
        witness.start()

        val started = System.nanoTime()
        val failure =
            shouldThrow<DeadlineExceeded> {
                inTransaction {
                    descriptor.provider.resolveMany(
                        listOf(request(seed.identity(ReleaseFamily.PIPELINE, NAME, 1))),
                        context(Deadline.after(Duration.ofSeconds(1))),
                    )
                }
            }
        val took = Duration.ofNanos(System.nanoTime() - started)
        witness.interrupt()

        withClue("the statement waited on the lock (pg_locks) and PostgreSQL cancelled it (57014) at its 1 s bound") {
            witnessed.get() shouldBe true
            failure.cause.shouldBeInstanceOf<StatementCancelled>()
            took shouldBeLessThan Duration.ofSeconds(4)
        }
    }

    @Test
    fun `success after expiry - rows read after the deadline passed are discarded and the call throws`() {
        seed.pipeline(NAME, 1)
        val deadline = Deadline.after(Duration.ofSeconds(5), clock).withStatementLimit(Duration.ofSeconds(5))
        statements.observed.clear()
        afterStatement = { clock.now = clock.now.plusSeconds(6) }

        shouldThrow<DeadlineExceeded> {
            inTransaction { descriptor.provider.resolveMany(listOf(request(seed.identity(ReleaseFamily.PIPELINE, NAME, 1))), context(deadline)) }
        }
        withClue("the read itself succeeded: exactly one statement ran") { statements.observed shouldHaveSize 1 }
    }

    @Test
    fun `an already expired deadline throws before any statement is sent`() {
        val deadline = Deadline.after(Duration.ofSeconds(1), clock)
        clock.now = clock.now.plusSeconds(2)
        statements.observed.clear()

        shouldThrow<DeadlineExceeded> {
            inTransaction { descriptor.provider.resolveMany(listOf(request(seed.identity(ReleaseFamily.PIPELINE, NAME, 1))), context(deadline)) }
        }
        statements.observed.shouldBeEmpty()
    }

    // -------------------------------------------------------------------------------------------- C.5 closure parity

    @Test
    fun `one release closure planned through the batched provider equals the closure through the native source`() {
        seed.template("query", 1)
        seed.template("format", 2)
        seed.template("rollup", 1)
        seed.pipeline("acme/leaf", 3, templates = listOf("rollup" to 1), source = "warehouse")
        seed.pipeline("acme/mid", 1, templates = listOf("query" to 1, "format" to 2), children = listOf("acme/leaf" to 3))
        seed.pipeline("acme/old", 1, DISCARDED, templates = listOf("query" to 1))
        seed.pipeline(
            "acme/root",
            2,
            DRAFT,
            templates = listOf("format" to 2, "query" to 1, "rollup" to 1),
            children = listOf("acme/mid" to 1, "acme/leaf" to 3, "acme/old" to 1),
        )
        val root = seed.identity(ReleaseFamily.PIPELINE, "acme/root", 2)
        val adapted = ReleaseDependencyFamilies.descriptor(ReleaseFamily.PIPELINE, native, ceiling)

        val batched = plan(registry(descriptor), root)
        val oracle = plan(registry(adapted), root)

        withClue("non-vacuity: the closure holds every pipeline and template, the DISCARDED child included") {
            oracle.nodes.map { it.identity.entityId }.toSet() shouldBe
                setOf("acme/root", "acme/mid", "acme/leaf", "acme/old", "query", "format", "rollup")
            oracle.edges.size shouldBeGreaterThanOrEqualTo 9
        }
        batched shouldBe oracle
        batched.edges.map { it.path } shouldBe oracle.edges.map { it.path }
    }

    // ------------------------------------------------------------------------------------------------------ fixture

    /**
     * Pipelines with one to four versions each, their statuses drawn RELEASED / DISCARDED (at most one DRAFT, the last
     * version — the one-draft index), template and child pins drawn from a shared pool, and the requested identities:
     * every stored version, an absent version of a stored pipeline and an absent pipeline.
     */
    private fun generatedMix(random: Random): List<ReleaseNodeIdentity> {
        val names = (0 until PIPELINES_PER_MIX).map { "acme/gen/p_$it" }
        val asked = ArrayList<ReleaseNodeIdentity>()
        names.forEach { name ->
            val versions = 1 + random.nextInt(MAX_VERSIONS)
            (1..versions).forEach { version ->
                val status =
                    when {
                        version == versions && random.nextBoolean() -> DRAFT
                        random.nextInt(3) == 0 -> DISCARDED
                        else -> RELEASED
                    }
                seed.pipeline(
                    name,
                    version,
                    status,
                    templates = List(random.nextInt(3)) { "tpl_${random.nextInt(4)}" to 1 + random.nextInt(3) },
                    children = List(random.nextInt(3)) { names[random.nextInt(names.size)] to 1 + random.nextInt(MAX_VERSIONS) },
                    source = SOURCES[random.nextInt(SOURCES.size)],
                )
                asked += seed.identity(ReleaseFamily.PIPELINE, name, version)
            }
            asked += seed.identity(ReleaseFamily.PIPELINE, name, versions + 1)
        }
        asked += seed.identity(ReleaseFamily.PIPELINE, "acme/gen/nobody", 1)
        return asked.shuffled(random)
    }

    /** The oracle of a node's datasource references: [DatasourceEdges.ofPipeline] over the stored body, none when DISCARDED. */
    private fun expectedDatasources(
        identity: ReleaseNodeIdentity,
        status: ReleaseNodeStatus,
        pipelineId: UUID,
    ) = if (status == ReleaseNodeStatus.DISCARDED) {
        emptyList()
    } else {
        val stored = checkNotNull(repository.findVersionBody(identity.workspaceId, pipelineId, identity.version))
        DatasourceEdges.ofPipeline(identity.workspaceId, PipelineDeserializer().readOrThrow(stored))
    }

    /** A second workspace owned by the seed's user. */
    private fun otherWorkspace(): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO workspaces (id, name, display_name, created_by) VALUES (:id, :name, 'O', :owner)",
            mapOf("id" to id, "name" to "o_${id.toString().replace("-", "")}", "owner" to seed.userId),
        )
        return id
    }

    /** Pipeline [name] in [workspace], RELEASED at each of [versions], each version the body of [sourceName]@[sourceVersion]. */
    private fun copyInto(
        workspace: UUID,
        name: String,
        sourceVersion: Int,
        versions: List<Int>,
        sourceName: String = name,
    ) {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO pipelines (id, name, display_name, owner_id, current_version, workspace_id) VALUES (:id, :name, 'P', :owner, :current, :ws)",
            mapOf("id" to id, "name" to name, "owner" to seed.userId, "current" to versions.max(), "ws" to workspace),
        )
        versions.forEach { version ->
            jdbc.update(
                "INSERT INTO pipeline_versions (pipeline_id, version, body_json, body_hash, status, created_by, released_by, released_at)" +
                    " SELECT :id, :version, v.body_json, :hash, 'RELEASED', :owner, :owner, NOW()" +
                    " FROM pipeline_versions v JOIN pipelines p ON p.id = v.pipeline_id" +
                    " WHERE p.workspace_id = :mine AND p.name = :source AND v.version = :sourceVersion",
                mapOf(
                    "id" to id,
                    "version" to version,
                    "hash" to "theirs-$name-$version",
                    "owner" to seed.userId,
                    "mine" to seed.workspaceId,
                    "source" to sourceName,
                    "sourceVersion" to sourceVersion,
                ),
            ) shouldNotBe 0
        }
    }

    private fun registry(pipelines: FamilyDescriptor<*>): FamilyRegistry = FamilyRegistry.production(listOf(pipelines, templates))

    private fun plan(
        registry: FamilyRegistry,
        root: ReleaseNodeIdentity,
    ): ReleaseClosure =
        checkNotNull(
            ReleasePlanningWalk(
                registry,
                ReleaseBounds(),
                ReleasePlanningWalk.planningTransaction(manager),
                ReleasePlanningWalk.speculativeReads(manager),
                ReleasePlanningDeadline.after(Duration.ofSeconds(BUDGET_SECONDS)),
            ).plan(root, seed.userId),
        ) { "$root resolves" }

    private fun context(
        deadline: Deadline = Deadline.after(Duration.ofSeconds(BUDGET_SECONDS)).withStatementLimit(Duration.ofSeconds(BUDGET_SECONDS)),
    ) = ResolveContext(seed.workspaceId, MetadataSnapshot.READ_COMMITTED, Purpose.RELEASE_PLANNING, Authority.System, deadline, ReadIsolation.NONE)

    private fun request(identity: ReleaseNodeIdentity): ObjectRequest = ReleaseDependencyFamilies.request(identity)

    private fun <T> inTransaction(read: () -> T): T = checkNotNull(TransactionTemplate(manager).execute { read() })

    private companion object {
        const val NAME = "acme/wide/object"
        const val LEAF = "acme/wide/leaf"
        const val SHARED = "acme/shared"
        const val THEIRS = "acme/theirs"
        const val BATCH = GraphLimits.MAX_RESOLVE_BATCH_SIZE
        const val BUDGET_SECONDS = 30L
        const val MIXES = 6
        const val SEED = 887
        const val PIPELINES_PER_MIX = 12
        const val MAX_VERSIONS = 4
        val SOURCES = listOf("pg", "warehouse", "lake")
    }
}
