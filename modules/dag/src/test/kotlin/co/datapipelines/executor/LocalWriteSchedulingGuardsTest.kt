package co.datapipelines.executor

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceProperties
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.datasources.pooling.ConnectionPoolManager
import co.datapipelines.events.NodeCompleted
import co.datapipelines.events.NodeStarted
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The r5 scheduling guards the review asked for, measured at REAL boundaries rather than assumed:
 *
 * - **Parallel DAG siblings** (not four directly opened spans): a DAG whose two write siblings are
 *   dispatched concurrently by the scheduler, with the FAMILY exclusion measured on the JDBC
 *   statements themselves (concurrency high-water == 1) and the dependency order measured on the
 *   statement sequence — while the event stream proves the sibling NODES really were in flight at
 *   the same time, so the serialized JDBC is the transaction's doing, not the scheduler's.
 * - **Remaining-budget acquisition**: a pool lease that cannot be satisfied still cannot outlive
 *   the execution's own wall-clock deadline — the run fails on `execution.timeout`, not on the
 *   pool's (much longer) wait, and nothing was enlisted or written.
 */
class LocalWriteSchedulingGuardsTest {
    /**
     * Two concurrent write siblings plus a dependent, all on one family registration.
     *
     * The sibling statements each HOLD the participant connection for 300ms, so the second
     * sibling's node is guaranteed to be in flight (its `node_started` emitted) while the first
     * sibling still owns the statement — concurrent NODES, serialized JDBC, both measured.
     */
    @Test
    fun `parallel dag siblings serialize on the family transaction while overlapping in flight`() {
        runBlocking {
            val source =
                h2Datasource("parallel_family", listOf("CREATE TABLE tgt (id INT)"))
                    .copy(dialect = Dialect.POSTGRES)
            val base = FakeDatasourceRegistry(mapOf(source.name to source))
            val active = AtomicInteger()
            val highWater = AtomicInteger()
            val statementOrder = CopyOnWriteArrayList<String>()
            val registry =
                object : DatasourceRegistry by base {
                    override fun poolFor(datasource: Datasource): ConnectionPool {
                        val pool = base.poolFor(datasource)
                        return object : ConnectionPool by pool {
                            override fun leaseConnection(): Connection {
                                val connection = pool.leaseConnection()
                                return object : Connection by connection {
                                    override fun prepareStatement(sql: String): PreparedStatement {
                                        val statement = connection.prepareStatement(sql)
                                        return object : PreparedStatement by statement {
                                            override fun executeUpdate(): Int {
                                                statementOrder += sql
                                                val nowActive = active.incrementAndGet()
                                                highWater.updateAndGet { current -> maxOf(current, nowActive) }
                                                try {
                                                    // Hold each sibling's statement so the other sibling's
                                                    // NODE provably overlaps in flight.
                                                    if (sql.contains("VALUES (1)") || sql.contains("VALUES (2)")) {
                                                        Thread.sleep(SIBLING_HOLD_MS)
                                                    }
                                                    return statement.executeUpdate()
                                                } finally {
                                                    active.decrementAndGet()
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            val meters = SimpleMeterRegistry()
            ExecutorHarness(
                templateEngine =
                    Fixtures.templateEngine(
                        mapOf(
                            "w1" to "INSERT INTO tgt VALUES (1)",
                            "w2" to "INSERT INTO tgt VALUES (2)",
                            "w3" to "INSERT INTO tgt VALUES (3)",
                        ),
                    ),
                registry = registry,
                metrics = ExecutorMetrics(meters),
                localWritePlanner = LocalWritePlanner(PipelineResolver { _, _, _ -> null }, registry, 8),
            ).use { harness ->
                val nodes =
                    listOf(
                        Fixtures.node("w1", type = NodeType.DML, source = source.name),
                        Fixtures.node("w2", type = NodeType.DML, source = source.name),
                        Fixtures.node("w3", type = NodeType.DML, source = source.name, dependsOn = listOf("w1", "w2")),
                    )
                harness.executor.execute(Fixtures.request(Fixtures.pipeline(nodes))).status shouldBe ExecutionStatus.SUCCESS

                // The sibling NODES overlapped in flight: both `node_started` precede either
                // sibling's `node_completed` — compared on the emitter's ONE ordered stream, so
                // the indexes are positions in the same sequence, not offsets of two lists.
                val events = harness.emitter.events

                fun globalIndexOf(match: (co.datapipelines.events.ExecutionEvent) -> Boolean): Int =
                    events.indexOfFirst(match).also { if (it < 0) error("event not found in stream") }
                val w1Start = globalIndexOf { it is NodeStarted && it.nodeId == "w1" }
                val w2Start = globalIndexOf { it is NodeStarted && it.nodeId == "w2" }
                val w1Completion = globalIndexOf { it is NodeCompleted && it.nodeId == "w1" }
                val w2Completion = globalIndexOf { it is NodeCompleted && it.nodeId == "w2" }
                (maxOf(w1Start, w2Start) < minOf(w1Completion, w2Completion)) shouldBe true

                // The family transaction serialized the JDBC: never two statements at once.
                highWater.get() shouldBe 1

                // Dependency order at the JDBC boundary: the dependent ran after BOTH siblings.
                val statements = statementOrder.toList()
                val w3At = statements.indexOfFirst { it.contains("VALUES (3)") }
                (w3At > statements.indexOfFirst { it.contains("VALUES (1)") }) shouldBe true
                (w3At > statements.indexOfFirst { it.contains("VALUES (2)") }) shouldBe true
            }
            count(source, "tgt") shouldBe 3L
            outcomes(meters) shouldBe mapOf("started" to 1.0, "committed" to 1.0)
        }
    }

    /**
     * The family lease waits inside the execution's own deadline, not the pool's.
     *
     * A one-connection pool whose wait (8s) far exceeds the execution deadline (2s), with the sole
     * connection held externally: the run must fail on `pipeline.execution.timeout` after ~2s —
     * never having enlisted (no `started` outcome), never having written — rather than blocking
     * until the pool gives up.
     */
    @Test
    fun `an unsatisfiable lease is bounded by the execution deadline not the pool wait`() {
        runBlocking {
            val source =
                h2Datasource("deadline_acquisition", listOf("CREATE TABLE tgt (id INT)"))
                    .copy(
                        dialect = Dialect.POSTGRES,
                        // buildHikariPool needs a plaintext credential; H2's is the empty password.
                        secret = "",
                    )
            // The pool is built from the H2-dialect copy (the driver must match the URL); the
            // registry keeps the POSTGRES-dialect registration the planner admits.
            val hikari =
                mapOf(
                    "maximumPoolSize" to 1,
                    "minimumIdle" to 0,
                    "connectionTimeout" to 8000,
                )
            val poolSource = source.copy(dialect = Dialect.H2, properties = DatasourceProperties(hikari = hikari))
            ConnectionPoolManager.buildHikariPool(poolSource).use { pool ->
                val base = FakeDatasourceRegistry(mapOf(source.name to source))
                val registry =
                    object : DatasourceRegistry by base {
                        override fun poolFor(datasource: Datasource): ConnectionPool = pool
                    }
                val meters = SimpleMeterRegistry()
                // The sole connection stays HELD through the assertions: released any earlier, the
                // executor's still-pending lease would grab it, enlist (`started`), and turn the
                // "never enlisted" check into a race with the pool's return path.
                pool.leaseConnection().use {
                    ExecutorHarness(
                        templateEngine = Fixtures.templateEngine(mapOf("write" to "INSERT INTO tgt VALUES (1)")),
                        registry = registry,
                        config = ExecutorConfig(maxParallelNodes = 4, executionTimeoutSeconds = EXECUTION_DEADLINE_SECONDS.toLong()),
                        metrics = ExecutorMetrics(meters),
                        localWritePlanner = LocalWritePlanner(PipelineResolver { _, _, _ -> null }, registry, 8),
                    ).use { harness ->
                        val started = System.nanoTime()
                        shouldThrow<PipelineTimeoutException> {
                            harness.executor.execute(
                                Fixtures.request(
                                    Fixtures.pipeline(listOf(Fixtures.node("write", NodeType.DML, source.name))),
                                ),
                            )
                        }
                        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
                        // The lower bound proves the deadline was really consumed; the DISCRIMINATOR
                        // between deadline and pool is the thrown code above (execution.timeout, not
                        // datasource_connection_failed), not an elapsed upper bound — execute may
                        // legitimately wait for the abandoned lease call to abort.
                        (elapsedMillis >= EXECUTION_DEADLINE_SECONDS * 1000L - CLOCK_SLACK_MS) shouldBe true
                    }
                    count(source, "tgt") shouldBe 0L
                    outcomes(meters) shouldBe emptyMap()
                }
            }
        }
    }

    private fun count(
        source: Datasource,
        table: String,
    ): Long =
        DriverManager.getConnection(source.jdbcUrl, source.username, source.secret ?: "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM $table").use { rows ->
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
        const val SIBLING_HOLD_MS = 300L
        const val EXECUTION_DEADLINE_SECONDS = 2
        const val CLOCK_SLACK_MS = 250L
    }
}
