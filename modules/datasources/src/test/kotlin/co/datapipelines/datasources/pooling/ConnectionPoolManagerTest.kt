package co.datapipelines.datasources.pooling

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.datasources.CredentialKind
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceErrorCodes
import co.datapipelines.datasources.DatasourceFileRoots
import co.datapipelines.datasources.DatasourceProperties
import co.datapipelines.datasources.Fixtures
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [ConnectionPoolManager] concurrency and lifecycle (datasources.md §5.2, §13.2).
 *
 * The load-bearing property: N threads calling `poolFor` at once for a cold datasource must
 * construct **exactly one** pool. `computeIfAbsent` is the mechanism; this test is the proof.
 *
 * The second is the RETIREMENT state machine (094). A dropped pool must eventually be **closed**
 * (a dropped-but-open pool leaks its connections and its source-DB slots for the life of the
 * process) and it must be removed from the map **before** anything is done to it, so an
 * in-flight caller never receives a pool that is already closing — but it must NOT be closed
 * while a statement is still running on it, which is the defect 094 exists to fix. The three
 * states are therefore asserted separately: retired-and-draining (still open, out of the map),
 * retired-and-drained (closed by the reaper), and retired-past-its-ceiling (closed anyway, with
 * the hard-close metric).
 */
class ConnectionPoolManagerTest {
    /**
     * A pool that constructs nothing real — the test counts factory invocations, close calls and
     * soft-evictions, not sockets. [closedWhileStillReachable] is the ordering probe: `close()`
     * asks the manager whether the map still points at this pool, which is exactly what §5.2
     * forbids. [active] is the reaper's input, set by the test to stand in for a running query.
     */
    private class FakePool(
        override val name: String,
        var active: Int = 0,
        private val manager: () -> ConnectionPoolManager? = { null },
    ) : ConnectionPool {
        var closed: Boolean = false
            private set
        var closedWhileStillReachable: Boolean = false
            private set
        var softEvicted: Boolean = false
            private set

        override fun leaseConnection(): Connection = error("not used in this test")

        override fun softEvict() {
            softEvicted = true
        }

        override val activeConnections: Int get() = active

        override fun close() {
            closedWhileStillReachable = manager()?.hasPool(name) ?: false
            closed = true
        }
    }

    /** A recording [PoolLifecycleMetrics] — never a strict mock: a missing call must be VISIBLE. */
    private class RecordingMetrics : PoolLifecycleMetrics {
        val retired = mutableListOf<String>()
        val hardClosed = mutableListOf<Pair<String, Int>>()

        override fun poolRetired(datasourceName: String) {
            retired += datasourceName
        }

        override fun poolHardClosed(
            datasourceName: String,
            activeConnections: Int,
        ) {
            hardClosed += datasourceName to activeConnections
        }
    }

    @Test
    fun `N concurrent poolFor calls for a cold datasource build exactly one pool`() {
        val builds = AtomicInteger(0)
        val manager =
            ConnectionPoolManager({ datasource ->
                builds.incrementAndGet()
                // Widen the race window so a non-atomic implementation would double-build.
                Thread.sleep(WIDEN_WINDOW_MS)
                FakePool(datasource.name)
            })
        val datasource = Fixtures.h2(name = "race_ds")
        val threads = 32
        val startGate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)

        try {
            val results =
                (1..threads).map {
                    pool.submit<ConnectionPool> {
                        startGate.await()
                        manager.poolFor(datasource)
                    }
                }
            startGate.countDown()
            val pools = results.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }

            builds.get() shouldBe 1
            // Every caller received the very same instance.
            pools.distinctBy { System.identityHashCode(it) }.size shouldBe 1
            manager.hasPool("race_ds") shouldBe true
        } finally {
            pool.shutdownNow()
            manager.close()
        }
    }

    @Test
    fun `retire drops the pool so the next poolFor rebuilds a fresh instance`() {
        val builds = AtomicInteger(0)
        val manager =
            ConnectionPoolManager({ datasource ->
                builds.incrementAndGet()
                FakePool(datasource.name)
            })
        val datasource = Fixtures.h2(name = "rebuild_ds")

        val first = manager.poolFor(datasource)
        manager.retire("rebuild_ds") shouldBe true
        manager.hasPool("rebuild_ds") shouldBe false
        val second = manager.poolFor(datasource)

        builds.get() shouldBe 2
        System.identityHashCode(first) shouldNotBe System.identityHashCode(second)
        manager.close()
    }

    @Test
    fun `retire soft-evicts and leaves the pool OPEN while a statement is still running on it`() {
        // The whole point of 094: the old `evict` closed here, and HikariCP then aborted the
        // in-use connection after its shutdown grace — killing a mid-query execution.
        lateinit var manager: ConnectionPoolManager
        val metrics = RecordingMetrics()
        manager =
            ConnectionPoolManager(
                { datasource -> FakePool(datasource.name, active = 1) { manager } },
                metrics = metrics,
            )
        val pool = manager.poolFor(Fixtures.h2(name = "busy_ds")) as FakePool

        manager.retire("busy_ds") shouldBe true

        pool.softEvicted shouldBe true
        pool.closed shouldBe false
        manager.hasPool("busy_ds") shouldBe false
        manager.retiringCount() shouldBe 1
        metrics.retired shouldBe listOf("busy_ds")

        // One reap tick with the statement still running changes nothing.
        manager.reapRetiring() shouldBe ReapOutcome.NOTHING
        pool.closed shouldBe false

        // The statement finishes; the next tick closes the pool, and only after it left the map.
        pool.active = 0
        manager.reapRetiring() shouldBe ReapOutcome(drained = 1, hardClosed = 0)
        pool.closed shouldBe true
        pool.closedWhileStillReachable shouldBe false
        manager.retiringCount() shouldBe 0
        metrics.hardClosed shouldBe emptyList()
    }

    @Test
    fun `a drained pool is closed on the very first reap tick`() {
        val manager = ConnectionPoolManager({ datasource -> FakePool(datasource.name, active = 0) })
        val pool = manager.poolFor(Fixtures.h2(name = "idle_ds")) as FakePool

        manager.retire("idle_ds") shouldBe true
        manager.reapRetiring() shouldBe ReapOutcome(drained = 1, hardClosed = 0)

        pool.closed shouldBe true
    }

    @Test
    fun `the ceiling closes a pool whose statement outlived it, and counts it as a hard close`() {
        // A hung statement must not pin a deleted datasource's pool forever (§5.2).
        var now = Instant.parse("2026-09-07T10:00:00Z")
        val metrics = RecordingMetrics()
        val manager =
            ConnectionPoolManager(
                { datasource -> FakePool(datasource.name, active = 3) },
                retireCeiling = Duration.ofSeconds(CEILING_SECONDS),
                metrics = metrics,
                clock = { now },
            )
        val pool = manager.poolFor(Fixtures.h2(name = "hung_ds")) as FakePool
        manager.retire("hung_ds") shouldBe true

        // One second before the ceiling: still waiting, still open.
        now = now.plusSeconds(CEILING_SECONDS - 1)
        manager.reapRetiring() shouldBe ReapOutcome.NOTHING
        pool.closed shouldBe false

        // At the ceiling: closed regardless, with the active count carried into the metric AND
        // into one WARN. The log line is the operator's only account of a query that just lost
        // its connection, so it is asserted, not assumed — observability.md §3.4A catalogues it.
        now = now.plusSeconds(1)
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            manager.reapRetiring() shouldBe ReapOutcome(drained = 0, hardClosed = 1)
        } finally {
            root.detachAppender(appender)
            appender.stop()
        }

        pool.closed shouldBe true
        metrics.hardClosed shouldBe listOf("hung_ds" to 3)
        manager.retiringCount() shouldBe 0

        val warnings = appender.list.filter { it.level == Level.WARN }
        val line = warnings.single { it.formattedMessage.contains("datasource.pool_hard_closed") }
        line.formattedMessage shouldContain "datasource=hung_ds"
        line.formattedMessage shouldContain "active_connections=3"
        line.formattedMessage shouldContain "ceiling_seconds=90"
    }

    @Test
    fun `two pools for the same datasource can retire at once and both are closed`() {
        // save → lease → save again: the first pool is still draining when the second retires.
        // A `retiring` map keyed by NAME would drop the first one un-closed; the queue does not.
        val manager = ConnectionPoolManager({ datasource -> FakePool(datasource.name, active = 1) })
        val datasource = Fixtures.h2(name = "twice_ds")

        val first = manager.poolFor(datasource) as FakePool
        manager.retire("twice_ds") shouldBe true
        val second = manager.poolFor(datasource) as FakePool
        manager.retire("twice_ds") shouldBe true

        manager.retiringCount() shouldBe 2
        first.active = 0
        second.active = 0
        manager.reapRetiring() shouldBe ReapOutcome(drained = 2, hardClosed = 0)
        first.closed shouldBe true
        second.closed shouldBe true
    }

    @Test
    fun `retiring a datasource with no pool is a no-op and reports it`() {
        val manager = ConnectionPoolManager({ datasource -> FakePool(datasource.name) })

        manager.retire("never_built") shouldBe false
        manager.retiringCount() shouldBe 0
    }

    @Test
    fun `reaping with nothing retired is free and reports nothing`() {
        val manager = ConnectionPoolManager({ datasource -> FakePool(datasource.name) })

        manager.reapRetiring() shouldBe ReapOutcome.NOTHING
    }

    @Test
    fun `close closes every pool, live and still draining`() {
        val manager = ConnectionPoolManager({ datasource -> FakePool(datasource.name, active = 1) })
        val first = manager.poolFor(Fixtures.h2(name = "a")) as FakePool
        val second = manager.poolFor(Fixtures.h2(name = "b")) as FakePool
        // `a` is mid-retirement and would NOT be reaped (its statement is still running);
        // shutdown closes it anyway — the drain lifecycle has already cancelled the statements.
        manager.retire("a") shouldBe true

        manager.close()

        first.closed shouldBe true
        second.closed shouldBe true
        manager.hasPool("b") shouldBe false
        manager.retiringCount() shouldBe 0
    }

    @Test
    fun `livePoolNames reports exactly the pools the reconcile may compare`() {
        val manager = ConnectionPoolManager({ datasource -> FakePool(datasource.name) })
        manager.poolFor(Fixtures.h2(name = "live_a"))
        manager.poolFor(Fixtures.h2(name = "live_b"))
        manager.retire("live_a")

        manager.livePoolNames() shouldBe setOf("live_b")
        manager.close()
    }

    @Test
    fun `a real Hikari pool is soft-evicted on retire and actually closed by the reaper`() {
        // The FakePool cases prove the manager's ordering; this one proves the thing it drives is
        // a live HikariDataSource — soft-evict leaves it open for the caller that already holds a
        // connection, and the reaper's close() flips isClosed.
        val manager = ConnectionPoolManager()
        val datasource = Fixtures.h2(name = "real_hikari")

        val pool = manager.poolFor(datasource) as HikariConnectionPool
        val leased = pool.leaseConnection()

        manager.retire("real_hikari") shouldBe true
        // A connection still out: the pool reports it, so the reaper leaves the pool alone and
        // the running statement keeps its connection.
        pool.activeConnections shouldBe 1
        manager.reapRetiring() shouldBe ReapOutcome.NOTHING
        pool.isClosed shouldBe false
        leased.isClosed shouldBe false

        // The caller returns it; Hikari closes the evicted entry, and the next tick reaps.
        leased.close()
        pool.activeConnections shouldBe 0
        manager.reapRetiring() shouldBe ReapOutcome(drained = 1, hardClosed = 0)
        pool.isClosed shouldBe true
    }

    @ParameterizedTest
    @ValueSource(strings = ["H2", "DUCKDB", "SQLITE"])
    fun `a bound row whose file now resolves outside every root does not build a pool - per in-process form`(dialectName: String) {
        // #204 L4: the roots check is a property of the BUILD. A row saved while its file sat
        // under a declared root is refused the moment the row's world moves out from under it —
        // here by the root list narrowing after the save — with the same catalogued refusal the
        // update gate throws, and builds again once the row is under a root again. Parameterised
        // over all three in-process file forms so the plain-pool branch (DuckDB, SQLite) is
        // proven, not only H2's.
        //
        // The file-replaced-by-an-outside-symlink shape (the L5 escape, re-checked here at the
        // build) is exercised where the driver opens the URL's exact path — DuckDB and SQLite.
        // H2 derives its own file name: ConnectionInfo.getDatabaseName (h2-2.3.232 :467) appends
        // `.mv.db` UNCONDITIONALLY, so the URL path `/roots/data` never names the file the
        // driver opens (`/roots/data.mv.db`) and a symlink ON the URL path is not the file's
        // escape route for this dialect (see the handback's H2-suffix note).
        val dialect = Dialect.valueOf(dialectName)
        val rootsDir = Files.createTempDirectory("dp-roots-l4")
        val outsideDir = Files.createTempDirectory("dp-outside-l4")
        val dbFile =
            rootsDir.resolve(
                when (dialect) {
                    Dialect.H2 -> "data"
                    Dialect.DUCKDB -> "data.duckdb"
                    else -> "data.db"
                },
            )
        val datasource = fileRow(dialect, dbFile)
        val fileRoots = DatasourceFileRoots(listOf(rootsDir))
        val manager =
            ConnectionPoolManager(poolFactory = { ds ->
                ConnectionPoolManager.buildHikariPool(ds, fileRoots = fileRoots)
            })

        fun refusedAssertion(thrown: DatapipelinesException) {
            assertAll(
                { thrown.code shouldBe DatasourceErrorCodes.WORKSPACE_FORBIDDEN },
                { thrown.message.orEmpty() shouldContain "file root" },
                // Non-vacuity: the refusal names the rule, never the outside path's component.
                { thrown.message.orEmpty() shouldNotContain "secret.db" },
            )
        }

        try {
            // The admitted half: a real file under the root builds.
            manager.poolFor(datasource)
            manager.hasPool(datasource.name) shouldBe true
            manager.retire(datasource.name) shouldBe true
            manager.reapRetiring() shouldBe ReapOutcome(drained = 1, hardClosed = 0)

            // (1) The root list narrowed after the row was saved — every form refuses.
            val narrowedRoots = DatasourceFileRoots(listOf(Files.createTempDirectory("dp-roots-l4-narrow")))
            val narrowed =
                ConnectionPoolManager(poolFactory = { ds ->
                    ConnectionPoolManager.buildHikariPool(ds, fileRoots = narrowedRoots)
                })
            try {
                refusedAssertion(shouldThrow { narrowed.poolFor(datasource) })
                narrowed.hasPool(datasource.name) shouldBe false // the map has no entry for it
            } finally {
                narrowed.close()
            }

            // (2) The driver's own file replaced by a symlink aimed outside every root.
            if (dialect != Dialect.H2) {
                Files.delete(dbFile)
                val outsideTarget = outsideDir.resolve("secret.db").also { it.toFile().writeText("x") }
                Files.createSymbolicLink(dbFile, outsideTarget)
                refusedAssertion(shouldThrow { manager.poolFor(datasource) })
                manager.hasPool(datasource.name) shouldBe false
                Files.delete(dbFile)
            }

            // The row's world restored (its root re-declared; the symlink gone): builds again.
            manager.poolFor(datasource)
            manager.hasPool(datasource.name) shouldBe true
        } finally {
            manager.close()
        }
    }

    /** A credential-less file-backed row — the shape a super admin registers for an embedded engine. */
    private fun fileRow(
        dialect: Dialect,
        dbFile: Path,
    ) = Datasource(
        name = "l4_${dialect.wire.lowercase()}",
        displayName = "L4 file row",
        dialect = dialect,
        jdbcUrl =
            when (dialect) {
                Dialect.H2 -> "jdbc:h2:file:$dbFile"
                Dialect.DUCKDB -> "jdbc:duckdb:$dbFile"
                else -> "jdbc:sqlite:$dbFile"
            },
        username = null,
        credentialKind = CredentialKind.NONE,
        secret = null,
        properties = DatasourceProperties(),
    )

    @Test
    fun `a rotated restricted password is repaired at the next build - the pool is evicted and rebuilt, not leaked`() {
        // #204 L2: a file database's restricted password rotated by ANOTHER builder (a second
        // instance, a restore) leaves the warm connections working but every post-build
        // creation failing with H2's 28000. The failing lease must evict the pool so the next
        // acquisition rebuilds it through build — whose bootstrap re-rotates the password —
        // and the evicted pool's connections must be closed, not leaked.
        //
        // Growth is forced by softEvict: it retires the pool's one warm connection, so the next
        // lease asks Hikari to CREATE — exactly the post-build creation path a maxLifetime
        // replacement rides in production. The tiny connectionTimeout keeps the failing borrow
        // bounded; HikariCP 6.3.3's createTimeoutException copies the driver's SQLState onto
        // the timeout exception and chains it via nextException, which is what makes the 28000
        // observable here at all.
        val scratch = Files.createTempDirectory("dp-l2-pool")
        val dbFile = scratch.resolve("rotate-l2") // no suffix: H2 appends .mv.db (ConnectionInfo :467)
        val datasource =
            Datasource(
                name = "l2_rotate",
                displayName = "L2 rotate",
                dialect = Dialect.H2,
                jdbcUrl = "jdbc:h2:file:$dbFile",
                username = null,
                credentialKind = CredentialKind.NONE,
                secret = null,
                properties =
                    DatasourceProperties(
                        hikari =
                            mapOf(
                                "maximumPoolSize" to 1,
                                "minimumIdle" to 1,
                                "connectionTimeout" to 500,
                            ),
                    ),
            )
        lateinit var manager: ConnectionPoolManager
        manager =
            ConnectionPoolManager(poolFactory = { ds ->
                // The production registry's exact wiring (DefaultDatasourceRegistry's factory).
                ConnectionPoolManager.buildHikariPool(
                    ds,
                    onRestrictedAuthFailure = { manager.retire(ds.name) },
                )
            })

        try {
            val first = manager.poolFor(datasource) as HikariConnectionPool
            first.leaseConnection().close() // the warm connection, authenticated with generation 1's password

            // An outside builder rotates the restricted user's password out from under the pool.
            rotateRestrictedPassword(dbFile, "rotated-by-outsider")

            first.softEvict() // the warm connection is gone; the next lease forces a CREATE
            val failed = shouldThrow<SQLException> { first.leaseConnection() }
            // The observed failure, recorded: Hikari's timeout exception carrying H2's 28000
            // (the pinned createTimeoutException copies sqlState/errorCode from the failed
            // create and chains the driver exception via nextException).
            failed.sqlState shouldBe "28000"

            // The next acquisition: the pool was evicted on the failure, so this REBUILDS.
            val second = manager.poolFor(datasource) as HikariConnectionPool
            System.identityHashCode(first) shouldNotBe System.identityHashCode(second)
            second.leaseConnection().use { connection -> plainSelect(connection) }

            // The evicted generation is closed, not leaked (the security section's assertion).
            manager.reapRetiring() shouldBe ReapOutcome(drained = 1, hardClosed = 0)
            first.isClosed shouldBe true
            manager.hasPool(datasource.name) shouldBe true
        } finally {
            manager.close()
        }
    }

    /** One plain SELECT — the rebuilt pool's proof of life (extracted: detekt's nesting floor). */
    private fun plainSelect(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT 1").use { rows -> rows.next() }
        }
    }

    /** The outside-builder act: the file's OWNER re-passwords the restricted user. */
    private fun rotateRestrictedPassword(
        dbFile: Path,
        newPassword: String,
    ) {
        java.sql.DriverManager.getConnection("jdbc:h2:file:$dbFile", "sa", "").use { owner ->
            owner.createStatement().use {
                it.execute(
                    "ALTER USER ${H2InProcessPool.RESTRICTED_USER} SET PASSWORD '$newPassword'",
                )
            }
        }
    }

    private companion object {
        const val WIDEN_WINDOW_MS = 25L
        const val TIMEOUT_SECONDS = 30L
        const val CEILING_SECONDS = 90L
    }
}
