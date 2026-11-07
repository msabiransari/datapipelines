package co.datapipelines.executor

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * #837 — [BoundedJdbc]'s socket bound against a real Postgres through a real HikariCP pool, the two premises its
 * design rests on:
 *
 * - **R4, the pool takes the bound back.** The bound is set on the connection a bounded statement runs on. Inside a
 *   Spring-managed transaction that connection stays leased after the statement, so the bound is observable on it;
 *   when the transaction ends and the connection goes back, HikariCP's proxy has marked the network timeout dirty and
 *   restores the pool's own value (`ProxyConnection.setNetworkTimeout` sets `DIRTY_BIT_NETTIMEOUT`;
 *   `PoolBase.resetConnectionState` restores `networkTimeout`, HikariCP 6.3.3). The pool holds ONE connection, so the
 *   second lease is provably the same physical backend (`pg_backend_pid()`), and a request handler that leases it next
 *   does not inherit a scheduled job's tight socket bound.
 * - **R5, the bounded template stays in the transaction.** The returned template is a new template on the SAME
 *   DataSource, so inside `@Transactional` / a `TransactionTemplate` its statements run on the transaction's connection
 *   (`DataSourceUtils` keys it by DataSource identity) — the same backend as a plain template's, and rolled back with it.
 *   A wrapper DataSource would take the bounded statements OUT of the transaction; this case is red for one.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BoundedJdbcPostgresIntegrationTest {
    private lateinit var pool: HikariDataSource
    private lateinit var plain: JdbcTemplate

    @BeforeAll
    fun open() {
        val database = SharedPostgres.postgres
        pool =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = database.jdbcUrl
                    username = database.username
                    password = database.password
                    maximumPoolSize = 1
                    minimumIdle = 1
                    poolName = "bounded-jdbc-837"
                },
            )
        plain = JdbcTemplate(pool)
    }

    @AfterAll
    fun close() = pool.close()

    @BeforeEach
    fun reset() {
        plain.execute("CREATE TABLE IF NOT EXISTS bounded_jdbc_probe (id INT)")
        plain.execute("TRUNCATE bounded_jdbc_probe")
    }

    @Test
    fun `R4 - the bounded statement's connection carries the socket bound, and the pool restores its own on return`() {
        val bounded = BoundedJdbc.bounded(NamedParameterJdbcTemplate(pool), SECONDS, "bound")
        val transactions = TransactionTemplate(DataSourceTransactionManager(pool))

        // The first lease, before anything: the driver's own unbounded read, and which backend it is.
        val before = leaseState()
        before.networkTimeoutMillis shouldBe DRIVER_DEFAULT_MILLIS

        val inside =
            transactions.execute {
                bounded.queryForObject("SELECT 1", emptyMap<String, Any>(), Int::class.java)
                plain.execute(ConnectionCallback { connection -> connection.networkTimeout })
            }
        // Red without the template's override: the statement left the connection's timeout at 0.
        inside shouldBe SOCKET_MILLIS

        val after = leaseState()
        // Non-vacuity: the same physical backend served both leases (one connection in the pool).
        after.backendPid shouldBe before.backendPid
        // The pool restored its own timeout when the transaction's connection went back.
        after.networkTimeoutMillis shouldBe DRIVER_DEFAULT_MILLIS
    }

    @Test
    fun `R5 - a bounded template inside a transaction runs on the transaction's connection and rolls back with it`() {
        val bounded = BoundedJdbc.bounded(NamedParameterJdbcTemplate(pool), SECONDS, "bound")
        val transactions = TransactionTemplate(DataSourceTransactionManager(pool))

        val pids =
            transactions.execute { status ->
                val viaBounded = bounded.queryForObject(PID, emptyMap<String, Any>(), Int::class.java)
                val viaPlain = plain.queryForObject(PID, Int::class.java)
                bounded.update("INSERT INTO bounded_jdbc_probe (id) VALUES (1)", emptyMap<String, Any>())
                // Visible to the plain template in the same transaction: one connection, one snapshot.
                plain.queryForObject("SELECT count(*) FROM bounded_jdbc_probe", Int::class.java) shouldBe 1
                status.setRollbackOnly()
                viaBounded to viaPlain
            }
        requireNotNull(pids)
        pids.first shouldBe pids.second
        // Rolled back with the transaction: the bounded statement was part of it, not auto-committed beside it.
        plain.queryForObject("SELECT count(*) FROM bounded_jdbc_probe", Int::class.java) shouldBe 0
    }

    /** What a fresh lease from the pool sees: its network timeout and which backend it is. */
    private fun leaseState(): LeaseState =
        requireNotNull(
            plain.execute(
                ConnectionCallback { connection ->
                    val pid = connection.createStatement().use { s -> s.executeQuery(PID).use { r -> r.next().let { r.getInt(1) } } }
                    LeaseState(connection.networkTimeout, pid)
                },
            ),
        )

    private data class LeaseState(
        val networkTimeoutMillis: Int,
        val backendPid: Int,
    )

    private companion object {
        const val SECONDS = 7

        /** #837: the bound plus five seconds, in milliseconds — a literal, never computed from the class under test. */
        const val SOCKET_MILLIS = 12_000

        /** The PostgreSQL driver's default network timeout: 0, no limit. */
        const val DRIVER_DEFAULT_MILLIS = 0
        const val PID = "SELECT pg_backend_pid()"
    }
}
