package co.datapipelines.lake.append

import co.datapipelines.lake.LakeTestDb
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.withClue
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
 * #858 — the managed lake runtime's statement template (`LakeAppendRuntime.statements`, the production factory) against a
 * real Postgres through a real HikariCP pool of ONE connection, so every lease is provably the same physical backend
 * (`pg_backend_pid()`). The pool's own socket bound is a non-zero [POOL_SOCKET_MILLIS] (the driver's `socketTimeout`),
 * so "the pool restored its value" is told apart from "nothing was ever set".
 *
 * - **R4, the pool takes the bound back.** Inside a five-second Spring transaction — the runtime's shape — the
 *   transaction's connection carries [SOCKET_MILLIS] after a bounded statement; when it goes back, HikariCP's proxy has
 *   marked the network timeout dirty and restores the pool's value. A sibling plain template's statement on the next
 *   lease of that same backend runs under the pool's value, never the runtime's.
 * - **R5, the bounded template stays in the transaction.** Its statements run on the transaction's connection (Spring
 *   keys it by DataSource identity) and roll back with it. A wrapper DataSource would take them out of the transaction;
 *   with one connection in the pool that shows here as a different backend or no lease at all, red on the backend check.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LakeBoundedJdbcPostgresIntegrationTest {
    private lateinit var pool: HikariDataSource
    private lateinit var plain: JdbcTemplate

    @BeforeAll
    fun open() {
        val database = LakeTestDb.freshDatabase(DATABASE)
        val user = LakeTestDb.connect().use { it.metaData.userName }
        pool =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = LakeTestDb.urlFor(database)
                    username = user
                    password = LakeTestDb.password
                    maximumPoolSize = 1
                    minimumIdle = 1
                    // A wrapper's second lease must fail fast, not wait the default 30 s.
                    connectionTimeout = LEASE_WAIT_MILLIS
                    validationTimeout = VALIDATION_MILLIS
                    addDataSourceProperty("socketTimeout", POOL_SOCKET_SECONDS.toString())
                    poolName = "lake-bounded-jdbc-858"
                },
            )
        plain = JdbcTemplate(pool)
    }

    @AfterAll
    fun close() = pool.close()

    @BeforeEach
    fun reset() {
        plain.execute("CREATE TABLE IF NOT EXISTS lake_bounded_jdbc_probe (id INT)")
        plain.execute("TRUNCATE lake_bounded_jdbc_probe")
    }

    @Test
    fun `R4 - a transaction's connection carries the socket bound, and the pool restores its own value on return`() {
        val bounded = NamedParameterJdbcTemplate(LakeAppendRuntime.statements(pool))

        // The first lease, before anything: the pool's own socket bound, and which backend it is.
        val before = leaseState()
        before.networkTimeoutMillis shouldBe POOL_SOCKET_MILLIS

        val inside =
            transactions().execute {
                bounded.queryForObject("SELECT 1", emptyMap<String, Any>(), Int::class.java)
                plain.execute(ConnectionCallback { connection -> connection.networkTimeout })
            }
        // Red without the template's override: the statement left the connection at the pool's value.
        inside shouldBe SOCKET_MILLIS

        val after = leaseState()
        // Non-vacuity: the same physical backend served both leases (one connection in the pool).
        after.backendPid shouldBe before.backendPid
        // The pool restored its own value when the transaction's connection went back.
        after.networkTimeoutMillis shouldBe POOL_SOCKET_MILLIS
    }

    @Test
    fun `R4 - an autocommit bounded statement leaves no bound behind for the next plain lease of the same backend`() {
        val before = leaseState()

        LakeAppendRuntime.statements(pool).queryForObject("SELECT 1", Int::class.java) shouldBe 1

        val sibling = leaseState()
        sibling.backendPid shouldBe before.backendPid
        sibling.networkTimeoutMillis shouldBe POOL_SOCKET_MILLIS
    }

    @Test
    fun `R5 - the runtime's statements inside a transaction run on its connection and roll back with it`() {
        val bounded = NamedParameterJdbcTemplate(LakeAppendRuntime.statements(pool))

        val pids =
            transactions().execute { status ->
                val viaPlain = plain.queryForObject(PID, Int::class.java)
                // Caught, so a wrapper that cannot lease (the one connection is the transaction's) reads as a backend
                // mismatch with its cause in the clue, not as an error somewhere else.
                val viaBounded = runCatching { bounded.queryForObject(PID, emptyMap<String, Any>(), Int::class.java) }
                if (viaBounded.isSuccess) {
                    bounded.update("INSERT INTO lake_bounded_jdbc_probe (id) VALUES (:id)", mapOf("id" to 1))
                    // Visible to the plain template in the same transaction: one connection, one snapshot.
                    plain.queryForObject(COUNT, Int::class.java) shouldBe 1
                }
                status.setRollbackOnly()
                viaPlain to viaBounded
            }
        requireNotNull(pids)
        withClue("the bounded statement's backend inside the transaction: ${pids.second}") {
            pids.second.getOrNull() shouldBe pids.first
        }
        // Rolled back with the transaction: the bounded insert was part of it, not auto-committed beside it.
        plain.queryForObject(COUNT, Int::class.java) shouldBe 0
    }

    /** The runtime's own transaction shape: five seconds, over the same DataSource. */
    private fun transactions() = TransactionTemplate(DataSourceTransactionManager(pool)).apply { timeout = TRANSACTION_SECONDS }

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
        const val DATABASE = "lake_bounded_jdbc_858"

        /** #858: the runtime's 5 s statement bound plus five seconds, in milliseconds — a literal. */
        const val SOCKET_MILLIS = 10_000

        /** The pool's own socket bound: the driver's `socketTimeout`, chosen to differ from every other number here. */
        const val POOL_SOCKET_SECONDS = 37
        const val POOL_SOCKET_MILLIS = 37_000

        const val TRANSACTION_SECONDS = 5
        const val LEASE_WAIT_MILLIS = 1_000L
        const val VALIDATION_MILLIS = 250L
        const val PID = "SELECT pg_backend_pid()"
        const val COUNT = "SELECT count(*) FROM lake_bounded_jdbc_probe"
    }
}
