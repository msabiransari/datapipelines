package co.datapipelines.application.dependencies.schedule

import co.datapipelines.application.SharedPostgres
import co.datapipelines.application.dependencies.HandClock
import co.datapipelines.application.dependencies.HeldTableLock
import co.datapipelines.application.dependencies.NonArtifactSeed
import co.datapipelines.application.dependencies.ObservedStatements
import co.datapipelines.application.dependencies.PostgresStatementCeiling
import co.datapipelines.application.dependencies.ReleaseArtifactSeed
import co.datapipelines.application.dependencies.ReleaseDependencyFamilies
import co.datapipelines.application.dependencies.ReleaseGraphLimits
import co.datapipelines.application.dependencies.StatementCancelled
import co.datapipelines.dependencies.Deadline
import co.datapipelines.dependencies.DeadlineExceeded
import co.datapipelines.dependencies.DependencyWalker
import co.datapipelines.dependencies.DiagnosticKind
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.FamilyRegistry
import co.datapipelines.dependencies.GraphLimits
import co.datapipelines.dependencies.GraphSnapshot
import co.datapipelines.dependencies.LookupScope
import co.datapipelines.dependencies.NodeFacts
import co.datapipelines.dependencies.ObjectAddress
import co.datapipelines.dependencies.ObjectRequest
import co.datapipelines.dependencies.Operational
import co.datapipelines.dependencies.Outcome
import co.datapipelines.dependencies.OwnerScope
import co.datapipelines.dependencies.Reference
import co.datapipelines.dependencies.Relationship
import co.datapipelines.dependencies.Resolution
import co.datapipelines.dependencies.ResolvedIdentity
import co.datapipelines.dependencies.ScheduleCondition
import co.datapipelines.dependencies.Selector
import co.datapipelines.dependencies.VersionInfo
import co.datapipelines.dependencies.conformance.ProviderConformance
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.release.PipelineDependencySource
import co.datapipelines.pipeline.release.ReleaseBounds
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.scheduler.Schedule
import co.datapipelines.scheduler.ScheduleRepository
import co.datapipelines.scheduler.SchedulerAutoConfiguration
import com.fasterxml.jackson.databind.JsonNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
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

/**
 * #874 G3 §C.0, C.3 on a real PostgreSQL, over the descriptor exactly as `DependencyGraphNonArtifactConfiguration` builds
 * it. Statement counter: [ObservedStatements]. The extractor here is a stand-in with the pipeline executor's id that
 * reads `{"pipeline", "version"}` (the real grammar's cases are web's `PipelineJobExecutorTest`): the provider's job is
 * one statement, one outcome per request and EXACTLY one `EXECUTOR_TARGET` edge per schedule.
 */
class ScheduleDependencyProviderPostgresTest {
    private val clock = HandClock()
    private var afterStatement: () -> Unit = {}
    private val statements = ObservedStatements(SharedPostgres.dataSource()) { afterStatement() }
    private val jdbc = NamedParameterJdbcTemplate(SharedPostgres.dataSource())
    private val artifacts = ReleaseArtifactSeed(jdbc)
    private val seed = NonArtifactSeed(jdbc)
    private val manager = DataSourceTransactionManager(statements.dataSource)
    private val ceiling = PostgresStatementCeiling(statements.dataSource)
    private val descriptor =
        ScheduleDependencyFamily.descriptor(ScheduleRepository(ceiling.jdbc, SchedulerAutoConfiguration.JSON), listOf(StandIn), ceiling)
    private val lock = HeldTableLock(SharedPostgres.dataSource())

    private lateinit var enabled: Schedule
    private lateinit var paused: Schedule
    private lateinit var blocked: Schedule
    private lateinit var deleted: Schedule
    private lateinit var elsewhere: Schedule

    @BeforeEach
    fun reset() {
        artifacts.reset()
        seed.adopt(artifacts.userId, artifacts.workspaceId)
        enabled = seed.schedule("ops/enabled")
        paused = seed.schedule("ops/paused").also(seed::pause)
        blocked = seed.schedule("ops/blocked").also(seed::block)
        deleted = seed.schedule("ops/deleted").also(seed::softDelete)
        elsewhere = seed.schedule("ops/elsewhere", workspace = seed.otherWorkspaceId)
        statements.observed.clear()
    }

    @AfterEach
    fun release() {
        lock.close()
    }

    @Test
    fun `the schedule provider conforms - the executor target is its one reference-bearing field`() {
        val violations =
            inTransaction {
                ProviderConformance.check(
                    ProviderConformance.Fixture(
                        descriptor = descriptor,
                        context = seed.context(),
                        present = listOf(request(enabled), request(paused), request(blocked)),
                        absent = listOf(request(deleted), request(elsewhere), request(UUID.randomUUID().toString()), request("not-a-uuid")),
                        references =
                            ProviderConformance.References.Fields(
                                listOf(enabled, paused, blocked).associate { request(it) to setOf(StandIn.POINTER) },
                            ),
                    ),
                )
            }

        violations.shouldBeEmpty()
    }

    @Test
    fun `a mixed batch answers each request once in ONE statement - deleted, foreign, absent and malformed ids MISSING`() {
        val upper = request(enabled.id.toString().uppercase())
        val versioned = ObjectRequest(address(enabled.id.toString()), Selector.Current)
        val batch = listOf(request(enabled), request(paused), request(blocked), request(deleted), request(elsewhere), upper, versioned)

        val outcomes = inTransaction { descriptor.provider.resolveMany(batch, seed.context()) }

        withClue(statements.observed.map { it.first }) { statements.observed shouldHaveSize 1 }
        outcomes.keys shouldBe batch.toSet()
        listOf(request(deleted), request(elsewhere), upper).forEach { outcomes[it] shouldBe Outcome.Missing }
        outcomes[versioned] shouldBe Outcome.Unsupported
        val node = outcomes[request(enabled)].shouldBeInstanceOf<Outcome.Resolved<ScheduleGraphBody>>()
        node.identity shouldBe ResolvedIdentity(FamilyKey.SCHEDULE, OwnerScope.Workspace(seed.workspaceId), enabled.id.toString(), null)
        node.facts shouldBe
            NodeFacts(
                name = "ops/enabled",
                versionInfo = VersionInfo.Revision(enabled.revision),
                lifecycle = null,
                operational = Operational.Schedule(ScheduleCondition.ENABLED),
            )
        condition(outcomes, paused) shouldBe ScheduleCondition.PAUSED
        condition(outcomes, blocked) shouldBe ScheduleCondition.BLOCKED
        node.body.toString().let { text ->
            text shouldNotContain "ops@example.com"
            text shouldNotContain "acme/rides/daily"
        }
    }

    @Test
    fun `outgoing is exactly one EXECUTOR_TARGET reference - the pipeline at its pinned version`() {
        outgoing(enabled) shouldBe
            listOf(
                Reference(
                    request = ObjectRequest(pipeline("acme/rides/daily"), Selector.ExactVersion(3)),
                    relationship = Relationship.EXECUTOR_TARGET,
                    sourcePath = StandIn.POINTER,
                    spelledName = "acme/rides/daily",
                ),
            )
    }

    @Test
    fun `an unknown executor and an unreadable payload each yield one edge to the unreadable target - never no edge`() {
        val unknown = seed.schedule("ops/unknown", executorId = "lake_settlement")
        val broken = seed.schedule("ops/broken", payload = SchedulerAutoConfiguration.JSON.createObjectNode().put("pipeline", "x"))

        outgoing(unknown) shouldBe listOf(unreadable(spelledName = null, sourcePath = ScheduleDependencyFamily.WHOLE_PAYLOAD))
        outgoing(broken) shouldBe listOf(unreadable(spelledName = "x", sourcePath = StandIn.POINTER))
    }

    @Test
    fun `through the walk a broken payload's target is a MISSING node with the MISSING diagnostic - a pinned one resolves`() {
        artifacts.pipeline("acme/rides/daily", 3)
        val broken = seed.schedule("ops/broken", payload = SchedulerAutoConfiguration.JSON.createObjectNode().put("pipeline", "x"))

        val good = walk(enabled)
        val bad = walk(broken)

        good.edges.single().relationship shouldBe Relationship.EXECUTOR_TARGET
        good.node(good.edges.single().toNodeId).resolution shouldBe Resolution.RESOLVED
        good.diagnostics.shouldBeEmpty()
        val target = bad.node(bad.edges.single().toNodeId)
        target.resolution shouldBe Resolution.MISSING
        target.name shouldBe "x"
        bad.diagnostics.single().let {
            it.kind shouldBe DiagnosticKind.MISSING
            it.nodeId shouldBe target.nodeId
        }
    }

    @Test
    fun `a batch above the walk's maximum is refused on entry - never read per key`() {
        val batch = (0..GraphLimits.MAX_RESOLVE_BATCH_SIZE).map { request(UUID.randomUUID().toString()) }

        shouldThrow<IllegalArgumentException> { inTransaction { descriptor.provider.resolveMany(batch, seed.context()) } }
        statements.observed.shouldBeEmpty()
    }

    @Test
    fun `an already expired deadline throws before any statement is sent`() {
        val deadline = Deadline.after(Duration.ofSeconds(1), clock)
        clock.now = clock.now.plusSeconds(2)

        shouldThrow<DeadlineExceeded> {
            inTransaction { descriptor.provider.resolveMany(listOf(request(enabled)), seed.context(deadline = deadline)) }
        }
        statements.observed.shouldBeEmpty()
    }

    @Test
    fun `in flight - a read blocked on another session's lock is cancelled by PostgreSQL at the one second left`() {
        lock.lock("schedules")
        val witnessed = AtomicBoolean(false)
        val witness = Thread { while (!witnessed.get() && !Thread.currentThread().isInterrupted) witnessed.set(lock.waiting("schedules")) }
        witness.isDaemon = true
        witness.start()

        val started = System.nanoTime()
        val failure =
            shouldThrow<DeadlineExceeded> {
                inTransaction {
                    descriptor.provider.resolveMany(
                        listOf(request(enabled)),
                        seed.context(deadline = Deadline.after(Duration.ofSeconds(1))),
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
        val deadline = Deadline.after(Duration.ofSeconds(5), clock).withStatementLimit(Duration.ofSeconds(5))
        afterStatement = { clock.now = clock.now.plusSeconds(6) }

        shouldThrow<DeadlineExceeded> {
            inTransaction { descriptor.provider.resolveMany(listOf(request(enabled)), seed.context(deadline = deadline)) }
        }
        withClue("the read itself succeeded: exactly one statement ran") { statements.observed shouldHaveSize 1 }
    }

    private fun condition(
        outcomes: Map<ObjectRequest, Outcome<ScheduleGraphBody>>,
        schedule: Schedule,
    ): ScheduleCondition? = ((outcomes[request(schedule)] as? Outcome.Resolved)?.facts?.operational as? Operational.Schedule)?.condition

    private fun outgoing(schedule: Schedule): List<Reference> {
        val resolved = inTransaction { descriptor.provider.resolveMany(listOf(request(schedule)), seed.context()) }.values.single()
        return descriptor.provider.outgoing(resolved.shouldBeInstanceOf<Outcome.Resolved<ScheduleGraphBody>>())
    }

    private fun walk(schedule: Schedule): GraphSnapshot {
        val pipelines =
            ReleaseDependencyFamilies.descriptor(
                ReleaseFamily.PIPELINE,
                PipelineDependencySource(PipelineRepository(ceiling.jdbc)),
                ceiling,
            )
        val registry = FamilyRegistry.production(listOf(descriptor, pipelines))
        return inTransaction {
            DependencyWalker(registry, ReleaseGraphLimits.of(ReleaseBounds())).walk(listOf(request(schedule)), seed.context())
        }
    }

    private fun unreadable(
        spelledName: String?,
        sourcePath: String,
    ) = Reference(
        request =
            ObjectRequest(
                pipeline(ScheduleDependencyFamily.UNREADABLE_TARGET_KEY),
                Selector.ExactVersion(ScheduleDependencyFamily.UNREADABLE_TARGET_VERSION),
            ),
        relationship = Relationship.EXECUTOR_TARGET,
        sourcePath = sourcePath,
        spelledName = spelledName,
    )

    private fun pipeline(name: String) = ObjectAddress(FamilyKey.PIPELINE, LookupScope(seed.workspaceId), name)

    private fun address(key: String) = ObjectAddress(FamilyKey.SCHEDULE, LookupScope(seed.workspaceId), key)

    private fun request(key: String) = ObjectRequest(address(key), Selector.Entity)

    private fun request(schedule: Schedule) = request(schedule.id.toString())

    private fun <T> inTransaction(read: () -> T): T = checkNotNull(TransactionTemplate(manager).execute { read() })

    /** The pipeline executor's id with a minimal reading of `{"pipeline", "version"}`; anything else is unreadable. */
    private object StandIn : ExecutorTargetExtractor {
        const val POINTER = "/pipeline"

        override val executorId: String = "pipeline"

        override fun targetOf(
            payloadSchemaVersion: Int,
            payload: JsonNode,
        ): ExecutorTarget {
            val name = payload.path("pipeline").takeIf { it.isTextual }?.asText()
            val version = payload.path("version").takeIf { it.isInt && it.asInt() >= 1 }?.asInt()
            return if (name != null && version != null) {
                ExecutorTarget.Pipeline(name, Selector.ExactVersion(version), POINTER)
            } else {
                ExecutorTarget.Unreadable(name, version, POINTER)
            }
        }
    }
}
