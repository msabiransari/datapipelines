package co.datapipelines.datasources

import co.datapipelines.datasources.pooling.ConnectionPoolManager
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * #463 A.2 — [ReadOnlyConnectionMode] measured on the three embedded engines, each through the
 * adapter-built real pool (max size 1).
 *
 * What the runs established (2026-11-12, the pinned sqlite-jdbc, h2 and duckdb_jdbc):
 *
 *  - **SQLite** — `setReadOnly` after connect throws ("Cannot change read-only flag after
 *    establishing a connection"), so M1 fails; M2 `PRAGMA query_only = ON` makes the ENGINE refuse
 *    writes (`SQLITE_READONLY`, result code 8). The JDBC flag never reports `query_only`, so the
 *    mode reads the pragma itself. `DATABASE`, the full contract.
 *  - **H2** — `setReadOnly(true)` is accepted and reported, and an INSERT still succeeds, in and
 *    out of a transaction (`staging/H2SessionReset.kt` documents the no-op). No M2 candidate.
 *    `DECLARATIVE_ONLY`.
 *  - **DuckDB** — `setReadOnly` throws ("Can't change read-only status on connection level"),
 *    and the pool's locked configuration refuses `SET`. No M2 candidate. `DECLARATIVE_ONLY`.
 *
 * Each `DECLARATIVE_ONLY` dialect pins BOTH halves: the candidate mechanism does not refuse a write
 * on this driver (the measurement), and [ReadOnlyConnectionMode.within] runs the block as it is —
 * a write inside it succeeds (the honest limit).
 */
class ReadOnlyConnectionModeEmbeddedTest {
    @Nested
    internal inner class Sqlite : ReadOnlyConnectionModeContract() {
        private val file: File = File.createTempFile("ro463_sqlite", ".db").also { it.deleteOnExit() }
        private val url = "jdbc:sqlite:${file.absolutePath}"

        override val subject =
            ReadOnlySubject(
                dialect = Dialect.SQLITE,
                datasource = pooledOne(Dialect.SQLITE, "ro_lite", url),
                raw = { DriverManager.getConnection(url) },
                setup = { },
                write = { c, n -> c.execute("INSERT INTO ro_t VALUES ($n)") },
                refusedByDatabase = { it.errorCode == SQLITE_READONLY || it.message.orEmpty().contains("SQLITE_READONLY") },
                entryCall = Call("execute", "PRAGMA query_only = ON"),
                restoreCall = Call("execute", "PRAGMA query_only = OFF"),
                setting = { c ->
                    c.createStatement().use { s ->
                        s.executeQuery("PRAGMA query_only").use {
                            it.next()
                            it.getBoolean(1)
                        }
                    }
                },
                settingOn = "PRAGMA query_only = ON",
                driverSetsReadOnly = false,
            )

        @Test
        fun `M1 is unavailable on this driver - setReadOnly after connect throws`() {
            ConnectionPoolManager.buildHikariPool(subject.datasource).use { pool ->
                pool.leaseConnection().use { c ->
                    shouldThrow<SQLException> { c.isReadOnly = true }.message.orEmpty() shouldContain "read-only"
                }
            }
        }
    }

    @Test
    fun `H2 is DECLARATIVE_ONLY - setReadOnly is accepted and a write still succeeds, and within runs the block as it is`() {
        val datasource = pooledOne(Dialect.H2, "ro_h2", "jdbc:h2:mem:ro463_${System.nanoTime()};DB_CLOSE_DELAY=-1")
        ConnectionPoolManager.buildHikariPool(datasource).use { pool ->
            pool.leaseConnection().use { c ->
                c.execute("CREATE TABLE ro_t (n INTEGER)")
                c.isReadOnly = true
                c.isReadOnly shouldBe true
                withClue("M1 measured: the flag is reported and enforces nothing, under autocommit") {
                    c.execute("INSERT INTO ro_t VALUES (1)")
                }
                c.autoCommit = false
                withClue("…and inside a transaction") { c.execute("INSERT INTO ro_t VALUES (2)") }
                c.commit()
                c.autoCommit = true
                c.isReadOnly = false
                assertDeclarative(c, Dialect.H2)
            }
        }
    }

    @Test
    fun `DuckDB is DECLARATIVE_ONLY - setReadOnly throws, and within runs the block as it is`(
        @TempDir dir: File,
    ) {
        val datasource = pooledOne(Dialect.DUCKDB, "ro_duck", "jdbc:duckdb:${File(dir, "ro.duckdb").absolutePath}")
        ConnectionPoolManager.buildHikariPool(datasource).use { pool ->
            pool.leaseConnection().use { c ->
                c.execute("CREATE TABLE ro_t (n INTEGER)")
                withClue("M1 measured: the driver refuses the flag") {
                    shouldThrow<SQLException> { c.isReadOnly = true }.message.orEmpty() shouldContain "read-only"
                }
                assertDeclarative(c, Dialect.DUCKDB)
            }
        }
    }

    private fun assertDeclarative(
        c: Connection,
        dialect: Dialect,
    ) {
        ReadOnlyConnectionMode.enforcementFor(dialect) shouldBe ReadOnlyConnectionMode.Enforcement.DECLARATIVE_ONLY
        val before = Triple(c.autoCommit, c.isReadOnly, c.count())
        withClue("the honest limit: a write inside the mode SUCCEEDS on a DECLARATIVE_ONLY dialect") {
            ReadOnlyConnectionMode.within(c, dialect, { error("nothing to dispose") }) { c.execute("INSERT INTO ro_t VALUES (9)") }
        }
        c.count() shouldBe before.third + 1
        Triple(c.autoCommit, c.isReadOnly, c.count() - 1) shouldBe before
    }

    private fun Connection.count(): Long =
        createStatement().use { s ->
            s.executeQuery("SELECT COUNT(*) FROM ro_t").use {
                it.next()
                it.getLong(1)
            }
        }

    private companion object {
        /** SQLite's `SQLITE_READONLY` primary result code. */
        const val SQLITE_READONLY = 8
    }
}
