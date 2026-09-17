package co.datapipelines.datasources

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.datasources.pooling.ConnectionPoolManager
import co.datapipelines.datasources.pooling.HikariConnectionPool
import co.datapipelines.datasources.pooling.LakeViewInit
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.io.TempDir
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant

/**
 * 152 (#128) — the LIFECYCLE and ISOLATION rules of a LAKE pool generation's shared DuckDB
 * instance, proven through the PRODUCTION factory path ([ConnectionPoolManager.buildHikariPool]
 * with a [LakeViewInit], the shape `DefaultDatasourceRegistry` builds) and a real
 * [ConnectionPoolManager] with its §5.2 retire/reap lifecycle.
 *
 * What is pinned, one test each: two simultaneous leases are two physical connections on ONE
 * initialized instance (catalog, secrets-free limits, restricted-extension posture all shared);
 * two datasources on the same anonymous URL never share one; a registry change rebuilds a NEW
 * generation whose in-flight old reader keeps its snapshot while new readers see the new rows;
 * a failing recorder does not fail the build; a pool whose first session init fails leaves no
 * owner behind; one connection's cancelled query does not touch its sibling; and a shared
 * memory budget is ONE budget — an over-budget query on one connection fails honestly while
 * the sibling keeps working.
 */
class LakeInstanceLifecycleIntegrationTest {
    @TempDir
    lateinit var tempDir: File

    private val adapter = DialectAdapters.forDialect(Dialect.LAKE)

    private fun lakeDatasource(
        name: String,
        dialect: Map<String, Any?> = emptyMap(),
    ) = Datasource(
        name = name,
        displayName = "Lake",
        dialect = Dialect.LAKE,
        jdbcUrl = "jdbc:duckdb::memory:",
        credentialKind = CredentialKind.NONE,
        properties = DatasourceProperties(dialect = dialect),
    )

    private fun parquet(
        file: String,
        selectSql: String,
    ): File {
        val target = tempDir.resolve(file)
        DriverManager.getConnection("jdbc:duckdb:").use { writer ->
            writer.createStatement().use { it.execute("COPY ($selectSql) TO '${target.absolutePath}' (FORMAT PARQUET)") }
        }
        return target
    }

    private fun table(
        name: String,
        file: File,
        namespace: List<String> = listOf("nyc", "mobility"),
    ) = LakeRegisteredTable(namespace, name, "parquet", "file://${file.absolutePath}")

    /** The production factory shape: rows re-read per build, outcomes through the recorder seam. */
    private fun poolManager(
        rows: () -> List<LakeRegisteredTable>,
        recorder: LakeViewOutcomeRecorder = LakeViewOutcomeRecorder.NONE,
        clock: () -> Instant = Instant::now,
    ) = ConnectionPoolManager(
        poolFactory = { datasource ->
            ConnectionPoolManager.buildHikariPool(
                datasource,
                lakeViews =
                    LakeViewInit(
                        datasourceName = datasource.name,
                        plan = LakeViewStatements.planForTables(rows(), adapter),
                        recorder = recorder,
                    ),
            )
        },
        clock = clock,
    )

    @Test
    @Suppress("NestedBlockDepth") // nested `use` blocks are the leases' lifetimes — flattening would hide them
    fun `two simultaneous leases are two physical connections on ONE initialized instance`() {
        val trips = parquet("trips.parquet", "SELECT 1 AS id UNION ALL SELECT 2")
        val ds = lakeDatasource("lake_shared", dialect = mapOf("memory_limit" to "256MB", "threads" to "2"))

        poolManager({ listOf(table("hvfhv_trips", trips)) }).use { manager ->
            val pool = manager.poolFor(ds)
            pool.leaseConnection().use { a ->
                pool.leaseConnection().use { b ->
                    // A catalog object created on one connection is visible on the other: they
                    // share the instance. (Not a view from the plan — a marker made NOW.)
                    a.createStatement().use { it.execute("CREATE TABLE nyc.mobility.marker AS SELECT 99 AS v") }
                    assertAll(
                        { physicalId(a) shouldNotBe physicalId(b) },
                        { countOf(b, "SELECT count(*) FROM hvfhv_trips") shouldBe 2 },
                        { countOf(b, "SELECT v FROM marker") shouldBe 99 },
                        // Engine limits were applied ONCE, on the owner, and are instance-global.
                        { settingOf(a, "memory_limit") shouldBe settingOf(b, "memory_limit") },
                        { settingOf(b, "threads") shouldBe "2" },
                        { settingOf(b, "preserve_insertion_order") shouldBe "false" },
                        // The adapter's security posture reached the instance through the
                        // driver properties the owner was opened with.
                        { settingOf(b, "allow_community_extensions") shouldBe "false" },
                        { settingOf(b, "autoinstall_known_extensions") shouldBe "false" },
                        // The search-path session init ran on BOTH connections.
                        { countOf(a, "SELECT count(*) FROM hvfhv_trips") shouldBe 2 },
                    )
                }
            }
        }
    }

    @Test
    @Suppress("NestedBlockDepth") // nested `use` blocks are the leases' lifetimes — flattening would hide them
    fun `two datasources on the same anonymous URL never share an instance`() {
        val a = parquet("a.parquet", "SELECT 1 AS id")
        val b = parquet("b.parquet", "SELECT 1 AS id UNION ALL SELECT 2 UNION ALL SELECT 3")
        val rowsByName =
            mapOf(
                "lake_a" to listOf(table("only_a", a)),
                "lake_b" to listOf(table("only_b", b)),
            )
        val manager =
            ConnectionPoolManager(poolFactory = { datasource ->
                ConnectionPoolManager.buildHikariPool(
                    datasource,
                    lakeViews =
                        LakeViewInit(
                            datasource.name,
                            LakeViewStatements.planForTables(rowsByName.getValue(datasource.name), adapter),
                            LakeViewOutcomeRecorder.NONE,
                        ),
                )
            })

        manager.use {
            manager.poolFor(lakeDatasource("lake_a")).leaseConnection().use { ca ->
                manager.poolFor(lakeDatasource("lake_b")).leaseConnection().use { cb ->
                    ca.createStatement().use { it.execute("CREATE TABLE nyc.mobility.marker_a AS SELECT 1 AS v") }
                    assertAll(
                        { countOf(ca, "SELECT count(*) FROM only_a") shouldBe 1 },
                        { countOf(cb, "SELECT count(*) FROM only_b") shouldBe 3 },
                        { runsSql(cb, "SELECT count(*) FROM only_a") shouldBe false },
                        { runsSql(ca, "SELECT count(*) FROM only_b") shouldBe false },
                        { runsSql(cb, "SELECT v FROM marker_a") shouldBe false },
                    )
                }
            }
        }
    }

    @Test
    fun `a registry change rebuilds a NEW generation - the in-flight old reader keeps its snapshot`() {
        val trips = parquet("trips.parquet", "SELECT 1 AS id")
        val zones = parquet("zones.parquet", "SELECT 'Manhattan' AS zone")
        val rows = mutableListOf(table("hvfhv_trips", trips))
        var now = Instant.parse("2026-09-16T00:00:00Z")
        val ds = lakeDatasource("lake_generations")

        poolManager({ rows.toList() }, clock = { now }).use { manager ->
            val oldPool = manager.poolFor(ds) as HikariConnectionPool
            val oldReader = oldPool.leaseConnection()
            countOf(oldReader, "SELECT count(*) FROM hvfhv_trips") shouldBe 1

            // The registry changes; the pool retires (§5.2) but the old reader is still out.
            rows += table("hvfhv_zone_day", zones)
            manager.retire(ds.name) shouldBe true
            manager.reapRetiring().closed shouldBe 0
            val newPool = manager.poolFor(ds) as HikariConnectionPool
            newPool shouldNotBe oldPool

            newPool.leaseConnection().use { newReader ->
                newReader.createStatement().use { it.execute("CREATE TABLE nyc.mobility.marker_new AS SELECT 1 AS v") }
                assertAll(
                    // New generation, new snapshot: the new table is there…
                    { countOf(newReader, "SELECT count(*) FROM hvfhv_zone_day") shouldBe 1 },
                    // …and the old generation still is not — the reader keeps what it was built with.
                    { runsSql(oldReader, "SELECT count(*) FROM hvfhv_zone_day") shouldBe false },
                    // The two generations do not share mutable catalog state either way.
                    { runsSql(oldReader, "SELECT v FROM marker_new") shouldBe false },
                    // The old instance is alive for the in-flight reader: retirement did not
                    // close the owner.
                    { countOf(oldReader, "SELECT count(*) FROM hvfhv_trips") shouldBe 1 },
                )
            }

            // The old reader returns; the next reap drains and closes the old generation —
            // owner included (LakeInstanceOwnerTest pins the close order).
            oldReader.close()
            manager.reapRetiring().drained shouldBe 1
            oldPool.isClosed shouldBe true
            newPool.isClosed shouldBe false

            // The ceiling path: a generation with a lease still out is hard-closed at its
            // deadline — the existing abandonment policy, the owner released at the same boundary.
            val stuck = newPool.leaseConnection()
            manager.retire(ds.name) shouldBe true
            now = now.plus(ConnectionPoolManager.DEFAULT_RETIRE_CEILING).plusSeconds(1)
            manager.reapRetiring().hardClosed shouldBe 1
            newPool.isClosed shouldBe true
            runCatching { stuck.close() }
        }
    }

    @Test
    fun `a recorder that throws does not fail the build - the healthy view still serves`() {
        val trips = parquet("trips.parquet", "SELECT 1 AS id")
        val broken = tempDir.resolve("broken.parquet").apply { writeText("not parquet") }
        val rows = listOf(table("hvfhv_trips", trips), table("hvfhv_broken", broken))
        val recorder = LakeViewOutcomeRecorder { _, _, _, _ -> throw IllegalStateException("registry write failed") }

        poolManager({ rows }, recorder).use { manager ->
            manager.poolFor(lakeDatasource("lake_recorder")).leaseConnection().use { connection ->
                assertAll(
                    { countOf(connection, "SELECT count(*) FROM hvfhv_trips") shouldBe 1 },
                    { runsSql(connection, "SELECT count(*) FROM hvfhv_broken") shouldBe false },
                )
            }
        }
    }

    @Test
    fun `a pool whose first physical connection fails leaves no owner behind`() {
        // Pre-existing edge, used here as the deterministic trigger: a single-namespace registry
        // whose ONLY table is emission-refused creates no schema but still emits the search-path
        // postlude, so the session init of the first physical connection fails and Hikari's
        // fail-fast construction throws — after the owner was opened. The build must close it.
        val trips = parquet("trips.parquet", "SELECT 1 AS id")
        val rows = listOf(table("unmappable", trips, namespace = listOf("a", "b", "c")))

        val lifecycle =
            capturingLogs {
                poolManager({ rows }).use { manager ->
                    val failure = shouldThrow<Exception> { manager.poolFor(lakeDatasource("lake_first_fails")) }
                    assertAll(
                        // The postlude `SET search_path = 'a.b.c'` is what the engine refuses.
                        { rootMessage(failure) shouldContain "Too many dots" },
                        { manager.hasPool("lake_first_fails") shouldBe false },
                    )
                }
            }
        // The owner's own lifecycle lines are the witness: opened once, closed once, for this
        // datasource — no generation survives a pool that was never built.
        val events =
            lifecycle
                .filter { it.contains("datasource=lake_first_fails") && it.startsWith("event=lake.instance_") }
                .map { it.substringBefore(" ") }
        events shouldBe listOf("event=lake.instance_opened", "event=lake.instance_closed")
    }

    @Test
    @Suppress("NestedBlockDepth") // nested `use` blocks are the leases' lifetimes — flattening would hide them
    fun `cancelling one connection's query leaves its sibling and its own next statement healthy`() {
        val ds = lakeDatasource("lake_cancel", dialect = mapOf("threads" to "1"))

        ConnectionPoolManager.buildHikariPool(ds).use { pool ->
            pool.leaseConnection().use { victim ->
                pool.leaseConnection().use { sibling ->
                    val slow = victim.createStatement()
                    val canceller =
                        Thread {
                            Thread.sleep(CANCEL_AFTER_MS)
                            slow.cancel()
                        }.apply { start() }
                    val interrupted =
                        shouldThrow<SQLException> {
                            slow.executeQuery("SELECT sum(i) FROM range(4000000000) t(i)").use { it.next() }
                        }
                    canceller.join()
                    assertAll(
                        { interrupted.message.orEmpty() shouldContain "INTERRUPT" },
                        { countOf(sibling, "SELECT 10") shouldBe 10 },
                        { countOf(victim, "SELECT 7") shouldBe 7 },
                    )
                }
            }
        }
    }

    @Test
    fun `the memory limit is ONE shared budget - an over-budget query fails honestly and the sibling survives`() {
        val ds = lakeDatasource("lake_budget", dialect = mapOf("memory_limit" to "10MB", "threads" to "2"))

        ConnectionPoolManager.buildHikariPool(ds).use { pool ->
            pool.leaseConnection().use { heavy ->
                pool.leaseConnection().use { light ->
                    val oom =
                        shouldThrow<SQLException> {
                            heavy.createStatement().use { st ->
                                st.executeQuery("SELECT count(DISTINCT i) FROM range(30000000) t(i)").use { it.next() }
                            }
                        }
                    assertAll(
                        { oom.message.orEmpty() shouldContain "Out of Memory" },
                        // One budget, read from either side — not two private 10 MB budgets.
                        { settingOf(light, "memory_limit") shouldBe settingOf(heavy, "memory_limit") },
                        { countOf(light, "SELECT count(*) FROM range(1000)") shouldBe 1000 },
                        { countOf(heavy, "SELECT 1") shouldBe 1 },
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Formatted messages logged while [block] ran — the shape `ConnectionPoolManagerTest` uses. */
    private fun capturingLogs(block: () -> Unit): List<String> {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            block()
        } finally {
            root.detachAppender(appender)
            appender.stop()
        }
        return appender.list.map { it.formattedMessage }
    }

    private fun physicalId(connection: Connection): Int = System.identityHashCode(connection.unwrap(Connection::class.java))

    private fun rootMessage(e: Throwable): String = generateSequence(e) { it.cause }.last().message.orEmpty()

    private fun settingOf(
        connection: Connection,
        name: String,
    ): String =
        connection.createStatement().use { st ->
            st.executeQuery("SELECT current_setting('$name')").use { rs ->
                rs.next() shouldBe true
                rs.getString(1)
            }
        }

    private fun countOf(
        connection: Connection,
        sql: String,
    ): Int =
        connection.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                rs.next() shouldBe true
                rs.getInt(1)
            }
        }

    private fun runsSql(
        connection: Connection,
        sql: String,
    ): Boolean =
        try {
            connection.createStatement().use { it.executeQuery(sql).close() }
            true
        } catch (_: SQLException) {
            false
        }

    private companion object {
        const val CANCEL_AFTER_MS = 300L
    }
}
