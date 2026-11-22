package co.datapipelines.executor

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceProperties
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.datasources.pooling.ConnectionPoolManager
import co.datapipelines.events.ExecutionStarted
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * R7 — a connection acquisition that returns AFTER the root has settled the run must not
 * resurrect the transaction, record a `started` outcome or escape the lease.
 *
 * The r5 "remaining budget" test held the sole pool connection through the harness's `close()`,
 * which shut down the dispatcher and interrupted the still-pending acquire — hiding the defect
 * rather than proving the cleanup (the r5 landing review). These two cases instead keep the
 * dispatcher ALIVE: the occupied connection is released only after the run has already settled
 * (deadline, or cancellation), so the abandoned node body really does acquire it, and the fix —
 * an enlist decision atomic with [LocalWriteTransaction.close] plus a deadline bound — must return
 * that lease exactly once. A follow-on execution through the same pool is the timely control:
 * it proves the pool still works and the write still commits.
 */
class LocalWriteLateAcquisitionTest {
    /** Deadline: the wall clock fires, the root settles and the late acquire must be returned. */
    @Test
    fun `a lease acquired after terminal timeout is returned and cannot resurrect the transaction`() {
        runBlocking { lateAcquisitionCase(Trigger.DEADLINE, budgetSeconds = 1) }
    }

    /** Cancellation: the same lifecycle through the abort path instead of the deadline. */
    @Test
    fun `a lease acquired after cancellation is returned and cannot resurrect the transaction`() {
        runBlocking { lateAcquisitionCase(Trigger.CANCEL, budgetSeconds = 60) }
    }

    private enum class Trigger {
        DEADLINE,
        CANCEL,
    }

    private suspend fun lateAcquisitionCase(
        trigger: Trigger,
        budgetSeconds: Long,
    ) {
        val source =
            h2Datasource("late_acquire_${trigger.name.lowercase()}", listOf("CREATE TABLE tgt (id INT)"))
                .copy(dialect = Dialect.POSTGRES, secret = "")
        val hikari = mapOf("maximumPoolSize" to 1, "minimumIdle" to 0, "connectionTimeout" to 8000)
        val poolSource = source.copy(dialect = Dialect.H2, properties = DatasourceProperties(hikari = hikari))
        ConnectionPoolManager.buildHikariPool(poolSource).use { pool ->
            val leases = AtomicInteger()
            val closes = AtomicInteger()
            val countingPool = countingPool(pool, leases, closes)
            val base = FakeDatasourceRegistry(mapOf(source.name to source))
            val registry =
                object : DatasourceRegistry by base {
                    override fun poolFor(datasource: Datasource): ConnectionPool = countingPool
                }
            val meters = SimpleMeterRegistry()
            val held = pool.leaseConnection()
            try {
                ExecutorHarness(
                    templateEngine = Fixtures.templateEngine(mapOf("write" to "INSERT INTO tgt VALUES (1)")),
                    registry = registry,
                    config = ExecutorConfig(maxParallelNodes = 4, executionTimeoutSeconds = budgetSeconds, cancelGraceSeconds = 1),
                    metrics = ExecutorMetrics(meters),
                    localWritePlanner = LocalWritePlanner(PipelineResolver { _, _, _ -> null }, registry, 8),
                ).use { harness ->
                    val nodes = listOf(Fixtures.node("write", NodeType.DML, source.name))
                    driveTerminal(harness, nodes, trigger, leases)
                    held.close()
                    awaitLeaseReturn(closes)
                    assertNoResurrection(leases, closes, pool, meters, source)
                    // The timely control: the pool is usable again, and a fresh execution commits.
                    harness.executor
                        .execute(Fixtures.request(Fixtures.pipeline(nodes)))
                        .status shouldBe ExecutionStatus.SUCCESS
                }
                count(source) shouldBe 1L
                outcomes(meters) shouldBe mapOf("started" to 1.0, "committed" to 1.0)
            } finally {
                runCatching { held.close() }
            }
        }
    }

    /** Runs the execution to its terminal settlement — deadline or cancellation. */
    private suspend fun driveTerminal(
        harness: ExecutorHarness,
        nodes: List<Node>,
        trigger: Trigger,
        leases: AtomicInteger,
    ) {
        when (trigger) {
            Trigger.DEADLINE -> {
                shouldThrow<PipelineTimeoutException> {
                    harness.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                }
            }

            Trigger.CANCEL -> {
                coroutineScope {
                    val run =
                        async {
                            shouldThrow<ExecutionAbortedException> {
                                harness.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                            }
                        }
                    // Cancel only once the body has ENTERED the pool acquire (leases=1) — not
                    // merely once the execution started — so the settlement really races a live,
                    // blocked acquisition.
                    while (harness.emitter.allOf<ExecutionStarted>().isEmpty() || leases.get() == 0) delay(POLL_MS)
                    val executionId = harness.emitter.firstOf<ExecutionStarted>().executionId
                    harness.cancellations.cancel(executionId, AbortReason.CANCELLED)
                    run.await()
                }
            }
        }
    }

    /** Waits for the detached body's late acquire to be returned (bounded — a leak stays red). */
    private suspend fun awaitLeaseReturn(closes: AtomicInteger) {
        val deadline = System.currentTimeMillis() + LATE_RESOLUTION_MS
        while (System.currentTimeMillis() < deadline && closes.get() == 0) delay(POLL_MS)
    }

    private fun assertNoResurrection(
        leases: AtomicInteger,
        closes: AtomicInteger,
        pool: ConnectionPool,
        meters: SimpleMeterRegistry,
        source: Datasource,
    ) {
        // R7: the late acquisition returned its lease exactly once — no retained lease, no
        // post-terminal `started` outcome, no business write.
        leases.get() shouldBe 1
        closes.get() shouldBe 1
        pool.activeConnections shouldBe 0
        outcomes(meters) shouldBe emptyMap()
        count(source) shouldBe 0L
    }

    /** Counts only the executor's acquisition: the held lease is taken from the raw pool. */
    private fun countingPool(
        pool: ConnectionPool,
        leases: AtomicInteger,
        closes: AtomicInteger,
    ): ConnectionPool =
        object : ConnectionPool by pool {
            override fun leaseConnection(): Connection {
                leases.incrementAndGet()
                val conn = pool.leaseConnection()
                return object : Connection by conn {
                    override fun close() {
                        closes.incrementAndGet()
                        conn.close()
                    }
                }
            }
        }

    private fun count(source: Datasource): Long =
        DriverManager.getConnection(source.jdbcUrl, source.username, source.secret ?: "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM tgt").use { rows ->
                    rows.next()
                    rows.getLong(1)
                }
            }
        }

    private fun outcomes(meters: SimpleMeterRegistry): Map<String?, Double> =
        meters
            .find(ExecutorMetrics.LOCAL_WRITE_TRANSACTIONS)
            .counters()
            .associate { it.id.getTag("outcome") to it.count() }

    private companion object {
        const val POLL_MS = 25L
        const val LATE_RESOLUTION_MS = 10_000L
    }
}
