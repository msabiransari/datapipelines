package co.datapipelines.executor

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.events.PipelineCompleted
import co.datapipelines.events.PipelineFailed
import co.datapipelines.events.SseEventType
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor

/**
 * #1035 R3 — the execution budget bounds an unanswered commit. A controlled driver commit blocks, ignoring
 * cancellation and interruption, until the fixture releases it. While it blocks: the execution ends
 * OUTCOME_UNKNOWN at the budget (never FAILED, ABORTED or SUCCESS), its execution slot is free for another
 * run, the customer session is aborted, nothing is announced; and when the driver finally answers the late
 * answer changes nothing the execution already recorded (no confirmation stamp, no event, no metric).
 */
class LocalWriteCommitBudgetTest {
    private val release = CountDownLatch(1)
    private val calls = CopyOnWriteArrayList<String>()
    private val meterRegistry = SimpleMeterRegistry()
    private val dispatcher =
        ExecutorDispatcher.forConfig(ExecutorConfig(maxParallelNodes = 4, executionTimeoutSeconds = 60), maxThreads = 4)

    private val ledger =
        object : CommitLedger {
            override fun recordAttempt(executionId: UUID): Boolean = true.also { calls += "ledger.attempt" }

            override fun recordConfirmed(executionId: UUID): Boolean = true.also { calls += "ledger.confirmed" }

            override fun recordResultUnavailable(executionId: UUID): Boolean = true.also { calls += "ledger.result_unavailable" }
        }

    @AfterEach
    fun tearDown() {
        release.countDown()
        dispatcher.close()
    }

    /** A registry whose leased connection's `commit` blocks until [release] (uninterruptibly), then commits for real. */
    private fun blockingRegistry(source: Datasource): DatasourceRegistry {
        val base = FakeDatasourceRegistry(mapOf(source.name to source))
        return object : DatasourceRegistry by base {
            override fun poolFor(datasource: Datasource): ConnectionPool =
                object : ConnectionPool {
                    override val name get() = datasource.name

                    override fun leaseConnection(): Connection {
                        val delegate = DriverManager.getConnection(datasource.jdbcUrl, datasource.username, datasource.secret ?: "")
                        return object : Connection by delegate {
                            override fun commit() {
                                calls += "driver.commit.entered"
                                awaitUninterruptibly()
                                delegate.commit()
                                calls += "driver.commit.returned"
                            }

                            override fun abort(executor: Executor) {
                                calls += "connection.abort"
                            }

                            override fun close() {
                                calls += "connection.close"
                                delegate.close()
                            }
                        }
                    }

                    override fun close() = Unit
                }
        }
    }

    private fun awaitUninterruptibly() {
        var interrupted = false
        while (true) {
            try {
                release.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun source(): Datasource = h2Datasource("commit_budget", listOf("CREATE TABLE tgt (id INT)")).copy(dialect = Dialect.POSTGRES)

    private fun rows(source: Datasource): Long =
        DriverManager.getConnection(source.jdbcUrl, source.username, source.secret ?: "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM tgt").use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }

    private fun awaitCall(call: String) {
        val limit = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < limit && call !in calls) Thread.sleep(POLL_MS)
        withClue("calls=$calls") { calls shouldContain call }
    }

    @Test
    fun `a commit the driver never answers is abandoned at the execution budget as OUTCOME_UNKNOWN`() {
        runBlocking {
            val source = source()
            val metrics = ExecutorMetrics(meterRegistry)
            val transaction =
                LocalWriteTransaction(
                    rootExecutionId = UUID.randomUUID(),
                    workspaceId = UUID.randomUUID(),
                    registration = source.name,
                    registry = blockingRegistry(source),
                    metrics = metrics,
                    dispatcher = dispatcher,
                    deadline = Instant.now().plusMillis(BUDGET_MS),
                    ledger = ledger,
                )
            transaction.withParticipant { it.createStatement().use { s -> s.execute("INSERT INTO tgt VALUES (1)") } }
            val startedAt = System.nanoTime()

            val thrown = shouldThrow<DatapipelinesException> { transaction.commit() }

            val waited = Duration.ofNanos(System.nanoTime() - startedAt)
            // Returned at the budget while the driver is STILL blocked — the wait, not the driver, was bounded.
            waited shouldBeLessThan Duration.ofSeconds(MAX_WAIT_S)
            calls shouldNotContain "driver.commit.returned"
            thrown.code shouldBe PipelineErrorCodes.Execution.LOCAL_WRITE_COMMIT_FAILED
            thrown.details["reason"] shouldBe "commit_unanswered_within_budget"
            transaction.commitAttempted shouldBe true
            transaction.commitConfirmed shouldBe false
            calls shouldContain "connection.abort"
            calls shouldNotContain "ledger.confirmed"

            // The root's terminal handlers run next: rollback is a no-op (nothing may claim it) and close leaves the connection alone.
            transaction.rollbackQuietly()
            transaction.close()
            calls shouldNotContain "connection.close"
            outcomes() shouldBe mapOf("started" to 1.0, "unknown" to 1.0)

            // The driver finally answers: the connection is returned by ITS thread; nothing observable changes.
            release.countDown()
            awaitCall("connection.close")
            transaction.commitConfirmed shouldBe false
            calls shouldNotContain "ledger.confirmed"
            outcomes() shouldBe mapOf("started" to 1.0, "unknown" to 1.0)
        }
    }

    @Test
    fun `a commit answered inside the budget is confirmed as before`() {
        runBlocking {
            val source = source()
            release.countDown()
            val transaction =
                LocalWriteTransaction(
                    rootExecutionId = UUID.randomUUID(),
                    workspaceId = UUID.randomUUID(),
                    registration = source.name,
                    registry = blockingRegistry(source),
                    metrics = ExecutorMetrics(meterRegistry),
                    dispatcher = dispatcher,
                    deadline = Instant.now().plusSeconds(MAX_WAIT_S),
                    ledger = ledger,
                )
            transaction.withParticipant { it.createStatement().use { s -> s.execute("INSERT INTO tgt VALUES (1)") } }

            transaction.commit()

            transaction.commitConfirmed shouldBe true
            calls.toList() shouldBe listOf("ledger.attempt", "driver.commit.entered", "driver.commit.returned", "ledger.confirmed")
            rows(source) shouldBe 1L
        }
    }

    @Test
    fun `the executor ends the run OUTCOME_UNKNOWN at its budget, frees the slot, and ignores the late answer`() {
        runBlocking {
            val source = source()
            val registry = blockingRegistry(source)
            val config =
                ExecutorConfig(
                    maxParallelNodes = 4,
                    executionTimeoutSeconds = 1,
                    maxConcurrentExecutionsPerUser = 1,
                    maxConcurrentExecutionsPerInstance = 1,
                )
            ExecutorHarness(
                templateEngine =
                    Fixtures.templateEngine(
                        mapOf(
                            "write" to "INSERT INTO tgt VALUES (1)",
                            "read" to "SELECT id FROM tgt",
                            "ping" to "SELECT 1 AS one",
                        ),
                    ),
                registry = registry,
                config = config,
                localWritePlanner = LocalWritePlanner(PipelineResolver { _, _, _ -> null }, registry, 8, ledger),
            ).use { harness ->
                val nodes =
                    listOf(
                        Fixtures.node("write", NodeType.DML, source.name),
                        Fixtures.node("read", source = source.name, dependsOn = listOf("write")),
                    )
                val startedAt = System.nanoTime()

                val thrown =
                    shouldThrow<DatapipelinesException> {
                        harness.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                    }

                withClue("calls=$calls") {
                    Duration.ofNanos(System.nanoTime() - startedAt) shouldBeLessThan Duration.ofSeconds(MAX_WAIT_S)
                    thrown.code shouldBe PipelineErrorCodes.Execution.LOCAL_WRITE_COMMIT_FAILED
                    // The driver is still blocked, yet the execution is terminal and its slot is free.
                    calls shouldNotContain "driver.commit.returned"
                    harness.slots.inFlight shouldBe 0
                    val failed = harness.emitter.allOf<PipelineFailed>().single()
                    failed.status shouldBe ExecutionStatus.OUTCOME_UNKNOWN
                    failed.settlesFamily shouldBe true
                    harness.emitter.types() shouldNotContain SseEventType.PIPELINE_COMPLETED
                    harness.emitter.types() shouldNotContain SseEventType.DATA_READY
                }

                // Slot ownership: with one instance slot in total, another execution is admitted and completes now.
                val other = harness.executor.execute(Fixtures.request(Fixtures.pipeline(listOf(Fixtures.node("ping")))))
                other.status shouldBe ExecutionStatus.SUCCESS

                // Late return: the driver commits for real after release, and nothing else changes.
                val eventsBefore = harness.emitter.events.size
                release.countDown()
                awaitCall("driver.commit.returned")
                awaitCall("connection.close")
                rows(source) shouldBe 1L
                calls shouldNotContain "ledger.confirmed"
                harness.emitter.events.size shouldBe eventsBefore
                harness.emitter.allOf<PipelineCompleted>().count { it.executionId == failed(harness) } shouldBe 0
                harness.slots.inFlight shouldBe 0
            }
        }
    }

    private fun failed(harness: ExecutorHarness): UUID =
        harness.emitter
            .allOf<PipelineFailed>()
            .single()
            .executionId

    private fun outcomes(): Map<String, Double> =
        meterRegistry
            .find(ExecutorMetrics.LOCAL_WRITE_TRANSACTIONS)
            .counters()
            .groupBy { it.id.getTag("outcome")!! }
            .mapValues { (_, counters) -> counters.sumOf { it.count() } }

    private companion object {
        const val BUDGET_MS = 400L
        const val MAX_WAIT_S = 20L
        const val WAIT_MS = 15_000L
        const val POLL_MS = 25L
    }
}
