package co.datapipelines.staging

import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.sql.Types
import java.util.UUID

/**
 * The failure paths that had no coverage at all: `value_overflow` (ST-TEST-4), `execute()`'s
 * post-write memory check (ST-TEST-3), `table_already_exists` reached through a DDL path the
 * in-process guard cannot see (ST-API-1), and the rollback of a partially staged table
 * (ST-SEC-4).
 *
 * These are the paths where a wrong classification is invisible in production: the executor
 * maps staging error CODES to node outcomes, so an unwrapped driver `SQLException` or a
 * poisoned table name surfaces as "internal error" long after the node that caused it.
 */
class H2StagingFailurePathTest {
    private val props = H2StagingProperties()
    private val staging = H2StagingFactory(props).create(UUID.randomUUID())

    @AfterEach
    fun tearDown() = staging.close()

    // ---------- ST-TEST-4: value_overflow, both branches ----------

    @Test
    fun `a source value past the staged column capacity fails with value_overflow`() {
        val thrown =
            shouldThrow<StagingValueOverflowException> {
                runBlocking { staging.stage(overflowingDecimalCursor(), "stg_overflow", Dialect.H2) }
            }

        thrown.code shouldBe StagingErrorCodes.VALUE_OVERFLOW
        thrown.message.orEmpty() shouldContain "stg_overflow"
        // The wrapped cause is H2's own data exception (SQL class 22) — verified against the
        // pinned driver rather than assumed: DECIMAL(3,0) ← 99999 reports SQLState 22001.
        val cause = thrown.cause as SQLException
        cause.sqlState.orEmpty().take(2) shouldBe "22"
    }

    @Test
    fun `a non-data-exception SQLException propagates unwrapped`() {
        // Class 42 (syntax/access), not 22: this must NOT be relabelled as a value overflow —
        // that would tell the author to fix their data when the fault is ours.
        val staging = stagingOverFailingInsert(SQLException("boom", "42000"))

        val thrown =
            shouldThrow<SQLException> {
                runBlocking { staging.stage(oneIntegerCursor(), "stg_raw", Dialect.H2) }
            }

        thrown.sqlState shouldBe "42000"
        staging.close()
    }

    // ---------- ST-SEC-4: rollback of a partial table ----------

    @Test
    fun `a failed stage drops its partial table and frees the name for a retry`() {
        shouldThrow<StagingValueOverflowException> {
            runBlocking { staging.stage(overflowingDecimalCursor(), "stg_retry", Dialect.H2) }
        }

        // Nothing left behind in the catalog…
        staging.readFromStaging { tableCount(it) } shouldBe 0
        // …the row counter was not inflated by the failed attempt…
        runBlocking { staging.stats() }.totalRows shouldBe 0L

        // …and the name is reusable, which is what a P4 node retry depends on.
        val retry =
            SourceDb().use { src ->
                src.exec("CREATE TABLE t (id INTEGER)")
                src.exec("INSERT INTO t VALUES (1), (2)")
                runBlocking { staging.stage(src.query("SELECT id FROM t"), "stg_retry", Dialect.H2) }
            }
        retry.rowsStaged shouldBe 2L
        runBlocking { staging.stats() }.totalRows shouldBe 2L
    }

    @Test
    fun `a stage that trips the memory budget also rolls its table back`() {
        val tiny =
            H2StagingFactory(H2StagingProperties(maxMemoryMb = budgetMbAboveBaseline(HEADROOM_MB)))
                .create(UUID.randomUUID())

        SourceDb().use { src ->
            val rs = src.query("SELECT x AS id, RPAD('a', 800, 'a') AS payload FROM SYSTEM_RANGE(1, 100000)")
            shouldThrow<StagingMemoryLimitException> { runBlocking { tiny.stage(rs, "stg_fat", Dialect.H2) } }
        }

        // The budget failure happens AFTER the rows are inserted, so without rollback the table
        // would survive holding the very memory the budget just refused.
        tiny.readFromStaging { tableCount(it) } shouldBe 0
        runBlocking { tiny.stats() }.totalRows shouldBe 0L

        tiny.close()
    }

    // ---------- ST-TEST-3: execute()'s post-write memory check ----------

    @Test
    fun `execute past the memory budget fails with memory_limit_exceeded`() {
        // §8.2's reading is the JVM's USED HEAP in KB, not the staged table's bytes — so a
        // hard-coded 1 MB budget trips on the process baseline (~6-17 MB) at the FIRST staging
        // call and proves nothing about `execute()`. The budget is anchored to a measured
        // baseline plus headroom that the one-row seed fits inside and the bulk INSERT
        // (100k × 800 chars ≈ +24 MB, measured on the pinned driver) cannot.
        val budgetMb = budgetMbAboveBaseline(HEADROOM_MB)
        val tiny = H2StagingFactory(H2StagingProperties(maxMemoryMb = budgetMb)).create(UUID.randomUUID())

        SourceDb().use { src ->
            src.exec("CREATE TABLE t (id INTEGER, payload VARCHAR(800))")
            src.exec("INSERT INTO t VALUES (1, 'seed')")
            // The control half of the test: inside the budget, staging succeeds. Without it the
            // failure below could just as well be a budget that was already blown on arrival.
            runBlocking {
                tiny.stage(src.query("SELECT id, payload FROM t"), "stg_seed", Dialect.H2)
            }.rowsStaged shouldBe 1L
        }

        // A write through execute() — not stage() — must be measured too (§8.2).
        val thrown =
            shouldThrow<StagingMemoryLimitException> {
                runBlocking {
                    tiny.execute(
                        "INSERT INTO \"stg_seed\" SELECT x, RPAD('a', 800, 'a') FROM SYSTEM_RANGE(1, 100000)",
                    )
                }
            }
        thrown.code shouldBe StagingErrorCodes.MEMORY_LIMIT_EXCEEDED
        thrown.maxMemoryMb shouldBe budgetMb
        // The measurement is what tripped it, and it was taken AFTER execute()'s write.
        thrown.memoryUsedBytes shouldBeGreaterThan budgetMb * BYTES_PER_MB

        tiny.close()
    }

    // ---------- ST-API-1: table_already_exists across the new DDL paths ----------

    @Test
    fun `a table created through execute makes a later stage of that name a catalogued failure`() {
        runBlocking { staging.execute("CREATE TABLE \"stg_ddl\" (\"id\" INTEGER)") }

        val thrown =
            SourceDb().use { src ->
                src.exec("CREATE TABLE t (id INTEGER)")
                src.exec("INSERT INTO t VALUES (1)")
                shouldThrow<StagingTableAlreadyExistsException> {
                    runBlocking { staging.stage(src.query("SELECT id FROM t"), "stg_ddl", Dialect.H2) }
                }
            }

        // The point of the fix: a catalogued code, not a raw driver SQLException the executor
        // would have to classify as an internal error.
        thrown.code shouldBe StagingErrorCodes.TABLE_ALREADY_EXISTS
        thrown.tableName shouldBe "stg_ddl"
    }

    @Test
    fun `the SQLState the duplicate-table mapping keys on is the one H2 actually reports`() {
        runBlocking { staging.execute("CREATE TABLE \"stg_state\" (\"id\" INTEGER)") }

        // Re-derived from the driver, not trusted from the constant: provoke a real duplicate
        // CREATE TABLE and read its SQLState. If a driver upgrade changes it, this fails here
        // AND the mapping test above fails — the pair localises the break.
        val observed =
            staging.readFromStaging { connection ->
                runCatching {
                    connection.createStatement().use { it.execute("CREATE TABLE \"stg_state\" (\"id\" INTEGER)") }
                }.exceptionOrNull() as SQLException
            }

        observed.sqlState shouldBe "42S01"
    }

    @Test
    fun `stats reports tables created outside stage`() {
        runBlocking { staging.execute("CREATE TABLE \"stg_by_sql\" (\"id\" INTEGER)") }

        // §10 says "current tables". The in-process set never saw this one.
        runBlocking { staging.stats() }.tableCount shouldBe 1
    }

    // ---------- fixtures ----------

    /** A one-row cursor whose metadata says `DECIMAL(3,0)` but whose value is 99999. */
    private fun overflowingDecimalCursor(): ResultSet {
        val meta =
            mockk<ResultSetMetaData> {
                every { columnCount } returns 1
                every { getColumnLabel(1) } returns "AMOUNT"
                every { getColumnType(1) } returns Types.DECIMAL
                every { getPrecision(1) } returns 3
                every { getScale(1) } returns 0
                every { getColumnTypeName(1) } returns "DECIMAL"
            }
        var rows = 1
        return mockk {
            every { metaData } returns meta
            every { next() } answers { rows-- > 0 }
            every { getBigDecimal(1) } returns BigDecimal("99999")
        }
    }

    /** A one-row, one-INTEGER cursor — enough to reach the insert. */
    private fun oneIntegerCursor(): ResultSet {
        val meta =
            mockk<ResultSetMetaData> {
                every { columnCount } returns 1
                every { getColumnLabel(1) } returns "ID"
                every { getColumnType(1) } returns Types.INTEGER
                every { getPrecision(1) } returns 10
                every { getScale(1) } returns 0
                every { getColumnTypeName(1) } returns "INTEGER"
            }
        var rows = 1
        return mockk {
            every { metaData } returns meta
            every { next() } answers { rows-- > 0 }
            every { getInt(1) } returns 1
            every { wasNull() } returns false
        }
    }

    /**
     * A staging instance whose `prepareStatement` always fails with [failure].
     *
     * Built over an instrumented `sa` connection, NOT the factory's restricted one: the proxy has
     * to wrap the Connection, which the factory does not expose. This test is about the insert
     * failure path, not §9.5 privilege — that is H2StagingPrivilegeTest's job, on a real factory
     * instance. The `sa` here is test scaffolding, not an endorsement.
     */
    private fun stagingOverFailingInsert(failure: SQLException): Staging {
        val executionId = UUID.randomUUID()
        val real = DriverManager.getConnection(stagingUrl(executionId, props), "sa", "")
        val proxy =
            Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf(Connection::class.java),
                InvocationHandler { _, method, args ->
                    if (method.name == "prepareStatement") throw failure
                    try {
                        method.invoke(real, *(args ?: emptyArray()))
                    } catch (e: java.lang.reflect.InvocationTargetException) {
                        throw e.targetException
                    }
                },
            ) as Connection
        return H2Staging(executionId, proxy, props)
    }

    private companion object {
        const val BYTES_PER_MB = 1024L * 1024L

        /**
         * Budget headroom over the measured process baseline. Big enough that the one-row seed
         * and H2's own allocations stay inside it, small enough that the ~24 MB bulk INSERT is
         * unambiguously past it (measured on the pinned driver: gate-logs/probe/delta-run1.txt).
         */
        const val HEADROOM_MB = 8L
    }
}
