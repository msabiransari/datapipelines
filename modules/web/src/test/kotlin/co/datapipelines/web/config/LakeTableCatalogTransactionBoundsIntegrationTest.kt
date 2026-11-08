package co.datapipelines.web.config

import co.datapipelines.application.datasources.LakeTableRepository
import co.datapipelines.datasources.LakeRegisteredTable
import co.datapipelines.datasources.LakeTableCatalog
import co.datapipelines.datasources.ManagedLakeTable
import co.datapipelines.web.TestRepoFiles
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.fail
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * #867 — the wired `lakeTableCatalog` bean against a real PostgreSQL through a real HikariCP pool of ONE connection, the
 * production shape of the split ([LakeConfiguration.transactionAwareManagedCatalog]):
 *
 * - **Inside a metadata transaction** the managed read joins the transaction's connection (it sees the transaction's
 *   uncommitted lifecycle row, which no other connection can; the pool has no second connection to give it anyway), sets
 *   no socket bound on it, and a LATER statement of the same transaction that runs longer than the listing's 10 s socket
 *   bound completes. Rollback discards what the transaction wrote. A nested participant (a savepoint) behaves the same.
 * - **Outside** the managed read is bounded and the next borrower of the same physical backend has the pool's own
 *   network timeout back (HikariCP's reset, `BoundedJdbcPostgresIntegrationTest` R4).
 * - **A paused metadata server** — the managed read already acquired and running (waiting on a catalog lock), then the
 *   container paused — ends at the bounded statement's completion, `BoundedJdbc`'s measured 15 s nominal (5 s statement
 *   bound + pgjdbc's 10 s default `cancelSignalTimeout`, past the 10 s socket bound), and the pool recovers after.
 *
 * A PRIVATE container: a paused shared Postgres would stall every later suite in this JVM (the #266 rule).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class LakeTableCatalogTransactionBoundsIntegrationTest {
    private val container: PostgreSQLContainer<*> =
        PostgreSQLContainer("postgres:16-alpine").withDatabaseName("p867").withUsername("dp").withPassword("dp")
    private lateinit var admin: NamedParameterJdbcTemplate
    private lateinit var pool: HikariDataSource
    private lateinit var plain: JdbcTemplate
    private lateinit var catalog: LakeTableCatalog
    private lateinit var transactions: TransactionTemplate
    private lateinit var userId: UUID
    private lateinit var namespaceId: UUID

    @BeforeAll
    fun open() {
        container.start()
        val unpooled = DriverManagerDataSource(container.jdbcUrl, container.username, container.password)
        TestRepoFiles.migrationPaths().forEach { path -> JdbcTemplate(unpooled).execute(TestRepoFiles.read(path)) }
        admin = NamedParameterJdbcTemplate(unpooled)
        pool = pool("lake-867", size = 1)
        plain = JdbcTemplate(pool)
        catalog = bean(pool)
        transactions = TransactionTemplate(DataSourceTransactionManager(pool))
    }

    @AfterAll
    fun close() {
        runCatching { unpause() }
        pool.close()
        container.stop()
    }

    @BeforeEach
    fun seed() {
        admin.update(
            "TRUNCATE iceberg_tables, lake_table_revisions, lake_leases, lake_tables_managed, lake_namespaces, " +
                "lake_tables, datasources, users CASCADE",
            emptyMap<String, Any>(),
        )
        userId = UUID.randomUUID()
        admin.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'T', 'google', :sub)",
            mapOf("id" to userId, "email" to "u$userId@example.com", "sub" to "sub-$userId"),
        )
        admin.update(
            "INSERT INTO datasources (name, display_name, dialect, jdbc_url, credential_kind, created_by) " +
                "VALUES (:name, 'Lake', 'LAKE', 'jdbc:duckdb::memory:', 'none', :actor)",
            mapOf("name" to LAKE, "actor" to userId),
        )
        namespaceId = UUID.randomUUID()
        admin.update(
            "INSERT INTO lake_namespaces (id, name, status, created_by) VALUES (:id, 'sales', 'active', :by)",
            mapOf("id" to namespaceId, "by" to userId),
        )
        admin.update(
            "INSERT INTO lake_tables (datasource_id, namespace, name, format, location, registered_by, origin, revision_seq) " +
                "VALUES (:ds, ARRAY['lake', 'sales'], 'orders', 'iceberg', :loc, :by, 'managed', 1)",
            mapOf("ds" to LAKE, "loc" to PROJECTION_LOCATION, "by" to userId),
        )
        catalogRow()
        // Committed state: a catalog row and NO lifecycle row — the read reports "no lifecycle row".
    }

    @Test
    fun `inside a transaction the read joins its connection, sets no socket bound, and a later longer statement completes`() {
        var orders: LakeRegisteredTable? = null
        var socketAfterRead = UNREAD
        var longer: Result<Unit>? = null
        var pids = UNREAD to UNREAD
        // Captured, asserted after the transaction: a socket bound left on the connection closes it at the longer
        // statement, and the rollback that follows would otherwise replace the assertion with "Connection is closed".
        val transaction =
            runCatching {
                transactions.executeWithoutResult { status ->
                    status.setRollbackOnly()
                    val pid = backendPid()
                    insertLifecycleRow(plain)
                    orders = catalog.registeredTables(LAKE).single()
                    socketAfterRead = networkTimeout()
                    // Longer than the listing read's 10 s socket bound.
                    val slept = runCatching { plain.execute("SELECT pg_sleep($LONGER_THAN_SOCKET_SECONDS)") }
                    longer = slept
                    pids = pid to (if (slept.isSuccess) backendPid() else UNREAD)
                }
            }

        assertAll(
            // Only the transaction's connection can see its uncommitted lifecycle row.
            { orders?.unavailableReason.shouldBeNull() },
            { orders?.location shouldBe CATALOG_LOCATION },
            { socketAfterRead shouldBe DRIVER_DEFAULT_MILLIS },
            {
                withClue("the later longer statement: ${longer?.exceptionOrNull()?.let(::causeChain)}") {
                    longer?.isSuccess shouldBe true
                }
            },
            { withClue("the transaction: ${transaction.exceptionOrNull()?.let(::causeChain)}") { transaction.isSuccess shouldBe true } },
            { pids.second shouldBe pids.first },
        )
        withClue("the rollback discarded the transaction's lifecycle row") {
            catalog.registeredTables(LAKE).single().unavailableReason shouldBe ManagedLakeTable.REASON_NO_LIFECYCLE_ROW
        }
    }

    @Test
    fun `a nested participant (savepoint) reads on the outer transaction's connection without a socket bound`() {
        val nested = TransactionTemplate(DataSourceTransactionManager(pool)).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_NESTED
        }
        transactions.executeWithoutResult { outer ->
            val pid = backendPid()
            insertLifecycleRow(plain)
            nested.executeWithoutResult { inner ->
                catalog.registeredTables(LAKE).single().unavailableReason.shouldBeNull()
                networkTimeout() shouldBe DRIVER_DEFAULT_MILLIS
                inner.setRollbackOnly()
            }
            assertAll(
                { backendPid() shouldBe pid },
                { networkTimeout() shouldBe DRIVER_DEFAULT_MILLIS },
            )
            outer.setRollbackOnly()
        }
    }

    @Test
    fun `outside a transaction the read is bounded and the next borrower of the same backend has its own timeout back`() {
        val before = lease()

        catalog.registeredTables(LAKE).single().unavailableReason shouldBe ManagedLakeTable.REASON_NO_LIFECYCLE_ROW

        val after = lease()
        assertAll(
            // Non-vacuity: one connection in the pool, so the read ran on this very backend.
            { after.second shouldBe before.second },
            { after.first shouldBe DRIVER_DEFAULT_MILLIS },
        )
    }

    /**
     * In a transaction on ANOTHER DataSource Spring's synchronization is active, so the listing's own projection read
     * (plain template, first) binds this pool's connection to that scope until the scope ends. The managed read then
     * finds a connection bound for the metadata DataSource and runs on it unbounded — the safe choice for a connection
     * the scope holds, where a socket bound would outlive the read. The selector itself keys on the DataSource, not the
     * thread flag (`LakeTableCatalogTransactionDispatchTest`). Production has no second transaction manager (every
     * `DataSourceTransactionManager` is over the metadata DataSource; evidence `867`, caller trace).
     */
    @Test
    fun `under another DataSource's transaction the projection read holds the connection and the managed read leaves it unbounded`() {
        pool("lake-867-other", size = 1).use { other ->
            TransactionTemplate(DataSourceTransactionManager(other)).executeWithoutResult {
                catalog.registeredTables(LAKE)
                networkTimeout() shouldBe DRIVER_DEFAULT_MILLIS
            }
        }
        lease().first shouldBe DRIVER_DEFAULT_MILLIS
    }

    @Test
    fun `a paused metadata server ends the outside managed read at the bounded completion, and the pool recovers`() {
        pool("lake-867-stall", size = 2).use { stall ->
            val stalled = bean(stall)
            val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "lake-867-stalled-read").apply { isDaemon = true } }
            val lock = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
            lock.autoCommit = false
            lock.createStatement().execute("LOCK TABLE iceberg_tables IN ACCESS EXCLUSIVE MODE")
            var paused = false
            try {
                val started = System.nanoTime()
                val read = executor.submit(Callable { runCatching { stalled.registeredTables(LAKE) } })
                awaitManagedReadWaitingOnLock()
                pause()
                paused = true
                val outcome =
                    try {
                        read.get(STALL_CAP_SECONDS, TimeUnit.SECONDS)
                    } catch (_: TimeoutException) {
                        fail("the outside managed read did not end within $STALL_CAP_SECONDS s against a paused server")
                    }
                val tookMs = (System.nanoTime() - started) / NANOS_PER_MILLI
                println("LANE867 stalled read ended after $tookMs ms: ${outcome.exceptionOrNull()?.let(::causeChain)}")
                assertAll(
                    { outcome.exceptionOrNull().shouldBeInstanceOf<DataAccessException>() },
                    // Past the socket bound (a statement cancel cannot reach a paused server), within the nominal 15 s.
                    { withClue("measured $tookMs ms") { (tookMs >= SOCKET_MILLIS) shouldBe true } },
                    { withClue("measured $tookMs ms") { (tookMs < STALL_CEILING_MILLIS) shouldBe true } },
                )
            } finally {
                if (paused) unpause()
                runCatching { lock.rollback() }
                lock.close()
                executor.shutdownNow()
            }
            // Recovery: the same bean reads again and the pool hands out a connection at its own timeout.
            stalled.registeredTables(LAKE).single().unavailableReason shouldBe ManagedLakeTable.REASON_NO_LIFECYCLE_ROW
            JdbcTemplate(stall).execute(ConnectionCallback { it.networkTimeout }) shouldBe DRIVER_DEFAULT_MILLIS
        }
    }

    // ------------------ fixtures

    private fun bean(dataSource: HikariDataSource): LakeTableCatalog {
        val jdbc = NamedParameterJdbcTemplate(dataSource)
        return LakeConfiguration().lakeTableCatalog(LakeTableRepository(jdbc), jdbc, beanProviderOf(SCHEDULED_JOBS_TEST_LAKE_PROPERTIES))
    }

    private fun pool(
        name: String,
        size: Int,
    ) = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = container.jdbcUrl
            username = container.username
            password = container.password
            maximumPoolSize = size
            minimumIdle = size
            connectionTimeout = ACQUIRE_MILLIS
            poolName = name
        },
    )

    /** The network timeout of the connection [plain] gets here — the transaction's when one is bound. */
    private fun networkTimeout(): Int = requireNotNull(plain.execute(ConnectionCallback { it.networkTimeout }))

    private fun backendPid(): Int = requireNotNull(plain.queryForObject("SELECT pg_backend_pid()", Int::class.java))

    /** A fresh lease outside any transaction: its network timeout and which backend it is. */
    private fun lease(): Pair<Int, Int> =
        requireNotNull(
            plain.execute(
                ConnectionCallback { c ->
                    val pid = c.createStatement().use { s -> s.executeQuery("SELECT pg_backend_pid()").use { r -> r.next().let { r.getInt(1) } } }
                    c.networkTimeout to pid
                },
            ),
        )

    private fun insertLifecycleRow(jdbc: JdbcTemplate) {
        jdbc.update(
            "INSERT INTO lake_tables_managed (id, namespace_id, name, status, policy_json, created_by) " +
                "VALUES (?, ?, 'orders', 'active', '{}', ?)",
            UUID.randomUUID(),
            namespaceId,
            userId,
        )
    }

    /** One catalog pointer row, written under a live table lease, the only write the V53 fence admits. */
    private fun catalogRow() {
        val token = UUID.randomUUID()
        val operation = UUID.randomUUID()
        admin.update(
            "INSERT INTO lake_leases (kind, name, operation_id, token, acquired_at, expires_at, deadline_at, operation, holder_instance) " +
                "VALUES ('table', 'sales.orders', :op, :token, now(), now() + interval '1 minute', now() + interval '1 minute', " +
                "'test', 'test')",
            mapOf("op" to operation, "token" to token),
        )
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
            connection.autoCommit = false
            connection.prepareStatement("SELECT set_config('dp.lease_token', ?, true), set_config('dp.operation_id', ?, true)").use {
                it.setString(1, token.toString())
                it.setString(2, operation.toString())
                it.executeQuery().close()
            }
            connection
                .prepareStatement(
                    "INSERT INTO iceberg_tables (catalog_name, table_namespace, table_name, metadata_location, " +
                        "previous_metadata_location, iceberg_type) VALUES (?, 'sales', 'orders', ?, NULL, 'TABLE')",
                ).use {
                    it.setString(1, LAKE)
                    it.setString(2, CATALOG_LOCATION)
                    it.executeUpdate()
                }
            connection.commit()
        }
    }

    /** Polled on its own connection (a snapshot inside a transaction would never change): the managed read is blocked. */
    private fun awaitManagedReadWaitingOnLock() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS)
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { probe ->
            while (true) {
                val waiting =
                    probe.createStatement().use { s ->
                        s
                            .executeQuery(
                                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' " +
                                    "AND pid <> pg_backend_pid() AND query LIKE '%LEFT JOIN iceberg_tables%'",
                            ).use { r -> r.next().let { r.getInt(1) } }
                    }
                if (waiting > 0) return
                check(System.nanoTime() < deadline) { "the managed read never waited on the catalog lock" }
                Thread.sleep(POLL_MILLIS)
            }
        }
    }

    /** The exception classes outermost first, and the root's message (the driver's, never customer SQL here). */
    private fun causeChain(e: Throwable): String {
        val chain = generateSequence(e) { it.cause }.toList()
        return chain.joinToString(" <- ") { it.javaClass.simpleName } + " | root: " + chain.last().message
    }

    private fun pause() {
        DockerClientFactory.instance().client().pauseContainerCmd(container.containerId).exec()
    }

    private fun unpause() {
        DockerClientFactory.instance().client().unpauseContainerCmd(container.containerId).exec()
    }

    private companion object {
        const val LAKE = "lake"
        const val PROJECTION_LOCATION = "s3://dp-lake/lake/sales/orders/metadata/00001-a.metadata.json"
        const val CATALOG_LOCATION = "s3://dp-lake/lake/sales/orders/metadata/00002-b.metadata.json"

        /** The PostgreSQL driver's default network timeout: 0, no limit. */
        const val DRIVER_DEFAULT_MILLIS = 0

        /** A captured value the case never reached. */
        const val UNREAD = -1

        /** The listing read's socket bound (5 s + BoundedJdbc's 5 s slack) — a literal, never computed from the class under test. */
        const val SOCKET_MILLIS = 10_000L

        /** Two seconds past the socket bound. */
        const val LONGER_THAN_SOCKET_SECONDS = 12

        /** The bounded statement's nominal completion against a paused server is 15 s; slack for a loaded box. */
        const val STALL_CEILING_MILLIS = 25_000L

        /** How long the case waits before calling the read unbounded. */
        const val STALL_CAP_SECONDS = 40L

        const val ACQUIRE_MILLIS = 2_000L
        const val AWAIT_SECONDS = 10L
        const val POLL_MILLIS = 50L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
