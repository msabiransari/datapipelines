package co.datapipelines.executor

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.WriteMode
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import java.sql.Statement
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * `LocalWriteTransaction` — the family lease (design §3.3, §6): lazy enlist under workspace
 * visibility, same-coroutine span reentrancy, root-only settle with honest outcomes, and the
 * run-admission recheck that refuses a registration that vanished or was repointed after
 * planning. Real H2 behind a POSTGRES-dialect registration — a real driver, real visibility
 * semantics across connections — with the ONE seam a healthy database cannot produce (a commit
 * the driver refuses) faked on the connection the pool hands out.
 */
class LocalWriteTransactionTest {
    private val workspaceId = UUID.randomUUID()
    private val meterRegistry = SimpleMeterRegistry()
    private val metrics = ExecutorMetrics(meterRegistry)
    private val dispatcher =
        ExecutorDispatcher.forConfig(ExecutorConfig(maxParallelNodes = 4, executionTimeoutSeconds = 60), maxThreads = 4)

    @AfterEach
    fun tearDown() {
        dispatcher.close()
    }

    private fun postgresH2(
        name: String,
        ddl: List<String> = listOf("CREATE TABLE tgt (id INT)"),
    ): Datasource = h2Datasource(name, ddl).copy(dialect = Dialect.POSTGRES)

    private fun transaction(registry: DatasourceRegistry): LocalWriteTransaction =
        LocalWriteTransaction(
            rootExecutionId = UUID.randomUUID(),
            workspaceId = workspaceId,
            registration = "pg_main",
            registry = registry,
            metrics = metrics,
            dispatcher = dispatcher,
        )

    /** outcome tag → count, summed over dialects — robust to tag order in the meter id. */
    private fun outcomes(): Map<String, Double> =
        meterRegistry
            .find(ExecutorMetrics.LOCAL_WRITE_TRANSACTIONS)
            .counters()
            .groupBy { it.id.getTag("outcome")!! }
            .mapValues { (_, counters) -> counters.sumOf { it.count() } }

    private fun dialectTags(): List<String> =
        meterRegistry
            .find(ExecutorMetrics.LOCAL_WRITE_TRANSACTIONS)
            .counters()
            .map { it.id.getTag("dialect")!! }
            .distinct()

    private fun countFromOtherConnection(
        datasource: Datasource,
        sql: String = "SELECT COUNT(*) FROM tgt",
    ): Long =
        DriverManager.getConnection(datasource.jdbcUrl, datasource.username, datasource.secret ?: "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }

    @Test
    fun `the first span enlists lazily, leases one connection and records started by dialect`() {
        runTest {
            val registry = FakeDatasourceRegistry(mapOf("pg_main" to postgresH2("pg_main")))
            val transaction = transaction(registry)

            transaction.enlisted shouldBe false
            transaction.withParticipant { connection ->
                connection.createStatement().use { it.execute("SELECT 1") }
            }
            transaction.enlisted shouldBe true
            transaction.dialect shouldBe Dialect.POSTGRES

            registry.leased.get() shouldBe 1
            outcomes() shouldBe mapOf("started" to 1.0)
            dialectTags() shouldContainExactly listOf(Dialect.POSTGRES.wire)
        }
    }

    @Test
    fun `a span opened inside another span of the same coroutine reuses the connection`() {
        runTest {
            val registry = FakeDatasourceRegistry(mapOf("pg_main" to postgresH2("pg_main")))
            val transaction = transaction(registry)

            transaction.withParticipant { outer ->
                transaction.withParticipant { inner ->
                    inner shouldBe outer
                }
            }

            registry.leased.get() shouldBe 1
        }
    }

    @Test
    fun `span work is invisible to other connections until the root commits`() {
        runTest {
            val datasource = postgresH2("pg_main", listOf("CREATE TABLE tgt (id INT)"))
            val registry = FakeDatasourceRegistry(mapOf("pg_main" to datasource))
            val transaction = transaction(registry)

            transaction.withParticipant { connection ->
                connection.createStatement().use { it.execute("INSERT INTO tgt VALUES (1)") }
            }
            countFromOtherConnection(datasource) shouldBe 0L

            transaction.commit()
            countFromOtherConnection(datasource) shouldBe 1L
            outcomes() shouldBe mapOf("started" to 1.0, "committed" to 1.0)

            transaction.close()
            registry.closed.get() shouldBe 1
        }
    }

    @Test
    fun `rollbackQuietly discards span work and records rolled_back`() {
        runTest {
            val datasource = postgresH2("pg_main", listOf("CREATE TABLE tgt (id INT)"))
            val registry = FakeDatasourceRegistry(mapOf("pg_main" to datasource))
            val transaction = transaction(registry)

            transaction.withParticipant { connection ->
                connection.createStatement().use { it.execute("INSERT INTO tgt VALUES (1)") }
            }

            transaction.rollbackQuietly()
            countFromOtherConnection(datasource) shouldBe 0L
            outcomes() shouldBe mapOf("started" to 1.0, "rolled_back" to 1.0)
        }
    }

    @Test
    fun `a participant REPLACE clears transactionally and the family rollback restores the replaced rows`() {
        runTest {
            val datasource =
                postgresH2("pg_main", listOf("CREATE TABLE tgt (id INT)", "INSERT INTO tgt VALUES (100)"))
            val registry = FakeDatasourceRegistry(mapOf("pg_main" to datasource))
            val transaction = transaction(registry)
            val idSchema = listOf(ColumnSchema("id", LogicalType.INTEGER))

            JdbcWritebackRunner(registry).writebackRows(
                idSchema,
                sequenceOf(listOf(1)),
                NodeOutput.Datasource("pg_main", "tgt", WriteMode.REPLACE),
                workspaceId,
                transaction = transaction,
            ) shouldBe 1L

            // Mid-transaction the independent client still sees the pre-replace row: the
            // participant's DELETE+INSERT happened only inside the family's uncommitted span.
            countFromOtherConnection(datasource, "SELECT COUNT(*) FROM tgt WHERE id = 100") shouldBe 1L
            countFromOtherConnection(datasource, "SELECT COUNT(*) FROM tgt WHERE id = 1") shouldBe 0L

            transaction.rollbackQuietly()

            // §5/§9: the replace's clear was a transactional DELETE, so it rolls back with the
            // family — the replaced row survives, the replacement does not.
            countFromOtherConnection(datasource, "SELECT COUNT(*) FROM tgt WHERE id = 100") shouldBe 1L
            countFromOtherConnection(datasource, "SELECT COUNT(*) FROM tgt WHERE id = 1") shouldBe 0L
            outcomes() shouldBe mapOf("started" to 1.0, "rolled_back" to 1.0)
        }
    }

    @Test
    fun `a participant REPLACE that commits replaces the rows durably`() {
        runTest {
            val datasource =
                postgresH2("pg_main", listOf("CREATE TABLE tgt (id INT)", "INSERT INTO tgt VALUES (100)"))
            val registry = FakeDatasourceRegistry(mapOf("pg_main" to datasource))
            val transaction = transaction(registry)
            val idSchema = listOf(ColumnSchema("id", LogicalType.INTEGER))

            JdbcWritebackRunner(registry).writebackRows(
                idSchema,
                sequenceOf(listOf(1)),
                NodeOutput.Datasource("pg_main", "tgt", WriteMode.REPLACE),
                workspaceId,
                transaction = transaction,
            ) shouldBe 1L

            transaction.commit()

            countFromOtherConnection(datasource, "SELECT COUNT(*) FROM tgt WHERE id = 100") shouldBe 0L
            countFromOtherConnection(datasource, "SELECT COUNT(*) FROM tgt WHERE id = 1") shouldBe 1L
            outcomes() shouldBe mapOf("started" to 1.0, "committed" to 1.0)
        }
    }

    @Test
    fun `a pool that refuses to lease leaves the transaction un-enlisted - nothing to settle`() {
        runTest {
            val datasource = postgresH2("pg_main", listOf("CREATE TABLE tgt (id INT)"))
            val base = FakeDatasourceRegistry(mapOf("pg_main" to datasource))
            val registry = PoolExhaustedRegistry(base)
            val transaction = transaction(registry)

            shouldThrow<SQLException> {
                transaction.withParticipant { connection ->
                    connection.createStatement().use { it.execute("SELECT 1") }
                }
            }

            // §4 row 2: enlist failed before the first span ran, so the run wrote nothing and the
            // transaction has no lease to settle — commit/rollback/close are all no-ops.
            transaction.enlisted shouldBe false
            transaction.commit()
            transaction.rollbackQuietly()
            transaction.close()
            base.leased.get() shouldBe 0
            outcomes() shouldBe emptyMap()
        }
    }

    @Test
    fun `commit with nothing enlisted is a no-op with zero outcomes and zero leases`() {
        runTest {
            val registry = FakeDatasourceRegistry(mapOf("pg_main" to postgresH2("pg_main")))
            val transaction = transaction(registry)

            transaction.commit()
            transaction.rollbackQuietly()
            transaction.close()

            registry.leased.get() shouldBe 0
            registry.closed.get() shouldBe 0
            outcomes() shouldBe emptyMap()
        }
    }

    @Test
    fun `a registration that vanished after planning refuses at enlist`() {
        runTest {
            val registry = FakeDatasourceRegistry(emptyMap())
            val transaction = transaction(registry)

            val thrown =
                shouldThrow<DatapipelinesException> {
                    transaction.withParticipant { connection ->
                        connection.createStatement().use { it.execute("SELECT 1") }
                    }
                }

            thrown.code shouldBe PipelineErrorCodes.Execution.LOCAL_WRITE_REFUSED
            thrown.details["reason"] shouldBe "registration_ineligible"
            thrown.details["registration"] shouldBe "pg_main"
            registry.leased.get() shouldBe 0
            outcomes() shouldBe emptyMap()
        }
    }

    @Test
    fun `a bounded pool wait that exhausts its timeout writes nothing and fails with the connection reason (R5)`() {
        runBlocking {
            val datasource = postgresH2("pg_main", listOf("CREATE TABLE tgt (id INT)"))
            val base = FakeDatasourceRegistry(mapOf("pg_main" to datasource))
            val registry = BoundedWaitRegistry(base, waitMillis = 150)
            val transaction = transaction(registry)

            val started = System.nanoTime()
            shouldThrow<SQLException> {
                transaction.withParticipant { connection ->
                    connection.createStatement().use { it.execute("SELECT 1") }
                }
            }
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000

            // The lease actually blocked for its bounded timeout before refusing — not an
            // immediate synthetic refusal — and the run wrote nothing.
            (elapsedMillis >= 150L) shouldBe true
            transaction.enlisted shouldBe false
            base.leased.get() shouldBe 0
            countFromOtherConnection(datasource) shouldBe 0L
            outcomes() shouldBe emptyMap()
        }
    }

    @Test
    fun `a registration repointed to an excluded dialect after planning refuses at enlist`() {
        runTest {
            val registry = FakeDatasourceRegistry(mapOf("pg_main" to h2Datasource("pg_main", emptyList())))
            val transaction = transaction(registry)

            val thrown =
                shouldThrow<DatapipelinesException> {
                    transaction.withParticipant { connection ->
                        connection.createStatement().use { it.execute("SELECT 1") }
                    }
                }

            thrown.code shouldBe PipelineErrorCodes.Execution.LOCAL_WRITE_REFUSED
            thrown.details["reason"] shouldBe "registration_ineligible"
            thrown.message shouldContain Dialect.H2.wire
            registry.leased.get() shouldBe 0
        }
    }

    @Test
    fun `a registration repointed mid-run keeps the original lease - the replacement receives nothing (R5)`() {
        runTest {
            val original = postgresH2("pg_main", listOf("CREATE TABLE tgt (id INT)"))
            val replacement = postgresH2("replacement", listOf("CREATE TABLE tgt (id INT)"))
            val base = FakeDatasourceRegistry(mapOf("pg_main" to original))
            val repointed = RepointableRegistry(base, original, replacement)
            val transaction = transaction(repointed)

            transaction.withParticipant { conn ->
                conn.createStatement().use { it.execute("INSERT INTO tgt VALUES (1)") }
            }
            // The registration is repointed to a different physical connection mid-run.
            repointed.repoint()
            transaction.withParticipant { conn ->
                conn.createStatement().use { it.execute("INSERT INTO tgt VALUES (2)") }
            }
            transaction.commit()

            // §6: the lease belongs to the snapshot taken at enlist — every later span still writes
            // the ORIGINAL connection, and the replacement destination receives zero writes.
            countFromOtherConnection(original) shouldBe 2L
            countFromOtherConnection(replacement) shouldBe 0L
            outcomes() shouldBe mapOf("started" to 1.0, "committed" to 1.0)
        }
    }

    @Test
    fun `concurrent spans serialize on the participant connection - never two statements in flight (R5)`() {
        runBlocking {
            val datasource = postgresH2("pg_main", listOf("CREATE TABLE tgt (id INT)"))
            val active = AtomicInteger()
            val maxActive = AtomicInteger()
            val registry = ConcurrentGuardRegistry(FakeDatasourceRegistry(mapOf("pg_main" to datasource)), datasource, active, maxActive)
            val transaction = transaction(registry)

            (1..4)
                .map {
                    async(dispatcher.context) {
                        transaction.withParticipant { conn ->
                            conn.createStatement().use {
                                it.execute("SELECT 1")
                                // Hold the open statement long enough that, without the transaction's
                                // mutex, the four spans would overlap and maxActive would read > 1.
                                delay(50)
                            }
                        }
                    }
                }.awaitAll()

            maxActive.get() shouldBe 1
        }
    }

    @Test
    fun `a driver commit refusal records unknown and throws local_write_commit_failed`() {
        runTest {
            val datasource = postgresH2("pg_main", listOf("CREATE TABLE tgt (id INT)"))
            val leased = AtomicInteger()
            val registry =
                CommitRefusingRegistry(
                    base = FakeDatasourceRegistry(mapOf("pg_main" to datasource)),
                    datasource = datasource,
                    leased = leased,
                )
            val transaction = transaction(registry)
            transaction.withParticipant { connection ->
                connection.createStatement().use { it.execute("INSERT INTO tgt VALUES (1)") }
            }

            val thrown = shouldThrow<DatapipelinesException> { transaction.commit() }

            thrown.code shouldBe PipelineErrorCodes.Execution.LOCAL_WRITE_COMMIT_FAILED
            thrown.details["registration"] shouldBe "pg_main"
            thrown.details["execution_id"] shouldBe transaction.rootExecutionId.toString()
            outcomes() shouldBe mapOf("started" to 1.0, "unknown" to 1.0)
        }
    }

    @Test
    fun `a driver that refuses the autocommit setter returns the connection once and records nothing (R3)`() {
        runTest {
            val datasource = postgresH2("pg_main", listOf("CREATE TABLE tgt (id INT)"))
            val leased = AtomicInteger()
            val closed = AtomicInteger()
            val registry =
                SetAutocommitRefusingRegistry(
                    base = FakeDatasourceRegistry(mapOf("pg_main" to datasource)),
                    datasource = datasource,
                    leased = leased,
                    closed = closed,
                )
            val transaction = transaction(registry)

            shouldThrow<SQLException> {
                transaction.withParticipant { connection ->
                    connection.createStatement().use { it.execute("SELECT 1") }
                }
            }

            // R3: the setup failure wrote nothing, but the acquired connection is returned/discarded
            // EXACTLY once — no leak — and no started/committed outcome was invented.
            leased.get() shouldBe 1
            closed.get() shouldBe 1
            transaction.enlisted shouldBe false
            outcomes() shouldBe emptyMap()
        }
    }

    @Test
    fun `a commit that landed but lost its ack records unknown only - no rollback evidence (R4)`() {
        runTest {
            val datasource = postgresH2("pg_main", listOf("CREATE TABLE tgt (id INT)"))
            val registry =
                CommitThenThrowRegistry(
                    base = FakeDatasourceRegistry(mapOf("pg_main" to datasource)),
                    datasource = datasource,
                )
            val transaction = transaction(registry)
            transaction.withParticipant { connection ->
                connection.createStatement().use { it.execute("INSERT INTO tgt VALUES (1)") }
            }

            shouldThrow<DatapipelinesException> { transaction.commit() }
            // The root's cleanup still runs rollbackQuietly — and must NOT record rolled_back
            // evidence that would imply the (possibly durable) write was undone.
            transaction.rollbackQuietly()
            transaction.close()

            countFromOtherConnection(datasource) shouldBe 1L
            outcomes() shouldBe mapOf("started" to 1.0, "unknown" to 1.0)
        }
    }

    @Test
    fun `a same-registration write-back drains the source cursor before writing, above one batch (R2)`() {
        runTest {
            val datasource =
                postgresH2(
                    "pg_main",
                    listOf(
                        "CREATE TABLE src (id INT)",
                        "CREATE TABLE tgt (id INT)",
                        "INSERT INTO src SELECT \"X\" FROM SYSTEM_RANGE(1, 1001)",
                    ),
                )
            val registry = CursorOverlapRegistry(FakeDatasourceRegistry(mapOf("pg_main" to datasource)), datasource)
            val transaction = transaction(registry)
            val runner = JdbcWritebackRunner(registry)

            transaction.withParticipant { conn ->
                val guard = conn as StreamingGuardConnection
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT id FROM src").use { rs ->
                        val tracked = TrackedCursor(rs)
                        guard.activeSource = tracked
                        runner.writeback(
                            tracked,
                            NodeOutput.Datasource("pg_main", "tgt", WriteMode.APPEND),
                            Dialect.POSTGRES,
                            workspaceId,
                            transaction = transaction,
                        ) shouldBe 1001L
                        tracked.exhausted shouldBe true
                    }
                }
            }
            registry.baseLeased() shouldBe 1
        }
    }

    @Test
    fun `a same-registration REPLACE write-back drains before the transactional clear (R2)`() {
        runTest {
            val datasource =
                postgresH2(
                    "pg_main",
                    listOf(
                        "CREATE TABLE src (id INT)",
                        "CREATE TABLE tgt (id INT)",
                        "INSERT INTO src SELECT \"X\" FROM SYSTEM_RANGE(1, 1)",
                        "INSERT INTO tgt VALUES (100)",
                    ),
                )
            val registry = CursorOverlapRegistry(FakeDatasourceRegistry(mapOf("pg_main" to datasource)), datasource)
            val transaction = transaction(registry)
            val runner = JdbcWritebackRunner(registry)

            transaction.withParticipant { conn ->
                val guard = conn as StreamingGuardConnection
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT id FROM src").use { rs ->
                        val tracked = TrackedCursor(rs)
                        guard.activeSource = tracked
                        runner.writeback(
                            tracked,
                            NodeOutput.Datasource("pg_main", "tgt", WriteMode.REPLACE),
                            Dialect.POSTGRES,
                            workspaceId,
                            transaction = transaction,
                        ) shouldBe 1L
                        tracked.exhausted shouldBe true
                    }
                }
            }
            transaction.commit()
            countFromOtherConnection(datasource, "SELECT COUNT(*) FROM tgt WHERE id = 1") shouldBe 1L
            countFromOtherConnection(datasource, "SELECT COUNT(*) FROM tgt WHERE id = 100") shouldBe 0L
        }
    }

    @Test
    fun `the composition-path drain refuses a lazy child sequence over the configured budget (R2)`() {
        runTest {
            val datasource = postgresH2("pg_main", listOf("CREATE TABLE tgt (v TEXT)"))
            val registry = FakeDatasourceRegistry(mapOf("pg_main" to datasource))
            val transaction = transaction(registry)
            // The configured budget is the drain ceiling: 32 bytes, while one 128-byte value
            // JSON-encodes to 132 bytes.
            val runner = JdbcWritebackRunner(registry, drainMaxBytes = 32)
            val idSchema = listOf(ColumnSchema("v", LogicalType.STRING))

            transaction.withParticipant { conn ->
                conn.createStatement().use { it.execute("SELECT 1") }
                val thrown =
                    shouldThrow<DatapipelinesException> {
                        runner.writebackRows(
                            idSchema,
                            sequenceOf(listOf<Any?>("x".repeat(128))),
                            NodeOutput.Datasource("pg_main", "tgt", WriteMode.APPEND),
                            workspaceId,
                            transaction = transaction,
                        )
                    }
                thrown.code shouldBe PipelineErrorCodes.Node.WRITEBACK_FAILED
            }

            // The drain refused before any write: the target is untouched.
            countFromOtherConnection(datasource) shouldBe 0L
        }
    }

    /**
     * A connection that refuses any new statement while a "streaming" source cursor is still open —
     * the Connector/J restriction the review reproduced on MySQL ("No statements may be issued when
     * any streaming result sets are open and in use on a given connection"). The source cursor the
     * write-back is handed registers here via [activeSource]; the drain must exhaust and close it
     * before the write's own `prepareStatement`/`createStatement` runs.
     */
    private class StreamingGuardConnection(
        private val delegate: Connection,
    ) : Connection by delegate {
        var activeSource: TrackedCursor? = null

        override fun createStatement(): Statement = guard { delegate.createStatement() }

        override fun createStatement(
            resultSetType: Int,
            resultSetConcurrency: Int,
        ): Statement = guard { delegate.createStatement(resultSetType, resultSetConcurrency) }

        override fun prepareStatement(sql: String): PreparedStatement = guard { delegate.prepareStatement(sql) }

        private fun <T> guard(block: () -> T): T {
            val source = activeSource
            if (source != null && !source.exhausted && !source.closed) {
                throw SQLException("streaming result set still open (test)")
            }
            return block()
        }
    }

    /** A cursor that records when it is exhausted and closed — the drain's two observable edges. */
    private class TrackedCursor(
        private val delegate: ResultSet,
    ) : ResultSet by delegate {
        var exhausted = false
        var closed = false

        override fun next(): Boolean {
            val has = delegate.next()
            if (!has) exhausted = true
            return has
        }

        override fun close() {
            closed = true
            delegate.close()
        }
    }

    /** A registry whose participant connection is a [StreamingGuardConnection] (the overlap seam). */
    private class CursorOverlapRegistry(
        private val base: FakeDatasourceRegistry,
        private val datasource: Datasource,
    ) : DatasourceRegistry by base {
        override fun poolFor(datasource: Datasource): ConnectionPool {
            val delegatePool = base.poolFor(datasource)
            return object : ConnectionPool by delegatePool {
                override fun leaseConnection(): Connection = StreamingGuardConnection(delegatePool.leaseConnection())
            }
        }

        fun baseLeased(): Int = base.leased.get()
    }

    /** `poolFor` hands out a real connection whose `commit` refuses — the lost-ack seam. */
    private class CommitRefusingRegistry(
        private val base: DatasourceRegistry,
        private val datasource: Datasource,
        private val leased: AtomicInteger,
    ) : DatasourceRegistry by base {
        override fun poolFor(datasource: Datasource): ConnectionPool =
            object : ConnectionPool {
                override val name get() = datasource.name

                override fun leaseConnection(): Connection {
                    leased.incrementAndGet()
                    val delegate = DriverManager.getConnection(datasource.jdbcUrl, datasource.username, datasource.secret ?: "")
                    return object : Connection by delegate {
                        override fun commit(): Unit = throw SQLException("commit refused (test)")
                    }
                }

                override fun close() = Unit
            }
    }

    /** `poolFor` hands out a real connection whose `setAutoCommit` refuses — the setup seam (R3). */
    private class SetAutocommitRefusingRegistry(
        private val base: DatasourceRegistry,
        private val datasource: Datasource,
        private val leased: AtomicInteger,
        private val closed: AtomicInteger,
    ) : DatasourceRegistry by base {
        override fun poolFor(datasource: Datasource): ConnectionPool =
            object : ConnectionPool {
                override val name get() = datasource.name

                override fun leaseConnection(): Connection {
                    leased.incrementAndGet()
                    val delegate = DriverManager.getConnection(datasource.jdbcUrl, datasource.username, datasource.secret ?: "")
                    return object : Connection by delegate {
                        override fun setAutoCommit(autoCommit: Boolean): Unit = throw SQLException("autocommit setter refused (test)")

                        override fun close() {
                            closed.incrementAndGet()
                            delegate.close()
                        }
                    }
                }

                override fun close() = Unit
            }
    }

    /** `poolFor` hands out a connection whose `commit` commits then throws — the lost-ack seam (R4). */
    private class CommitThenThrowRegistry(
        private val base: DatasourceRegistry,
        private val datasource: Datasource,
    ) : DatasourceRegistry by base {
        override fun poolFor(datasource: Datasource): ConnectionPool =
            object : ConnectionPool {
                override val name get() = datasource.name

                override fun leaseConnection(): Connection {
                    val delegate = DriverManager.getConnection(datasource.jdbcUrl, datasource.username, datasource.secret ?: "")
                    return object : Connection by delegate {
                        override fun commit() {
                            delegate.commit()
                            throw SQLException("commit ack lost (test)")
                        }
                    }
                }

                override fun close() = Unit
            }
    }

    /**
     * `poolFor` refuses to lease — the pool-wait-exhausted seam (§4 row 2): Hikari's
     * `SQLTransientConnectionException` after `connectionTimeout`, modelled as a plain refusal.
     */
    private class PoolExhaustedRegistry(
        private val base: DatasourceRegistry,
    ) : DatasourceRegistry by base {
        override fun poolFor(datasource: Datasource): ConnectionPool =
            object : ConnectionPool {
                override val name get() = datasource.name

                override fun leaseConnection(): Connection =
                    throw SQLException("Connection is not available, request timed out after 30000ms (test)")

                override fun close() = Unit
            }
    }

    /**
     * `poolFor` blocks for its bounded [waitMillis] and then refuses with Hikari's
     * `SQLTransientConnectionException` — the ACTUAL bounded pool wait (§4 row 2), as distinct from
     * the immediate refusal [PoolExhaustedRegistry] models. The caller observes both the elapsed
     * wait and the connection-failure exception type.
     */
    private class BoundedWaitRegistry(
        private val base: DatasourceRegistry,
        private val waitMillis: Long,
    ) : DatasourceRegistry by base {
        override fun poolFor(datasource: Datasource): ConnectionPool =
            object : ConnectionPool {
                override val name get() = datasource.name

                override fun leaseConnection(): Connection {
                    Thread.sleep(waitMillis)
                    throw SQLTransientConnectionException("Connection is not available, request timed out after ${waitMillis}ms (test)")
                }

                override fun close() = Unit
            }
    }

    /**
     * A registration that can be repointed mid-run: after [repoint], `getVisible` for [original]
     * answers [replacement] (a different physical connection under the same name). The transaction
     * snapshots at enlist, so later spans must keep writing the original.
     */
    private class RepointableRegistry(
        private val base: DatasourceRegistry,
        private val original: Datasource,
        private val replacement: Datasource,
    ) : DatasourceRegistry by base {
        private val repointed = AtomicBoolean()

        fun repoint() {
            repointed.set(true)
        }

        override fun getVisible(
            name: String,
            workspaceId: UUID,
        ): Datasource? =
            if (repointed.get() && name == original.name) {
                replacement
            } else {
                base.getVisible(name, workspaceId)
            }
    }

    /**
     * A registry whose participant connection is a [ConcurrentGuardConnection] (the serialization
     * seam): it records the highest number of statements ever in flight at once, so a span that
     * did not serialize on the transaction's mutex would read > 1.
     */
    private class ConcurrentGuardRegistry(
        private val base: FakeDatasourceRegistry,
        private val datasource: Datasource,
        private val active: AtomicInteger,
        private val maxActive: AtomicInteger,
    ) : DatasourceRegistry by base {
        override fun poolFor(datasource: Datasource): ConnectionPool {
            val delegatePool = base.poolFor(datasource)
            return object : ConnectionPool by delegatePool {
                override fun leaseConnection(): Connection = ConcurrentGuardConnection(delegatePool.leaseConnection(), active, maxActive)
            }
        }
    }

    /** Counts statements in flight: `createStatement`/`prepareStatement` increment, close decrements. */
    private class ConcurrentGuardConnection(
        private val delegate: Connection,
        private val active: AtomicInteger,
        private val maxActive: AtomicInteger,
    ) : Connection by delegate {
        override fun createStatement(): Statement = GuardedStatement(delegate.createStatement(), ::enter)

        override fun createStatement(
            resultSetType: Int,
            resultSetConcurrency: Int,
        ): Statement = GuardedStatement(delegate.createStatement(resultSetType, resultSetConcurrency), ::enter)

        override fun prepareStatement(sql: String): PreparedStatement = GuardedPrepared(delegate.prepareStatement(sql), ::enter)

        private fun enter() {
            active.incrementAndGet()
            maxActive.updateAndGet { maxOf(it, active.get()) }
        }

        private fun leave() {
            active.decrementAndGet()
        }

        private inner class GuardedStatement(
            private val delegate: Statement,
            private val onOpen: () -> Unit,
        ) : Statement by delegate {
            init {
                onOpen()
            }

            override fun close() {
                leave()
                delegate.close()
            }
        }

        private inner class GuardedPrepared(
            private val delegate: PreparedStatement,
            private val onOpen: () -> Unit,
        ) : PreparedStatement by delegate {
            init {
                onOpen()
            }

            override fun close() {
                leave()
                delegate.close()
            }
        }
    }
}
