package co.datapipelines.datasources

import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.datasources.pooling.ConnectionPoolManager
import co.datapipelines.datasources.pooling.HikariConnectionPool
import co.datapipelines.datasources.pooling.LakeViewInit
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.jdbc.DuckDbAuthorSqlGate
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * #816 (datasources.md §5.8, TEMPORARY): the author-SQL guard sits at the pool, so every lease of a
 * `DUCKDB` or `LAKE` datasource passes statement text through [DuckDbAuthorSqlGate] — and nothing
 * else changes. Three halves:
 *
 *  - A1: the production factory ([ConnectionPoolManager.buildHikariPool]) arms the guard for exactly
 *    the two DuckDB dialects; an H2 pool runs the same text unguarded.
 *  - A2: every DP statement family that runs on such a lease still passes — table stats, the
 *    introspector's reads, the probe's `EXPLAIN`, the lake reads and the pre-flight's prelude.
 *  - The pool's own lifecycle survives the wrap: [ConnectionPool.discard] still evicts, a DUCKDB
 *    lease freezes every setting but session state, and a LAKE owner stays unlocked (its engine
 *    can be shared by a joining pool — see `openLakeInstance`) with its session postlude applied.
 *
 * Falsified at birth (handback, gates G2 and G4): arming only `DUCKDB` turns the LAKE case red; a
 * refused name planted in `TableStatsPlans.DUCKDB` turns the stats case red.
 */
class DuckDbAuthorSqlGuardPoolTest {
    @TempDir
    lateinit var tempDir: File

    private val carrier = "SELECT * FROM query('SELECT 1')"

    private fun embedded(
        dialect: Dialect,
        name: String,
        url: String,
        properties: DatasourceProperties = DatasourceProperties(),
    ) = Datasource(
        name = name,
        displayName = "Guarded ${dialect.wire}",
        dialect = dialect,
        jdbcUrl = url,
        credentialKind = CredentialKind.NONE,
        properties = properties,
    )

    /** A lake with no catalog kind: no extension, no secret, no network — the instance alone. */
    private fun localLake(name: String) = embedded(Dialect.LAKE, name, "jdbc:duckdb::memory:")

    private fun refusedByTheGuard(
        route: String,
        call: () -> Any?,
    ) {
        withClue(route) { shouldThrow<SQLException> { call() }.sqlState shouldBe DuckDbAuthorSqlGate.SQL_STATE }
    }

    private fun Connection.scalar(sql: String): String =
        createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                rs.next()
                rs.getString(1)
            }
        }

    @Test
    fun `A1 - a DUCKDB pool refuses a carrier call on every lease route and runs ordinary SQL`() {
        ConnectionPoolManager.buildHikariPool(embedded(Dialect.DUCKDB, "duck_guarded", "jdbc:duckdb::memory:")).use { pool ->
            pool.leaseConnection().use { c ->
                refusedByTheGuard("createStatement.execute") { c.createStatement().use { it.execute(carrier) } }
                refusedByTheGuard("prepareStatement") { c.prepareStatement(carrier) }
                refusedByTheGuard("statement.connection") { c.createStatement().use { st -> st.connection.prepareStatement(carrier) } }
                c.scalar("SELECT 1") shouldBe "1"
            }
        }
    }

    @Test
    fun `A1 - a LAKE pool refuses a carrier call, and its engine stays unlocked for a joining pool`() {
        ConnectionPoolManager.buildHikariPool(localLake("lake_guarded")).use { pool ->
            pool.leaseConnection().use { c ->
                refusedByTheGuard("LAKE createStatement.execute") { c.createStatement().use { it.execute(carrier) } }
                refusedByTheGuard("LAKE prepareStatement") { c.prepareStatement(carrier) }
                c.scalar("SELECT 1") shouldBe "1"
                // The LAKE lock decision (§5.8): not locked, so a second pool on a shared engine can
                // still apply its own settings (LakeSpillIntegrationTest's joining pool).
                c.scalar("SELECT current_setting('lock_configuration')") shouldBe "false"
            }
        }
    }

    @Test
    fun `A1 - an H2 pool hands out the driver's connection and runs the same text unguarded`() {
        ConnectionPoolManager.buildHikariPool(Fixtures.h2(name = "h2_unguarded")).use { pool ->
            pool.leaseConnection().use { c ->
                // H2 has no such table function: the text reaches the driver and fails THERE,
                // with H2's own state — never the guard's.
                val thrown = shouldThrow<SQLException> { c.createStatement().use { it.execute(carrier) } }
                thrown.sqlState shouldNotBe DuckDbAuthorSqlGate.SQL_STATE
                (pool as HikariConnectionPool).poolConnectionOf(c) shouldBeSameInstanceAs c
            }
        }
    }

    @Test
    fun `A1 - a registered lake table reads through a guarded lease`() {
        val file = tempDir.resolve("trips.parquet")
        DriverManager.getConnection("jdbc:duckdb:").use { writer ->
            writer.createStatement().use {
                it.execute("COPY (SELECT 1 AS id UNION ALL SELECT 2) TO '${file.absolutePath}' (FORMAT PARQUET)")
            }
        }
        val adapter = DialectAdapters.forDialect(Dialect.LAKE)
        val table = LakeRegisteredTable(listOf("nyc", "mobility"), "trips", "parquet", "file://${file.absolutePath}")
        val ds = localLake("lake_views")
        val plan = LakeViewStatements.planForTables(listOf(table), adapter)
        ConnectionPoolManager
            .buildHikariPool(ds, lakeViews = LakeViewInit(ds.name, plan, LakeViewOutcomeRecorder.NONE))
            .use { pool ->
                pool.leaseConnection().use { c ->
                    // The postlude's `SET search_path` ran on the duplicate, so the unqualified view
                    // name resolves.
                    c.scalar("SELECT count(*) FROM trips") shouldBe "2"
                }
            }
    }

    @Test
    fun `A5 - a DUCKDB lease freezes every engine setting except session state`() {
        ConnectionPoolManager.buildHikariPool(embedded(Dialect.DUCKDB, "duck_frozen", "jdbc:duckdb::memory:")).use { pool ->
            pool.leaseConnection().use { c ->
                val settings = c.settingNames()
                val outcomes = settings.groupBy { name -> resetOutcome(c, name) }
                println("event=duckdb_frozen_settings settings=${settings.size} outcomes=${outcomes.mapValues { it.value.size }}")
                // Non-vacuity: the pinned engine has 158 settings; a broken listing cannot pass.
                (settings.size >= SETTINGS_FLOOR) shouldBe true
                // Every RESET is ADMITTED, GATED (the gate refuses the setting's NAME before the engine
                // sees it) or LOCKED; any other failure throws in resetOutcome. Only session state may
                // be admitted.
                outcomes.getValue(ResetOutcome.ADMITTED) shouldBe SESSION_STATE
            }
        }
    }

    private enum class ResetOutcome { ADMITTED, GATED, LOCKED }

    private fun Connection.settingNames(): List<String> =
        createStatement().use { st ->
            st.executeQuery("SELECT name FROM duckdb_settings() ORDER BY 1").use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }

    private fun resetOutcome(
        c: Connection,
        name: String,
    ): ResetOutcome {
        val refusal = runCatching { c.createStatement().use { it.execute("RESET \"$name\"") } }.exceptionOrNull()
        return when {
            refusal == null -> ResetOutcome.ADMITTED
            (refusal as? SQLException)?.sqlState == DuckDbAuthorSqlGate.SQL_STATE -> ResetOutcome.GATED
            refusal.message?.contains(LOCKED) == true -> ResetOutcome.LOCKED
            else -> throw AssertionError("RESET \"$name\" failed for another reason", refusal)
        }
    }

    @Test
    fun `discard still evicts a guarded lease, and the pool replaces it`() {
        val ds =
            embedded(
                Dialect.DUCKDB,
                "duck_discard",
                "jdbc:duckdb::memory:",
                DatasourceProperties(hikari = mapOf("maximumPoolSize" to 1, "connectionTimeout" to LEASE_TIMEOUT_MS)),
            )
        ConnectionPoolManager.buildHikariPool(ds).use { pool ->
            val lease = pool.leaseConnection()
            val proxy = (pool as HikariConnectionPool).poolConnectionOf(lease)
            proxy shouldNotBeSameInstanceAs lease
            pool.activeConnections shouldBe 1
            pool.discard(lease)
            // Hikari's evictConnection is a silent no-op for a connection that is not its own proxy;
            // had the guard reached it, the single slot would stay checked out and this lease time out.
            pool.activeConnections shouldBe 0
            pool.leaseConnection().use { it.scalar("SELECT 1") shouldBe "1" }
            lease.close()
        }
    }

    @Test
    fun `a probe refused by the guard is a statement refusal carrying the guard's message, not a privilege one`() {
        val ds = embedded(Dialect.DUCKDB, "duck_probe", "jdbc:duckdb::memory:")
        ConnectionPoolManager.buildHikariPool(ds).use { pool ->
            val registry = mockk<DatasourceRegistry>()
            every { registry.get(ds.name) } returns ds
            every { registry.poolFor(ds) } returns pool
            val thrown = shouldThrow<SqlProbeExecutionException> { SqlProbe(registry, PROBE_TIMEOUT_SECONDS).probe(ds, carrier) }
            // The sql_probe / preview-rows / selector boundaries choose table_forbidden exactly when
            // this is true; false keeps the author on query_execution_failed with the text below.
            (thrown.cause as SQLException).isPermissionDenied() shouldBe false
            thrown.driverMessage shouldContain "DuckDB refuses a call to `query` or `query_table` in author SQL"
            thrown.driverMessage shouldNotContain "SELECT 1"
        }
    }

    @Test
    fun `A2 - DP's own DUCKDB statements pass the guard on a real lease`() {
        val file = tempDir.resolve("stats.duckdb")
        val ds =
            embedded(
                Dialect.DUCKDB,
                "duck_dp_sql",
                "jdbc:duckdb:${file.absolutePath}",
                DatasourceProperties(hikari = mapOf("maximumPoolSize" to 1)),
            )
        ConnectionPoolManager.buildHikariPool(ds).use { pool ->
            pool.leaseConnection().use { c ->
                c.createStatement().use {
                    it.execute("CREATE TABLE orders (id INTEGER PRIMARY KEY, code VARCHAR UNIQUE, amount INTEGER)")
                    it.execute("CREATE INDEX orders_amount ON orders (amount)")
                    it.execute("INSERT INTO orders VALUES (1, 'a', 10), (2, 'b', 20)")
                }
            }
            // §7C's plan, every query, bound the way TableStatsReader binds it.
            val plan = TableStatsPlans.DUCKDB
            plan.rowEstimate shouldNotBe null
            pool.leaseConnection().use { c ->
                (listOfNotNull(plan.rowEstimate) + plan.indexes).forEach { query ->
                    withClue(query.sql) {
                        shouldNotThrowAny {
                            c.prepareStatement(query.sql).use { ps ->
                                ps.setString(1, "main")
                                ps.setString(2, "orders")
                                ps.executeQuery().use { rs -> rs.next() }
                            }
                        }
                    }
                }
                // §7D's probe plan.
                val explain = DialectAdapters.forDialect(Dialect.DUCKDB).explainSelectSql("SELECT * FROM orders WHERE amount > 5")
                shouldNotThrowAny { c.createStatement().use { st -> st.executeQuery(explain).use { rs -> rs.next() } } }
            }
            // §7A / §7C through the introspector, on the same guarded pool.
            val registry = mockk<DatasourceRegistry>()
            every { registry.get(ds.name) } returns ds
            every { registry.poolFor(ds) } returns pool
            val introspector = SchemaIntrospector(registry)
            shouldNotThrowAny {
                introspector.tables(ds.name).tables.map { it.name } shouldContain "orders"
                introspector.columns(ds.name, "orders").map { it.column.name } shouldBe listOf("id", "code", "amount")
                introspector.tableStats(ds.name, "orders")
                introspector.schemas(ds.name)
            }
        }
    }

    @Test
    fun `A2 - the lake reads and the pre-flight prelude are text the guard admits`() {
        val adapter = DialectAdapters.forDialect(Dialect.LAKE)
        val tables =
            listOf(
                LakeRegisteredTable(listOf("nyc", "mobility"), "trips", "parquet", "s3://bucket/trips/*.parquet"),
                LakeRegisteredTable(listOf("lake"), "events", "iceberg", "s3://bucket/events/metadata/v3.metadata.json"),
                LakeRegisteredTable(listOf("a", "b"), "local", "parquet", "file:///data/local.parquet"),
            )
        val plan = LakeViewStatements.planForTables(tables, adapter, "/opt/duckdb/extensions")
        val texts =
            plan.prelude + plan.views.mapNotNull { it.sql } + plan.postlude +
                tables.flatMap { t ->
                    val qualified = (t.namespace + t.name).joinToString(".") { adapter.quoteIdentifier(it) }
                    // SchemaIntrospector.lakeColumns and LakeTablePreflight.readCandidate.
                    listOf("SELECT * FROM $qualified LIMIT 0", "SELECT * FROM $qualified LIMIT 1")
                } +
                adapter.connectionInit(
                    localLake("lake_init").copy(
                        properties = DatasourceProperties(dialect = mapOf("catalog.kind" to "s3", "region" to "us-east-1")),
                    ),
                )
        (texts.size > tables.size * 2) shouldBe true
        texts.forEach { sql -> withClue(sql) { shouldNotThrowAny { DuckDbAuthorSqlGate.check(sql) } } }
    }

    private companion object {
        /** HikariCP's floor for connectionTimeout is 250 ms. */
        const val LEASE_TIMEOUT_MS = 250L

        const val LOCKED = "the configuration has been locked"

        const val PROBE_TIMEOUT_SECONDS = 30

        /** 158 on the pinned 1.5.5.1 (lane #816 evidence); the floor only guards a broken listing. */
        const val SETTINGS_FLOOR = 150

        /** The two session-scoped settings the lock admits: DP's own `SET search_path` and `USE`. */
        val SESSION_STATE = listOf("schema", "search_path")
    }
}
