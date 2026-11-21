package co.datapipelines.executor

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * #1035 — the root's durable commit boundary ([CommitLedger]) around the driver's commit: the attempt
 * stamp lands BEFORE the driver is asked to commit, the confirmation AFTER, a stamp that cannot be made
 * durable stops the commit while the work is still honestly rollback-able, and a commit that began is
 * never abandoned by a cancellation. Real H2 behind a POSTGRES-dialect registration; the ONE seam a
 * healthy database cannot produce (a refused commit, a call-order probe) is faked on the leased connection.
 */
class LocalWriteCommitBoundaryTest {
    private val meterRegistry = SimpleMeterRegistry()
    private val metrics = ExecutorMetrics(meterRegistry)
    private val dispatcher =
        ExecutorDispatcher.forConfig(ExecutorConfig(maxParallelNodes = 4, executionTimeoutSeconds = 60), maxThreads = 4)

    /** Everything the transaction did to the ledger and the driver, in call order. */
    private val calls = CopyOnWriteArrayList<String>()

    @AfterEach
    fun tearDown() {
        dispatcher.close()
    }

    /** A ledger that records its calls into [calls]; each answer is configurable. */
    private inner class RecordingLedger(
        private val attempt: () -> Boolean = { true },
        private val confirmed: () -> Boolean = { true },
        private val unavailable: () -> Boolean = { true },
    ) : CommitLedger {
        override fun recordAttempt(executionId: UUID): Boolean {
            calls += "ledger.attempt"
            return attempt()
        }

        override fun recordConfirmed(executionId: UUID): Boolean {
            calls += "ledger.confirmed"
            return confirmed()
        }

        override fun recordResultUnavailable(executionId: UUID): Boolean {
            calls += "ledger.result_unavailable"
            return unavailable()
        }
    }

    /** The driver's commit as a probe: [commit] runs in place of the real one. */
    private class ProbedRegistry(
        private val base: DatasourceRegistry,
        private val commit: (Connection) -> Unit,
    ) : DatasourceRegistry by base {
        override fun poolFor(datasource: Datasource): ConnectionPool =
            object : ConnectionPool {
                override val name get() = datasource.name

                override fun leaseConnection(): Connection {
                    val delegate = DriverManager.getConnection(datasource.jdbcUrl, datasource.username, datasource.secret ?: "")
                    return object : Connection by delegate {
                        override fun commit() = commit(delegate)
                    }
                }

                override fun close() = Unit
            }
    }

    private fun datasource(): Datasource = h2Datasource("pg_main", listOf("CREATE TABLE tgt (id INT)")).copy(dialect = Dialect.POSTGRES)

    private fun transaction(
        datasource: Datasource,
        ledger: CommitLedger,
        commit: (Connection) -> Unit = { it.commit() },
    ): LocalWriteTransaction =
        LocalWriteTransaction(
            rootExecutionId = UUID.randomUUID(),
            workspaceId = UUID.randomUUID(),
            registration = "pg_main",
            registry =
                ProbedRegistry(FakeDatasourceRegistry(mapOf("pg_main" to datasource))) { connection ->
                    calls += "driver.commit"
                    commit(connection)
                },
            metrics = metrics,
            dispatcher = dispatcher,
            ledger = ledger,
        )

    private suspend fun LocalWriteTransaction.insertRow() {
        withParticipant { connection -> connection.createStatement().use { it.execute("INSERT INTO tgt VALUES (1)") } }
    }

    private fun rowsOnOtherConnection(datasource: Datasource): Long =
        DriverManager.getConnection(datasource.jdbcUrl, datasource.username, datasource.secret ?: "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM tgt").use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }

    private fun outcomes(): Map<String, Double> =
        meterRegistry
            .find(ExecutorMetrics.LOCAL_WRITE_TRANSACTIONS)
            .counters()
            .groupBy { it.id.getTag("outcome")!! }
            .mapValues { (_, counters) -> counters.sumOf { it.count() } }

    @Test
    fun `the attempt is stamped before the driver commit and the confirmation after it`() {
        runTest {
            val datasource = datasource()
            val transaction = transaction(datasource, RecordingLedger())
            transaction.insertRow()
            transaction.commitAttempted shouldBe false

            transaction.commit()

            calls.toList() shouldBe listOf("ledger.attempt", "driver.commit", "ledger.confirmed")
            transaction.commitAttempted shouldBe true
            transaction.commitConfirmed shouldBe true
            rowsOnOtherConnection(datasource) shouldBe 1L
            outcomes() shouldBe mapOf("started" to 1.0, "committed" to 1.0)
        }
    }

    @Test
    fun `an attempt stamp that is not durable stops the commit and leaves the work honestly rollback-able`() {
        runTest {
            val datasource = datasource()
            val transaction = transaction(datasource, RecordingLedger(attempt = { false }))
            transaction.insertRow()

            val thrown = shouldThrow<DatapipelinesException> { transaction.commit() }

            thrown.code shouldBe PipelineErrorCodes.Execution.LOCAL_WRITE_REFUSED
            thrown.details["reason"] shouldBe "commit_evidence_unavailable"
            calls.toList() shouldBe listOf("ledger.attempt")
            transaction.commitAttempted shouldBe false
            transaction.commitConfirmed shouldBe false

            // The root's terminal handler: nothing was attempted, so the rollback is real and recorded.
            transaction.rollbackQuietly()
            transaction.close()
            rowsOnOtherConnection(datasource) shouldBe 0L
            outcomes() shouldBe mapOf("started" to 1.0, "rolled_back" to 1.0)
        }
    }

    @Test
    fun `an attempt stamp that throws is treated as not durable - the driver is never asked to commit`() {
        runTest {
            val datasource = datasource()
            val transaction = transaction(datasource, RecordingLedger(attempt = { error("metadata store down") }))
            transaction.insertRow()

            val thrown = shouldThrow<DatapipelinesException> { transaction.commit() }

            thrown.details["reason"] shouldBe "commit_evidence_unavailable"
            calls.contains("driver.commit") shouldBe false
            transaction.commitAttempted shouldBe false
        }
    }

    @Test
    fun `a refused driver commit leaves the attempt stamped and the confirmation absent`() {
        runTest {
            val datasource = datasource()
            val transaction =
                transaction(datasource, RecordingLedger()) { throw SQLException("commit refused (test)") }
            transaction.insertRow()

            val thrown = shouldThrow<DatapipelinesException> { transaction.commit() }

            thrown.code shouldBe PipelineErrorCodes.Execution.LOCAL_WRITE_COMMIT_FAILED
            calls.toList() shouldBe listOf("ledger.attempt", "driver.commit")
            transaction.commitAttempted shouldBe true
            transaction.commitConfirmed shouldBe false
            outcomes() shouldBe mapOf("started" to 1.0, "unknown" to 1.0)
        }
    }

    @Test
    fun `a confirmation stamp that fails never undoes or fails a commit the driver confirmed`() {
        runTest {
            val datasource = datasource()
            val transaction = transaction(datasource, RecordingLedger(confirmed = { error("metadata store down") }))
            transaction.insertRow()

            transaction.commit()

            transaction.commitConfirmed shouldBe true
            rowsOnOtherConnection(datasource) shouldBe 1L
            outcomes() shouldBe mapOf("started" to 1.0, "committed" to 1.0)
        }
    }

    @Test
    fun `marking the result unavailable reaches the ledger and never throws`() {
        runTest {
            val datasource = datasource()
            val reached = transaction(datasource, RecordingLedger())
            reached.markResultUnavailable()
            calls.toList() shouldBe listOf("ledger.result_unavailable")

            val failing = transaction(datasource, RecordingLedger(unavailable = { error("metadata store down") }))
            failing.markResultUnavailable()
            calls.toList() shouldBe listOf("ledger.result_unavailable", "ledger.result_unavailable")
        }
    }

    @Test
    fun `a commit entered by a coroutine that is already cancelled still completes and is confirmed`() {
        runTest {
            val datasource = datasource()
            val transaction = transaction(datasource, RecordingLedger())
            transaction.insertRow()

            val job =
                launch {
                    currentCoroutineContext().cancel()
                    try {
                        transaction.commit()
                    } catch (_: CancellationException) {
                        // The unwinding of the cancelled caller; the commit itself ran non-cancellable.
                    }
                }
            job.join()

            calls.toList() shouldBe listOf("ledger.attempt", "driver.commit", "ledger.confirmed")
            transaction.commitConfirmed shouldBe true
            rowsOnOtherConnection(datasource) shouldBe 1L
        }
    }
}
