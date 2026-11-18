package co.datapipelines.executor

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
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
}
