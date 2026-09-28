package co.datapipelines.datasources

import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.datasources.pooling.ConnectionPoolManager
import co.datapipelines.datasources.pooling.HikariConnectionPool
import co.datapipelines.typesystem.Dialect
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicReference

/**
 * `ConnectionPool.discard` (parameter-engine record §2.5, §12's datasources row) against the
 * module's shared Postgres container: a connection whose statement is still RUNNING on another
 * thread is discarded, and the pool behaves as the record says HikariCP 6.3.3's
 * `evictConnection` does — the physical connection closes at once, the pool refills to
 * `minimumIdle`, the sleeping borrower ends with a connection error, and the discarded
 * connection never serves anyone again.
 *
 * What discard does NOT do, measured here on the first run: it closes the CLIENT side only. The
 * Postgres backend that was running `pg_sleep` stayed alive and sleeping after the discard —
 * a server notices a vanished client only when it next writes (`client_connection_check_interval`
 * is 0 by default). Stopping the server-side statement is `Statement.cancel()`'s job, which is why
 * the selector runner's abandonment cancels FIRST and discards second
 * ([LeasedStatement.abandon], whose test asserts the backend goes idle). Discard's guarantee is the
 * POOL's: the connection is never handed out again, and a fresh one takes its place.
 *
 * Synchronised on events, never on time: the sleep is observed running in `pg_stat_activity`
 * (polled on its own autocommit connection) before the discard, and every "after" is a bounded
 * poll on the state it expects. The sleep is long (60 s) so that a discard that did nothing
 * cannot be rescued by the statement ending on its own inside a bound.
 */
class ConnectionPoolDiscardIntegrationTest {
    private val postgres = SharedPostgres.postgres
    private val opened = mutableListOf<AutoCloseable>()

    @AfterEach
    fun cleanUp() {
        opened.asReversed().forEach { runCatching { it.close() } }
        // A falsified discard leaves the 60 s sleep running; end it rather than the container's next test.
        admin {
            it.execute(
                "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE query LIKE '%pg_sleep(60)%' AND pid <> pg_backend_pid()",
            )
        }
    }

    private fun hikari(
        poolName: String,
        minimumIdle: Int = MINIMUM_IDLE,
        maximumPoolSize: Int = MINIMUM_IDLE,
    ): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                this.minimumIdle = minimumIdle
                this.maximumPoolSize = maximumPoolSize
                this.poolName = poolName
            },
        ).also { opened += it }

    @Test
    fun `a connection mid-statement is discarded - closed at once, never returned, and the pool refills to minimumIdle`() {
        val hikari = hikari("discard-mid-statement")
        val pool = HikariConnectionPool("pg_discard", hikari)
        waitUntil("the pool fills to minimumIdle") { hikari.hikariPoolMXBean.totalConnections == MINIMUM_IDLE }

        val borrowed = pool.leaseConnection()
        val pid = backendPid(borrowed)
        val failure = AtomicReference<Throwable?>()
        val sleeper =
            Thread {
                try {
                    borrowed.createStatement().use { it.execute("SELECT pg_sleep(60)") }
                } catch (e: SQLException) {
                    failure.set(e)
                } finally {
                    runCatching { borrowed.close() } // the borrower's own close — after a discard it returns nothing
                }
            }.apply {
                isDaemon = true
                start()
            }
        waitUntil("the sleep is running on backend $pid") { sleeping(pid) }
        hikari.hikariPoolMXBean.activeConnections shouldBe 1

        pool.discard(borrowed)

        withClue("the pool no longer counts the discarded connection, and refills to minimumIdle") {
            waitUntil("active 0, total = minimumIdle") {
                hikari.hikariPoolMXBean.activeConnections == 0 && hikari.hikariPoolMXBean.totalConnections == MINIMUM_IDLE
            }
        }
        sleeper.join(JOIN_MILLIS)
        withClue("the sleeping borrower ended — on a connection error, not the sleep's end") {
            sleeper.isAlive shouldBe false
            val error = failure.get().shouldBeInstanceOf<SQLException>()
            error.isConnectionFailure() shouldBe true
        }
        withClue("nothing returned to the pool: every connection it hands out now is a different backend") {
            val a = pool.leaseConnection().also { opened += it }
            val b = pool.leaseConnection().also { opened += it }
            listOf(backendPid(a), backendPid(b)) shouldNotContain pid
            hikari.hikariPoolMXBean.totalConnections shouldBe MINIMUM_IDLE
        }
    }

    @Test
    fun `discard after the pool is shut down is a no-op, never an exception`() {
        val hikari = hikari("discard-after-close")
        val pool = HikariConnectionPool("pg_discard_closed", hikari)
        val borrowed = pool.leaseConnection()
        pool.close()

        pool.discard(borrowed) // must not throw

        pool.isClosed shouldBe true
    }

    @Test
    fun `every production pool kind discards through Hikari - an in-process H2 pool built by the manager`() {
        val datasource =
            Datasource(
                name = "h2_discard",
                displayName = "H2",
                dialect = Dialect.H2,
                jdbcUrl = "jdbc:h2:mem:discard_${System.nanoTime()};DB_CLOSE_DELAY=-1",
                username = "sa",
                secret = "sa",
            )
        val pool = ConnectionPoolManager.buildHikariPool(datasource).also { opened += it }
        pool.shouldBeInstanceOf<HikariConnectionPool>()
        val borrowed = pool.leaseConnection()
        pool.activeConnections shouldBe 1

        pool.discard(borrowed)

        pool.activeConnections shouldBe 0
        // Hikari's proxy answers isClosed() for its BORROWER only; the physical connection behind it
        // is what the discard closed, so the observable fact is that it can no longer run anything.
        waitUntil("the discarded H2 connection refuses a statement") {
            runCatching { borrowed.createStatement().use { it.execute("SELECT 1") } }.isFailure
        }
        pool.leaseConnection().use { it.isValid(1) shouldBe true }
    }

    @Test
    fun `the default discard of a non-pooling pool closes the connection`() {
        val physical = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        val pool =
            object : ConnectionPool {
                override val name = "fresh_per_lease"

                override fun leaseConnection(): Connection = physical

                override fun close() = Unit
            }

        pool.discard(physical)

        physical.isClosed shouldBe true
    }

    private fun backendPid(connection: Connection): Int =
        connection.createStatement().use { s ->
            s.executeQuery("SELECT pg_backend_pid()").use {
                it.next()
                it.getInt(1)
            }
        }

    private fun sleeping(pid: Int): Boolean =
        admin { s ->
            s
                .executeQuery(
                    "SELECT count(*) FROM pg_stat_activity WHERE pid = $pid AND state = 'active' AND query LIKE '%pg_sleep(60)%'",
                ).use {
                    it.next()
                    it.getInt(1) == 1
                }
        }

    /** One autocommit statement on its OWN connection — a snapshot inside a transaction would freeze (MISTAKES). */
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
        const val MINIMUM_IDLE = 2
        const val JOIN_MILLIS = 10_000L
        const val WAIT_NANOS = 10_000_000_000L
        const val POLL_MILLIS = 20L
    }
}
