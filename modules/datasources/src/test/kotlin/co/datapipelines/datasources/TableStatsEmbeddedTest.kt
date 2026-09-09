package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager

/**
 * §7C catalog statistics over the two embedded dialects (the [EmbeddedDialectBehaviorTest]
 * pattern): real pinned drivers over FILE-backed databases — an in-memory DuckDB/SQLite
 * database is per-connection, and the reader leases its own connection from the pool, so the
 * fixture must persist where the lease can see it.
 */
class TableStatsEmbeddedTest {
    @TempDir
    lateinit var tempDir: File

    private fun introspectorFor(
        name: String,
        dialect: Dialect,
        jdbcUrl: String,
    ): SchemaIntrospector {
        val ds =
            Datasource(
                name = name,
                displayName = name,
                dialect = dialect,
                jdbcUrl = jdbcUrl,
                credentialKind = CredentialKind.NONE,
            )
        val registry = mockk<DatasourceRegistry>()
        every { registry.get(name) } returns ds
        every { registry.poolFor(ds) } returns JdbcUrlPool(jdbcUrl, name)
        return SchemaIntrospector(registry)
    }

    @Test
    fun `sqlite reports indexes always, a row estimate only after ANALYZE`() {
        val db = tempDir.resolve("stats.db")
        val url = "jdbc:sqlite:${db.absolutePath}"
        DriverManager.getConnection(url).use { writer ->
            writer.createStatement().use { st ->
                // A table-level composite PRIMARY KEY: an INTEGER PRIMARY KEY column is a rowid
                // alias with NO index (verified on sqlite-jdbc 3.49.1.0) and would pin nothing.
                st.execute("CREATE TABLE orders_lite (a TEXT, b TEXT, c TEXT, PRIMARY KEY (a, b))")
                st.execute("CREATE INDEX idx_bc ON orders_lite(b, c)")
                st.execute("INSERT INTO orders_lite VALUES ('x', 'y', '1'), ('p', 'q', '2'), ('m', 'n', '3')")
            }
        }
        val introspector = introspectorFor("lite_stats", Dialect.SQLITE, url)

        val before = introspector.tableStats("lite_stats", "orders_lite")

        val autoindex = before.indexes.single { it.primary }
        val secondary = before.indexes.single { it.name == "idx_bc" }
        assertAll(
            // sqlite_stat1 does not exist before the first ANALYZE — the read tolerates the
            // missing catalog and says so.
            { before.statsSource shouldBe "none" },
            { before.rowEstimate shouldBe null },
            { autoindex.unique shouldBe true },
            { autoindex.columns shouldContainExactly listOf("a", "b") },
            { secondary.unique shouldBe false },
            { secondary.primary shouldBe false },
            { secondary.columns shouldContainExactly listOf("b", "c") },
            { before.columns shouldBe emptyList<ColumnStats>() },
        )

        DriverManager.getConnection(url).use { writer ->
            writer.createStatement().use { it.execute("ANALYZE") }
        }

        val after = introspector.tableStats("lite_stats", "orders_lite")

        assertAll(
            { after.statsSource shouldBe "sqlite_stat1" },
            { after.rowEstimate shouldBe "3" },
        )
    }

    @Test
    fun `duckdb reports estimated size, constraint indexes in key order, and explicit indexes`() {
        val db = tempDir.resolve("stats.duckdb")
        val url = "jdbc:duckdb:${db.absolutePath}"
        DriverManager.getConnection(url).use { writer ->
            writer.createStatement().use { st ->
                st.execute("CREATE TABLE pkd (x INTEGER, y INTEGER, label VARCHAR, PRIMARY KEY (y, x))")
                st.execute("CREATE UNIQUE INDEX idx_label ON pkd(label)")
                st.execute("INSERT INTO pkd VALUES (1, 10, 'a'), (2, 20, 'b'), (3, 30, 'c'), (4, 40, 'd'), (5, 50, 'e')")
            }
        }
        val introspector = introspectorFor("duck_stats", Dialect.DUCKDB, url)

        // The unfiltered read resolves the connection's current schema (main), like columns().
        val stats = introspector.tableStats("duck_stats", "pkd")

        val primary = stats.indexes.single { it.primary }
        val explicit = stats.indexes.single { it.name == "idx_label" }
        assertAll(
            { stats.statsSource shouldBe "duckdb_tables" },
            { stats.rowEstimate shouldBe "5" },
            { stats.statsAsOf shouldBe null },
            // duckdb_constraints(): the PRIMARY KEY's column list, in key order (y, x).
            { primary.unique shouldBe true },
            { primary.columns shouldContainExactly listOf("y", "x") },
            // duckdb_indexes(): the explicit index's columns parsed from its expressions text.
            { explicit.unique shouldBe true },
            { explicit.primary shouldBe false },
            { explicit.columns shouldContainExactly listOf("label") },
            // DuckDB keeps no per-column catalog statistics.
            { stats.columns shouldBe emptyList<ColumnStats>() },
        )
    }
}
