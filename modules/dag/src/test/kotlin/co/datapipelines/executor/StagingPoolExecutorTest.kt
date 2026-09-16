package co.datapipelines.executor

import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.staging.H2StagingFactory
import co.datapipelines.staging.H2StagingProperties
import co.datapipelines.staging.Staging
import co.datapipelines.staging.StagingEngine
import co.datapipelines.staging.StagingFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The pool through the REAL executor (dag-executor.md §6.4, §9; #118): independent tempdb
 * nodes inside H2 at the same instant on distinct leases, capacity one still completing under
 * a dispatcher smaller than the ready set, dependent visibility across connections with values
 * reconciled at the caller, and the executor's own abandoned-body path meeting a lease that
 * never returns in time.
 *
 * The overlap proof is a barrier INSIDE the lease: a [Staging] decorator wraps every author
 * block so the two nodes rendezvous while each holds a real connection. At capacity four both
 * arrive; at capacity one the second cannot lease until the first returns, the barrier times
 * out, and the node fails — the control that proves the guard can fail.
 */
class StagingPoolExecutorTest {
    @Test
    fun `two independent tempdb nodes hold leases inside H2 at the same instant at capacity four`() =
        runBlocking<Unit> {
            val rendezvous = Rendezvous(parties = 2)
            harness(TWO_INDEPENDENT_SQL, stagingFactory = rendezvous.factory(H2StagingProperties(maxConnections = 4))).use { h ->
                val result = h.executor.execute(Fixtures.request(Fixtures.pipeline(twoIndependentNodes())))

                result.status shouldBe ExecutionStatus.SUCCESS
                rendezvous.metAt.get() shouldBe 2
                result.nodeStats.map { it.status }.toSet() shouldBe setOf(NodeStatus.SUCCESS)
            }
        }

    @Test
    fun `the same two nodes at capacity one never meet — the control that makes the guard honest`() =
        runBlocking<Unit> {
            val rendezvous = Rendezvous(parties = 2)
            harness(TWO_INDEPENDENT_SQL, stagingFactory = rendezvous.factory(H2StagingProperties(maxConnections = 1))).use { h ->
                val failed =
                    shouldThrow<PipelineExecutionFailed> { h.executor.execute(Fixtures.request(Fixtures.pipeline(twoIndependentNodes()))) }

                // The first node held its lease waiting for a partner that could not get one;
                // its wait timed out and surfaced as that node's failure (an unmapped exception in
                // the EXECUTE phase is the catalog's executor-internal row).
                failed.errorCode shouldBe PipelineErrorCodes.Execution.ABORTED
                rendezvous.metAt.get() shouldBe 0
                rendezvous.timedOut.get() shouldBe 1
            }
        }

    @Test
    fun `capacity one completes six independent tempdb nodes under a bounded dispatcher`() =
        runBlocking<Unit> {
            val nodes = (1..6).map { Fixtures.node("n$it", type = NodeType.DDL) }
            val sql = (1..6).associate { "n$it" to """CREATE TABLE "t$it" AS SELECT "X" AS n FROM SYSTEM_RANGE(1, 2000)""" }
            harness(sql, stagingFactory = H2StagingFactory(H2StagingProperties(maxConnections = 1))).use { h ->
                val result = h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                result.status shouldBe ExecutionStatus.SUCCESS
                result.nodeStats.size shouldBe 6
            }
        }

    @Test
    fun `a producer's committed table is read by dependents on other connections with values intact`() =
        runBlocking<Unit> {
            val source =
                h2Datasource(
                    "vis_src",
                    listOf(
                        "CREATE TABLE src (n INT, amount DECIMAL(10,2), label VARCHAR(10))",
                        "INSERT INTO src VALUES (1, 10.50, 'a'), (2, NULL, 'b'), (3, 0.25, NULL)",
                    ),
                )
            val nodes =
                listOf(
                    Fixtures.node("fetch", source = "vis_src", output = NodeOutput.Tempdb("stg")),
                    Fixtures.node("left", output = NodeOutput.Tempdb("l"), dependsOn = listOf("fetch")),
                    Fixtures.node("right", output = NodeOutput.Tempdb("r"), dependsOn = listOf("fetch")),
                    Fixtures.node("report", output = NodeOutput.Caller, dependsOn = listOf("left", "right")),
                )
            val sql =
                mapOf(
                    "fetch" to "SELECT n, amount, label FROM src",
                    "left" to """SELECT "n", "amount" FROM "stg" WHERE "n" < 3""",
                    "right" to """SELECT "n", "label" FROM "stg" WHERE "n" > 1""",
                    "report" to
                        """SELECT l."n" AS n, l."amount" AS amount, r."label" AS label FROM "l" l JOIN "r" r ON l."n" = r."n" ORDER BY n""",
                )
            val store = InMemoryResultStore()
            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(sql),
                registry = FakeDatasourceRegistry(mapOf("vis_src" to source)),
                config = ExecutorConfig(executionTimeoutSeconds = TEST_TIMEOUT_SECONDS),
                resultStore = store,
                stagingFactory = H2StagingFactory(H2StagingProperties(maxConnections = 4)),
            ).use { h ->
                val result = h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                result.status shouldBe ExecutionStatus.SUCCESS
                val view = requireNotNull(store.describe(store.keyFor(result.executionId), null)) { "no stored result" }
                view.totalRows shouldBe 1L
                // n = 2 is the only row in both branches; its DECIMAL null and its label ride through.
                view.firstPage.single().map { it?.toString() } shouldContainExactly listOf("2", null, "b")
            }
        }

    /**
     * The executor's detached/abandoned-body path meets the pool (108 §A + #118 §6): a lease
     * whose JDBC "call" never returns inside the node deadline + grace is abandoned by the
     * executor, the execution fails on schedule, and `cleanup` closes staging WITHOUT waiting
     * for that lease. When the call finally returns, the lease closes its own connection and
     * the database — kept alive only by that connection — is gone.
     */
    @Test
    fun `an abandoned lease outlives close without blocking it, and its late return destroys the database`() =
        runBlocking<Unit> {
            val park = CountDownLatch(1)
            val parked = CountDownLatch(1)
            val executionIds = mutableListOf<UUID>()
            val real = H2StagingFactory(H2StagingProperties(maxConnections = 2))
            val factory =
                object : StagingFactory {
                    override fun create(
                        executionId: UUID,
                        engine: StagingEngine,
                    ): Staging {
                        executionIds += executionId
                        // The author block runs, then the "driver" parks: a blocking wait that ignores
                        // cancellation, exactly what a driver that drops cancel() looks like from a lease.
                        return ParkingStaging(real.create(executionId, engine), parked, park)
                    }
                }
            val config =
                ExecutorConfig(
                    nodeQueryTimeoutSeconds = NO_RESCUE_SECONDS.toInt(),
                    nodeTimeoutSeconds = DEADLINE_SECONDS,
                    cancelGraceSeconds = GRACE_SECONDS,
                    executionTimeoutSeconds = NO_RESCUE_SECONDS,
                    cancelPollIntervalSeconds = NO_RESCUE_SECONDS,
                )
            harness(mapOf("hang" to """CREATE TABLE "hung" (n INT)"""), stagingFactory = factory, config = config).use { h ->
                val nodes = listOf(Fixtures.node("hang", type = NodeType.DDL))
                val elapsed =
                    kotlin.system.measureTimeMillis {
                        shouldThrow<PipelineExecutionFailed> { h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes))) }
                            .errorCode shouldBe PipelineErrorCodes.Node.TIMEOUT
                    }
                // Failed on schedule — cleanup did not wait for the parked lease.
                (elapsed < (DEADLINE_SECONDS + GRACE_SECONDS) * MILLIS + SLACK_MS).shouldBeTrue()
                parked.await(DEADLINE_SECONDS, TimeUnit.SECONDS).shouldBeTrue()
                h.cancellations.liveExecutions shouldBe 0

                // The database is still alive: the quarantined lease holds its connection.
                val url = "jdbc:h2:mem:exec_${executionIds.single()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
                DriverManager.getConnection(url, "sa", "").use { peer -> execUsers(peer) shouldBe 1L }

                park.countDown()
                // Give the late return a moment to close its connection, then the database is gone.
                awaitDatabaseGone(url)
            }
        }

    // ---------- fixtures ----------

    private fun twoIndependentNodes() = listOf(Fixtures.node("a", type = NodeType.DDL), Fixtures.node("b", type = NodeType.DDL))

    private fun harness(
        sqlByTemplateId: Map<String, String>,
        stagingFactory: StagingFactory,
        config: ExecutorConfig = ExecutorConfig(executionTimeoutSeconds = TEST_TIMEOUT_SECONDS),
    ) = ExecutorHarness(templateEngine = Fixtures.templateEngine(sqlByTemplateId), config = config, stagingFactory = stagingFactory)

    private fun execUsers(connection: Connection): Long =
        connection.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.USERS WHERE UPPER(USER_NAME) = 'STAGING_EXEC'").use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    private fun awaitDatabaseGone(url: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS * 2)
        while (true) {
            val users = DriverManager.getConnection(url, "sa", "").use { execUsers(it) }
            if (users == 0L) return
            check(System.nanoTime() < deadline) { "the abandoned lease's connection never closed" }
            Thread.sleep(POLL_MS)
        }
    }

    /**
     * Wraps every author block so the nodes rendezvous INSIDE their leases. [metAt] counts
     * parties that passed the barrier; [timedOut] counts the ones that gave up waiting.
     */
    private class Rendezvous(
        parties: Int,
    ) {
        private val barrier = CyclicBarrier(parties)
        val metAt = AtomicInteger()
        val timedOut = AtomicInteger()

        fun factory(props: H2StagingProperties): StagingFactory {
            val real = H2StagingFactory(props)
            return object : StagingFactory {
                override fun create(
                    executionId: UUID,
                    engine: StagingEngine,
                ): Staging = BarrierStaging(real.create(executionId, engine))
            }
        }

        private inner class BarrierStaging(
            private val inner: Staging,
        ) : Staging by inner {
            override suspend fun <T> withConnection(block: suspend (Connection) -> T): T =
                inner.withConnection { connection ->
                    try {
                        barrier.await(BARRIER_S, TimeUnit.SECONDS)
                        metAt.incrementAndGet()
                    } catch (e: TimeoutException) {
                        timedOut.incrementAndGet()
                        throw e
                    }
                    block(connection)
                }
        }
    }

    /** Runs the author block, then blocks the lease holder's thread until [park] is released. */
    private class ParkingStaging(
        private val inner: Staging,
        private val parked: CountDownLatch,
        private val park: CountDownLatch,
    ) : Staging by inner {
        override suspend fun <T> withConnection(block: suspend (Connection) -> T): T =
            inner.withConnection { connection ->
                val value = block(connection)
                parked.countDown()
                park.await()
                value
            }
    }

    private companion object {
        const val TEST_TIMEOUT_SECONDS = 60L
        const val BARRIER_S = 5L
        const val DEADLINE_SECONDS = 1L
        const val GRACE_SECONDS = 1L
        const val MILLIS = 1_000L
        const val SLACK_MS = 4_000L
        const val NO_RESCUE_SECONDS = 300L
        const val POLL_MS = 50L
        val TWO_INDEPENDENT_SQL =
            mapOf(
                "a" to """CREATE TABLE "a" AS SELECT "X" AS n FROM SYSTEM_RANGE(1, 100)""",
                "b" to """CREATE TABLE "b" AS SELECT "X" AS n FROM SYSTEM_RANGE(1, 100)""",
            )
    }
}
