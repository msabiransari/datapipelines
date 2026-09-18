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
        // The deterministic trigger is a session init the engine refuses: a search path naming
        // a schema that does not exist (the engine answers `No catalog + schema named …`).
        // Pre-#129 `planForTables` itself produced such a postlude for an all-refused
        // single-namespace registry; with that fixed the plan is hand-built — what this test
        // pins is the owner-cleanup rule for a failed first connection, not the generator.
        val plan =
            LakeViewPlan(
                prelude = emptyList(),
                views = emptyList(),
                postlude = listOf("SET search_path = 'missing.missing'"),
            )
        val manager =
            ConnectionPoolManager(
                poolFactory = { datasource ->
                    ConnectionPoolManager.buildHikariPool(
                        datasource,
                        lakeViews =
                            LakeViewInit(datasource.name, plan, LakeViewOutcomeRecorder.NONE),
                    )
                },
            )

        val lifecycle =
            capturingLogs {
                manager.use {
                    val failure = shouldThrow<Exception> { manager.poolFor(lakeDatasource("lake_first_fails")) }
                    assertAll(
                        // The session init `SET search_path = 'missing.missing'` is what the engine refuses.
                        { rootMessage(failure) shouldContain "No catalog + schema named" },
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

    /**
     * Cancellation isolation on one shared instance, synchronised on ENGINE ENTRY, not on time
     * and not on the call site: each statement's scan starts with a `read_parquet` of its own
     * loopback file, and the server's first request for that file is the engine's own signal
     * that it has bound and begun executing the statement (a call-site latch, the earlier
     * version, only proved the threads had reached `executeQuery`). The cancel is issued once
     * BOTH engines' requests have arrived; the victim's `INTERRUPT` proves it was mid-execution
     * (a finished statement cannot be interrupted), and the sibling — whose statement entered
     * the engine before the cancel and, by its own timestamp, returned after it — ran through
     * the cancel with the right answer. The victim's next statement proves reuse.
     */
    @Test
    @Suppress("NestedBlockDepth") // nested `use` blocks are the leases' lifetimes — flattening would hide them
    fun `cancelling one running query leaves the concurrently running sibling and the victim's next statement healthy`() {
        val victimFile = parquet("victim.parquet", "SELECT 1 AS id")
        val siblingFile = parquet("sibling.parquet", "SELECT 1 AS id")
        val victimEntered = java.util.concurrent.CountDownLatch(1)
        val siblingEntered = java.util.concurrent.CountDownLatch(1)
        val ds =
            lakeDatasource(
                "lake_cancel",
                dialect =
                    mapOf(
                        "catalog.kind" to "s3",
                        "region" to "us-east-1",
                        "unsigned" to "true",
                        "threads" to "2",
                    ),
            )
        val expectedSum = (LONG_SCAN_ROWS / 2) * (LONG_SCAN_ROWS - 1) // halve first: n·(n−1) overflows a Long

        CountingParquetServer(victimFile, onRequest = { victimEntered.countDown() }).use { victimServer ->
            CountingParquetServer(siblingFile, onRequest = { siblingEntered.countDown() }).use { siblingServer ->
                ConnectionPoolManager.buildHikariPool(ds).use { pool ->
                    pool.leaseConnection().use { victim ->
                        pool.leaseConnection().use { sibling ->
                            val victimStatement = victim.createStatement()
                            val victimOutcome = scanOnThread(victimStatement, longScan(victimServer.url))
                            val siblingOutcome = scanOnThread(sibling.createStatement(), longScan(siblingServer.url))

                            withClue("both statements entered the engine (each engine fetched its file)") {
                                victimEntered.await(SCAN_WAIT_S, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
                                siblingEntered.await(SCAN_WAIT_S, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
                            }
                            val cancelledAt = System.nanoTime()
                            victimStatement.cancel()
                            val interrupted = victimOutcome.get(SCAN_WAIT_S, java.util.concurrent.TimeUnit.SECONDS).exceptionOrNull()
                            val (siblingSum, siblingFinishedAt) =
                                siblingOutcome.get(SCAN_WAIT_S, java.util.concurrent.TimeUnit.SECONDS).getOrThrow()

                            assertAll(
                                {
                                    withClue("the victim was running and was interrupted") {
                                        interrupted?.message.orEmpty() shouldContain
                                            "INTERRUPT"
                                    }
                                },
                                {
                                    withClue("the sibling was still running when the cancel was issued") {
                                        (siblingFinishedAt > cancelledAt) shouldBe
                                            true
                                    }
                                },
                                { withClue("the sibling ran through beside the cancel") { siblingSum shouldBe expectedSum } },
                                { countOf(victim, "SELECT 7") shouldBe 7 },
                                { countOf(sibling, "SELECT 10") shouldBe 10 },
                            )
                        }
                    }
                }
            }
        }
    }

    /** Runs [sql] on [statement] on its own thread: the sum and the nanoTime it returned, or the failure. */
    private fun scanOnThread(
        statement: java.sql.Statement,
        sql: String,
    ): java.util.concurrent.CompletableFuture<Result<Pair<Long, Long>>> {
        val outcome = java.util.concurrent.CompletableFuture<Result<Pair<Long, Long>>>()
        Thread {
            outcome.complete(
                runCatching {
                    val sum =
                        statement.executeQuery(sql).use { rs ->
                            rs.next()
                            rs.getLong(1)
                        }
                    sum to System.nanoTime()
                },
            )
        }.start()
        return outcome
    }

    /** A statement that touches [url] first (engine entry, observable at the server) and then scans for seconds. */
    private fun longScan(url: String) =
        "SELECT sum(i) FROM range($LONG_SCAN_ROWS) t(i) WHERE (SELECT count(*) FROM read_parquet('$url')) = 1"

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

    @Test
    fun `the strict and the isolated compositions cannot be passed together`() {
        val trips = parquet("trips.parquet", "SELECT 1 AS id")
        val rows = listOf(table("hvfhv_trips", trips))

        val refusal =
            shouldThrow<IllegalArgumentException> {
                ConnectionPoolManager.buildHikariPool(
                    lakeDatasource("lake_both"),
                    additionalConnectionInit = LakeViewStatements.forTables(rows, adapter),
                    lakeViews = LakeViewInit("lake_both", LakeViewStatements.planForTables(rows, adapter), LakeViewOutcomeRecorder.NONE),
                )
            }
        refusal.message.orEmpty() shouldContain "pass exactly one"
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
        /** Long enough that the scan is still running when the cancel arrives, on any box (≈2.5 s at 2 threads on the dev box). */
        const val LONG_SCAN_ROWS = 2_000_000_000L
        const val SCAN_WAIT_S = 60L
    }
}
