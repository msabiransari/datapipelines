package co.datapipelines.datasources

import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.datasources.pooling.HikariConnectionPool
import co.datapipelines.typesystem.Dialect
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicReference

/**
 * [ReadOnlyStatementLease] — the parameter engine's lease (record §2.5): the gate before any lease,
 * the timeout clamp, the row budget, the failure classification, and the two endings that exclude
 * each other. The abandonment path runs against the module's Postgres container with a real
 * running statement; the rest against a recording pool over the same container.
 */
class ReadOnlyStatementLeaseTest {
    private val postgres = SharedPostgres.postgres
    private val opened = mutableListOf<AutoCloseable>()

    @AfterEach
    fun cleanUp() {
        opened.asReversed().forEach { runCatching { it.close() } }
        admin {
            it.execute(
                "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE query LIKE '%pg_sleep(60)%' AND pid <> pg_backend_pid()",
            )
        }
    }

    private fun datasource(queryTimeoutSeconds: Int? = null) =
        Datasource(
            name = "pg_lease",
            displayName = "PG",
            dialect = Dialect.POSTGRES,
            jdbcUrl = postgres.jdbcUrl,
            username = postgres.username,
            secret = postgres.password,
            queryTimeoutSeconds = queryTimeoutSeconds,
        )

    /** Records every lease, close and discard — the endings are the assertions (never a strict mock). */
    private inner class RecordingPool : ConnectionPool {
        override val name = "pg_lease"
        val leased = mutableListOf<Connection>()
        val discarded = mutableListOf<Connection>()

        override fun leaseConnection(): Connection =
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).also {
                leased += it
                opened += it
            }

        override fun discard(connection: Connection) {
            discarded += connection
            connection.close()
        }

        override fun close() = Unit
    }

    private fun registryOver(pool: ConnectionPool): DatasourceRegistry =
        mockk<DatasourceRegistry>().also { every { it.poolFor(any()) } returns pool }

    @Test
    fun `the gate runs before any lease - a write is refused and no pool is asked`() {
        val registry = mockk<DatasourceRegistry>()
        val lease = ReadOnlyStatementLease(registry)

        shouldThrow<SqlProbeRefusalException> { lease.open(datasource(), "DELETE FROM t", emptyList(), 2, 10) }
        shouldThrow<SqlProbeRefusalException> { lease.open(datasource(), "SELECT 1; SELECT 2", emptyList(), 2, 10) }
        shouldThrow<SqlProbeRefusalException> { lease.open(datasource(), "SELECT * INTO t2 FROM t", emptyList(), 2, 10) }

        verify(exactly = 0) { registry.poolFor(any()) }
    }

    @Test
    fun `the statement timeout is the datasource's own clamped to the ceiling, or the ceiling`() {
        ReadOnlyStatementLease.effectiveTimeoutSeconds(datasource(queryTimeoutSeconds = 30), 10) shouldBe 10
        ReadOnlyStatementLease.effectiveTimeoutSeconds(datasource(queryTimeoutSeconds = 5), 10) shouldBe 5
        ReadOnlyStatementLease.effectiveTimeoutSeconds(datasource(queryTimeoutSeconds = null), 10) shouldBe 10
    }

    @Test
    fun `binds are positional values, the row budget caps what the cursor yields, and close returns the connection`() {
        val pool = RecordingPool()
        val lease = ReadOnlyStatementLease(registryOver(pool))

        val statement =
            lease.open(datasource(), "SELECT g FROM generate_series(1, ?) AS g WHERE g >= ? ORDER BY g", listOf(10, 3), maxRows = 2, 10)
        val rows = statement.use { it.query { rs -> generateSequence { if (rs.next()) rs.getInt(1) else null }.toList() } }

        rows shouldContainExactly listOf(3, 4)
        pool.leased.single().isClosed shouldBe true
        pool.discarded shouldBe emptyList()
    }

    @Test
    fun `a statement over its clamped timeout is the timeout, not an unreachable database`() {
        val lease = ReadOnlyStatementLease(registryOver(RecordingPool()))

        val statement = lease.open(datasource(queryTimeoutSeconds = 30), "SELECT pg_sleep(5)", emptyList(), 2, timeoutCeilingSeconds = 1)

        statement.use { shouldThrow<SqlProbeTimeoutException> { it.query { } } }
    }

    @Test
    fun `a failing statement is the execution failure with the driver's bounded text`() {
        val lease = ReadOnlyStatementLease(registryOver(RecordingPool()))

        val failure =
            shouldThrow<SqlProbeExecutionException> {
                lease.open(datasource(), "SELECT no_such_column FROM pg_class", emptyList(), 2, 10).use { it.query { } }
            }

        failure.driverMessage.contains("no_such_column") shouldBe true
    }

    @Test
    fun `a pool that cannot be built or leased from is an unreachable datasource`() {
        val registry = mockk<DatasourceRegistry>().also { every { it.poolFor(any()) } throws IllegalStateException("pool init failed") }

        shouldThrow<DatasourceUnreachableException> { ReadOnlyStatementLease(registry).open(datasource(), "SELECT 1", emptyList(), 2, 10) }
    }

    @Test
    fun `abandon after close does nothing - the returned connection may already serve its next borrower`() {
        val pool = RecordingPool()
        val statement = ReadOnlyStatementLease(registryOver(pool)).open(datasource(), "SELECT 1", emptyList(), 2, 10)
        statement.query { }

        statement.close()
        statement.abandon()

        pool.discarded shouldBe emptyList()
        statement.isAbandoned shouldBe false
    }

    @Test
    fun `close after abandon returns nothing - the connection was discarded once`() {
        val pool = RecordingPool()
        val statement = ReadOnlyStatementLease(registryOver(pool)).open(datasource(), "SELECT 1", emptyList(), 2, 10)

        statement.abandon()
        statement.abandon()
        statement.close()

        pool.discarded shouldContainExactly pool.leased
        statement.isAbandoned shouldBe true
    }

    @Test
    fun `abandon from another thread stops a running statement and discards its connection from the real pool`() {
        val hikari =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    username = postgres.username
                    password = postgres.password
                    minimumIdle = 1
                    maximumPoolSize = 1
                    poolName = "lease-abandon"
                },
            ).also { opened += it }
        val pool = HikariConnectionPool("pg_lease", hikari)
        val statement =
            ReadOnlyStatementLease(
                registryOver(pool),
            ).open(datasource(), "SELECT pg_sleep(60), pg_backend_pid()", emptyList(), 2, 120)
        val ended = AtomicReference<Throwable?>()
        val worker =
            Thread {
                try {
                    statement.query { }
                } catch (e: RuntimeException) {
                    ended.set(e)
                } finally {
                    statement.close()
                }
            }.apply {
                isDaemon = true
                start()
            }
        waitUntil("the sleep is running") { sleepingPid() != null }
        val pid = checkNotNull(sleepingPid())

        statement.abandon()

        worker.join(JOIN_MILLIS)
        withClue("the worker ended at once, on the cancel or the closed connection") {
            worker.isAlive shouldBe false
            (ended.get() != null) shouldBe true
        }
        waitUntil("the cancel reached the server - backend $pid no longer runs the sleep") { sleepingPid() == null }
        waitUntil("the pool refilled with a fresh connection, nothing active") {
            hikari.hikariPoolMXBean.activeConnections == 0 && hikari.hikariPoolMXBean.totalConnections == 1
        }
        pool.leaseConnection().use { fresh -> listOf(pid) shouldNotContain backendPid(fresh) }
    }

    /** The pid running the 60 s sleep, if any — read on its own connection. */
    private fun sleepingPid(): Int? =
        admin { s ->
            s
                .executeQuery(
                    "SELECT pid FROM pg_stat_activity WHERE state = 'active' AND query LIKE '%pg_sleep(60)%' AND pid <> pg_backend_pid()",
                ).use {
                    if (it.next()) it.getInt(1) else null
                }
        }

    private fun backendPid(connection: Connection): Int =
        connection.createStatement().use { s ->
            s.executeQuery("SELECT pg_backend_pid()").use {
                it.next()
                it.getInt(1)
            }
        }

    private fun <T> admin(block: (java.sql.Statement) -> T): T =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c -> c.createStatement().use(block) }

    private fun waitUntil(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + WAIT_NANOS
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting until $what" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private companion object {
        const val JOIN_MILLIS = 10_000L
        const val WAIT_NANOS = 10_000_000_000L
        const val POLL_MILLIS = 20L
    }
}
