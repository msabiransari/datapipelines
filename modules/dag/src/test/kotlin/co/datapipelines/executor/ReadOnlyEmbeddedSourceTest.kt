package co.datapipelines.executor

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.ReadOnlyConnectionMode
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * #463 A.3 — the embedded engines through the real executor, each per its measured row of the
 * read-only table (dag-executor.md §8.2; `ReadOnlyConnectionModeEmbeddedTest` measures them):
 *
 *  - **SQLite** is `DATABASE` (`PRAGMA query_only = ON`): the four [ReadOnlySourceCases], the
 *    write-shaped text an `INSERT … RETURNING`, refused by the engine with `SQLITE_READONLY` (8).
 *  - **H2** and **DuckDB** are `DECLARATIVE_ONLY`: one case each PINS the honest limit — the
 *    write-shaped DQL SUCCEEDS on `readOnly = true` and its row persists. The guard there is the
 *    read-only rule plus a SELECT-only database user (datasources.md §5.7), never this mode.
 */
class ReadOnlyEmbeddedSourceTest {
    @Nested
    internal inner class Sqlite : ReadOnlySourceCases() {
        private val file: File = File.createTempFile("ro463_exec", ".db").also { it.deleteOnExit() }
        private val url = "jdbc:sqlite:${file.absolutePath}"

        override val datasource = Datasource(name = "lite_ro", displayName = "lite_ro", dialect = Dialect.SQLITE, jdbcUrl = url)

        override val writeSql = "INSERT INTO ro_t VALUES (1) RETURNING n"

        override fun connect(): Connection = DriverManager.getConnection(url)

        override fun refusedByDatabase(details: Map<String, Any?>): Boolean = details["vendor_code"] == SQLITE_READONLY
    }

    @Test
    fun `H2 is DECLARATIVE_ONLY - the write-shaped DQL succeeds on readOnly and its row persists`() {
        val url = "jdbc:h2:mem:ro463_exec_${System.nanoTime()};DB_CLOSE_DELAY=-1"
        assertWritesThrough(
            Datasource(name = "h2_ro", displayName = "h2_ro", dialect = Dialect.H2, jdbcUrl = url),
            writeSql = "SELECT n FROM FINAL TABLE (INSERT INTO ro_t VALUES (1))",
        ) { DriverManager.getConnection(url) }
    }

    @Test
    fun `DuckDB is DECLARATIVE_ONLY - the write-shaped DQL succeeds on readOnly and its row persists`(
        @TempDir dir: File,
    ) {
        val url = "jdbc:duckdb:${File(dir, "ro.duckdb").absolutePath}"
        assertWritesThrough(
            Datasource(name = "duck_ro", displayName = "duck_ro", dialect = Dialect.DUCKDB, jdbcUrl = url),
            writeSql = "INSERT INTO ro_t VALUES (1) RETURNING n",
        ) { DriverManager.getConnection(url) }
    }

    private fun assertWritesThrough(
        datasource: Datasource,
        writeSql: String,
        connect: () -> Connection,
    ) = runBlocking<Unit> {
        ReadOnlyConnectionMode.enforcementFor(datasource.dialect) shouldBe ReadOnlyConnectionMode.Enforcement.DECLARATIVE_ONLY
        connect().use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TABLE ro_t (n INTEGER)")
                s.execute("INSERT INTO ro_t VALUES (0)")
            }
        }
        val pipeline = Fixtures.pipeline(listOf(Fixtures.node("q", source = datasource.name)))
        val result =
            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("q" to writeSql)),
                registry = FakeDatasourceRegistry(mapOf(datasource.name to datasource)),
            ).use { h -> h.executor.execute(Fixtures.request(pipeline).copy(readOnly = true)) }
        result.status shouldBe ExecutionStatus.SUCCESS
        result.nodeStats.single().rowsOut shouldBe 1
        withClue("the honest limit: ${datasource.dialect} wrote on a read-only run") {
            connect().use { c ->
                c.createStatement().use { s ->
                    s.executeQuery("SELECT COUNT(*) FROM ro_t").use {
                        it.next()
                        it.getLong(1)
                    }
                }
            } shouldBe 2L
        }
    }

    private companion object {
        /** SQLite's `SQLITE_READONLY` primary result code. */
        const val SQLITE_READONLY = 8
    }
}
