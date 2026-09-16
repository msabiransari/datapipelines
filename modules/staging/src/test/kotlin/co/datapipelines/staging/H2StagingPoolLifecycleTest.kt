package co.datapipelines.staging

import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The lifecycle, failure-ownership and privilege invariants the pool adds to [H2Staging]
 * (staging.md §3.4, §6, §9.5; #118): close against an open cursor, creation failure partway
 * through, a database that vanishes under a lease, a partial table whose cleanup refuses, a
 * stage cancelled mid-drain at capacity one, bounded buffering of child rows, and the
 * restricted identity on every physical connection — including ones opened after bootstrap.
 */
class H2StagingPoolLifecycleTest {
    private val props = H2StagingProperties()

    @Test
    fun `close with an open cursor returns at once, the cursor finishes, and the database then dies`() {
        val executionId = UUID.randomUUID()
        val staging = H2StagingFactory(props).create(executionId)
        runBlocking { staging.execute("CREATE TABLE \"stg_open\" (\"id\" INTEGER)") }
        runBlocking { staging.execute("INSERT INTO \"stg_open\" VALUES (1), (2), (3)") }
        val draining = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val drained = AtomicInteger()

        DriverManager.getConnection(stagingUrl(executionId, props), "sa", "").use { observer ->
            runBlocking {
                val reader =
                    async(Dispatchers.IO) {
                        staging.withQuery("SELECT \"id\" FROM \"stg_open\"") { rs ->
                            rs.next()
                            drained.incrementAndGet()
                            draining.complete(Unit)
                            release.await()
                            // The cursor is still live after close(): nothing touched its connection.
                            while (rs.next()) drained.incrementAndGet()
                        }
                    }
                withTimeout(TIMEOUT_MS) { draining.await() }

                val started = System.nanoTime()
                staging.close()
                ((System.nanoTime() - started) / 1_000_000 < TIMEOUT_MS) shouldBe true
                // Closed to new work, but the database is alive — the quarantined lease holds it.
                shouldThrow<IllegalStateException> { staging.execute("SELECT 1") }
                execUserCount(observer) shouldBe 1L

                release.complete(Unit)
                reader.await()
            }
            drained.get() shouldBe 3
            // The late return ran the deferred sweep before closing its connection.
            tableCount(observer) shouldBe 0
        }
        // …and with the observer gone too, nothing keeps the database: a fresh connection sees none of it.
        DriverManager.getConnection(stagingUrl(executionId, props), "sa", "").use { fresh -> execUserCount(fresh) shouldBe 0L }
    }

    @Test
    fun `a creation failure after the operational connection opened does not leave the database behind`() {
        val executionId = UUID.randomUUID()
        val closed = AtomicInteger()
        // The restricted connect succeeds, but the pool's capture of session defaults —
        // `getSchema` — fails: creation must close that connection (destroying the database)
        // and report the catalogued code, not leak a half-built pool.
        val factory =
            H2StagingFactory(props) { url, user, password ->
                val real = DriverManager.getConnection(url, user, password)
                if (user != "STAGING_EXEC") {
                    real
                } else {
                    Proxy.newProxyInstance(
                        Connection::class.java.classLoader,
                        arrayOf(Connection::class.java),
                        InvocationHandler { _, method, args ->
                            if (method.name == "getSchema") throw SQLException("no session", "08003")
                            if (method.name == "close") closed.incrementAndGet()
                            try {
                                method.invoke(real, *(args ?: emptyArray()))
                            } catch (e: java.lang.reflect.InvocationTargetException) {
                                throw e.targetException
                            }
                        },
                    ) as Connection
                }
            }

        val thrown = shouldThrow<DatapipelinesException> { factory.create(executionId) }
        thrown.code shouldBe StagingErrorCodes.CREATION_FAILED
        closed.get() shouldBe 1
        DriverManager.getConnection(stagingUrl(executionId, props), "sa", "").use { fresh -> execUserCount(fresh) shouldBe 0L }
    }

    @Test
    fun `a database that disappears under a lease fails that lease, and close still does not throw`() {
        val executionId = UUID.randomUUID()
        val staging = H2StagingFactory(props).create(executionId)
        runBlocking { staging.execute("CREATE TABLE \"stg_gone\" (\"id\" INTEGER)") }

        // An admin observer shuts the database down under the pool — the "disappearance" shape.
        DriverManager.getConnection(stagingUrl(executionId, props), "sa", "").use { admin ->
            admin.createStatement().use { it.execute("SHUTDOWN IMMEDIATELY") }
        }

        shouldThrow<SQLException> { runBlocking { staging.execute("SELECT COUNT(*) FROM \"stg_gone\"") } }
        // Non-throwing on every path (§3.4), even when there is nothing left to clean.
        staging.close()
    }

    @Test
    fun `a partial table whose cleanup refuses keeps its name owned instead of silently reusable`() {
        val executionId = UUID.randomUUID()
        val armed = AtomicInteger()
        val staging =
            stagingOverConnections(executionId, props) { real ->
                Proxy.newProxyInstance(
                    Connection::class.java.classLoader,
                    arrayOf(Connection::class.java),
                    InvocationHandler { _, method, args ->
                        val result =
                            try {
                                method.invoke(real, *(args ?: emptyArray()))
                            } catch (e: java.lang.reflect.InvocationTargetException) {
                                throw e.targetException
                            }
                        if (method.name == "createStatement") refusingDrops(result as java.sql.Statement, armed) else result
                    },
                ) as Connection
            }

        armed.set(1)
        SourceDb().use { src ->
            src.exec("CREATE TABLE t (n DECIMAL(3,0))")
            src.exec("INSERT INTO t VALUES (1)")
            // A value overflow after CREATE TABLE → rollback → the DROP is refused.
            shouldThrow<StagingValueOverflowException> {
                runBlocking { staging.stage(overflowing(src.query("SELECT n FROM t")), "stg_dirty", Dialect.H2) }
            }
        }
        armed.set(0)
        // The table is still there (the drop refused) and the name is still owned: a retry is
        // refused with the catalogued code rather than reusing a dirty table as if it were new.
        staging.readFromStaging { tableCount(it) } shouldBe 1
        SourceDb().use { src ->
            shouldThrow<StagingTableAlreadyExistsException> {
                runBlocking { staging.stage(src.query("SELECT x AS id FROM SYSTEM_RANGE(1, 2)"), "stg_dirty", Dialect.H2) }
            }
        }
        runBlocking { staging.stats() }.totalRows shouldBe 0L
        staging.close()
    }

    @Test
    fun `a stage cancelled mid-drain at capacity one still rolls its partial table back`() {
        val executionId = UUID.randomUUID()
        val staging = H2StagingFactory(props.copy(maxConnections = 1, insertBatchSize = 10)).create(executionId)
        val pulled = AtomicInteger()
        val midway = CompletableDeferred<Unit>()

        SourceDb().use { src ->
            val cursor = src.query("SELECT x AS id FROM SYSTEM_RANGE(1, 1000)")
            val counting =
                Proxy.newProxyInstance(
                    java.sql.ResultSet::class.java.classLoader,
                    arrayOf(java.sql.ResultSet::class.java),
                    InvocationHandler { _, method, args ->
                        // Batch size 10: the 25th `next()` is mid-way through the third batch, with
                        // two batches already inserted and committed.
                        if (method.name == "next" && pulled.incrementAndGet() == 25) midway.complete(Unit)
                        try {
                            method.invoke(cursor, *(args ?: emptyArray()))
                        } catch (e: java.lang.reflect.InvocationTargetException) {
                            throw e.targetException
                        }
                    },
                ) as java.sql.ResultSet

            runBlocking {
                val job = launch(Dispatchers.IO) { staging.stage(counting, "stg_cancel", Dialect.H2) }
                withTimeout(TIMEOUT_MS) { midway.await() }
                job.cancel(CancellationException("node deadline"))
                withTimeout(TIMEOUT_MS) { job.join() }
            }
            // The drain stopped at the next batch boundary rather than reading the source to its end.
            (pulled.get() < 1000) shouldBe true
        }

        // The rollback needed the one connection while the scope was already cancelled.
        staging.readFromStaging { tableCount(it) } shouldBe 0
        runBlocking { staging.stats() }.totalRows shouldBe 0L
        // And the name is free for a retry.
        SourceDb().use { src ->
            runBlocking { staging.stage(src.query("SELECT x AS id FROM SYSTEM_RANGE(1, 5)"), "stg_cancel", Dialect.H2) }.rowsStaged shouldBe
                5L
        }
        staging.close()
    }

    @Test
    fun `child rows are pulled in bounded batches while no lease is held`() {
        val executionId = UUID.randomUUID()
        val activeAtPull = CopyOnWriteArrayList<Int>()
        val pool = H2ConnectionPool(executionId, sa(executionId), { sa(executionId) }, props.maxConnections)
        val staging = H2Staging(executionId, pool, props.copy(insertBatchSize = 10))
        val columns = listOf(ColumnSchema("id", LogicalType.INTEGER))
        val rows =
            (1..35).asSequence().map { i ->
                activeAtPull += pool.activeLeases
                listOf<Any?>(i)
            }

        runBlocking { staging.stageRows("stg_child", columns, rows) }.rowsStaged shouldBe 35L

        // Every pull happened between leases, never inside one.
        activeAtPull.size shouldBe 35
        activeAtPull.filter { it != 0 }.shouldBeEmpty()
        staging.readFromStaging { scalarLong(it, "SELECT COUNT(*) FROM \"stg_child\"") } shouldBe 35L
        staging.close()
    }

    @Test
    fun `two executions cannot see each other's objects`() {
        val a = H2StagingFactory(props).create(UUID.randomUUID())
        val b = H2StagingFactory(props).create(UUID.randomUUID())
        runBlocking { a.execute("CREATE TABLE \"only_a\" (\"id\" INTEGER)") }
        shouldThrow<SQLException> { runBlocking { b.execute("SELECT COUNT(*) FROM \"only_a\"") } }
        runBlocking { b.stats() }.tableCount shouldBe 0
        a.close()
        b.close()
    }

    @Test
    fun `every physical connection is the restricted user, including ones opened after bootstrap`() {
        val executionId = UUID.randomUUID()
        val staging = H2StagingFactory(props).create(executionId)
        val users = CopyOnWriteArrayList<String>()
        val refusals = AtomicInteger()
        val allIn = CompletableDeferred<Unit>()
        val entered = AtomicInteger()
        val release = CompletableDeferred<Unit>()

        runBlocking {
            // Three leases held at once → three distinct physical connections, two of them opened
            // by the pool after the bootstrap closed.
            (1..3)
                .map {
                    async(Dispatchers.IO) {
                        staging.withConnection { c ->
                            users +=
                                c.createStatement().use { st ->
                                    st.executeQuery("SELECT CURRENT_USER").use { rs ->
                                        rs.next()
                                        rs.getString(1)
                                    }
                                }
                            val refused =
                                runCatching {
                                    c.createStatement().use { st ->
                                        st.executeQuery("SELECT FILE_READ('/proc/self/environ', NULL)").use { }
                                    }
                                }.exceptionOrNull() as? SQLException
                            if (refused?.sqlState == ADMIN_REQUIRED) refusals.incrementAndGet()
                            if (entered.incrementAndGet() == 3) allIn.complete(Unit)
                            release.await()
                        }
                    }
                }.also {
                    withTimeout(TIMEOUT_MS) { allIn.await() }
                    release.complete(Unit)
                }.awaitAll()
        }

        users.size shouldBe 3
        users.map { it.uppercase() }.toSet() shouldBe setOf("STAGING_EXEC")
        refusals.get() shouldBe 3
        DriverManager.getConnection(stagingUrl(executionId, props), "sa", "").use { admin ->
            // Three restricted sessions really exist (plus this admin one) — the leases were not
            // three views of one connection.
            scalarLong(admin, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE UPPER(USER_NAME) = 'STAGING_EXEC'") shouldBe 3L
        }
        staging.close()
    }

    // ---------- fixtures ----------

    private fun sa(executionId: UUID): Connection = DriverManager.getConnection(stagingUrl(executionId, props), "sa", "")

    private fun execUserCount(connection: Connection): Long =
        scalarLong(connection, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.USERS WHERE UPPER(USER_NAME) = 'STAGING_EXEC'")

    /** A statement proxy that refuses `DROP TABLE` while [armed] is set — the failed-cleanup shape. */
    private fun refusingDrops(
        target: java.sql.Statement,
        armed: AtomicInteger,
    ): java.sql.Statement =
        Proxy.newProxyInstance(
            java.sql.Statement::class.java.classLoader,
            arrayOf(java.sql.Statement::class.java),
            InvocationHandler { _, method, args ->
                val sql = args?.firstOrNull() as? String
                if (armed.get() == 1 && method.name == "execute" && sql?.startsWith("DROP TABLE") == true) {
                    throw SQLException("drop refused", "90067")
                }
                try {
                    method.invoke(target, *(args ?: emptyArray()))
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    throw e.targetException
                }
            },
        ) as java.sql.Statement

    /** A cursor whose metadata says DECIMAL(3,0) but whose value is 99999 — the overflow shape. */
    private fun overflowing(target: java.sql.ResultSet): java.sql.ResultSet =
        Proxy.newProxyInstance(
            java.sql.ResultSet::class.java.classLoader,
            arrayOf(java.sql.ResultSet::class.java),
            InvocationHandler { _, method, args ->
                if (method.name == "getBigDecimal") return@InvocationHandler java.math.BigDecimal("99999")
                try {
                    method.invoke(target, *(args ?: emptyArray()))
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    throw e.targetException
                }
            },
        ) as java.sql.ResultSet

    private companion object {
        const val TIMEOUT_MS = 10_000L

        /** H2's "Admin rights are required", read off the pinned driver (2.3.232). */
        const val ADMIN_REQUIRED = "90040"
    }
}
