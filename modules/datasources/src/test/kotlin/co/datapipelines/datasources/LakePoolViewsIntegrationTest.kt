package co.datapipelines.datasources

import co.datapipelines.datasources.pooling.ConnectionPoolManager
import co.datapipelines.datasources.pooling.HikariConnectionPool
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager

/**
 * Phase B's end-to-end proof over a REAL pool (089 §B): the registry rows become per-table
 * views in a new pool's `connectionInitSql`, a template reads them by bare name (the
 * single-namespace search-path rule) and by the fully-qualified three-part name, and — the
 * rebuild half — a table registered AFTER a pool exists becomes visible on the next execution
 * without a restart, because `evict` drops the cached pool and the next `poolFor` rebuild
 * re-reads the registry.
 *
 * The [ConnectionPoolManager] factory here is the same shape `DefaultDatasourceRegistry`'s is
 * (build through the adapter, append [LakeViewStatements] of the catalog's current rows) —
 * `evict` is what `LakeTableRegistryService.refreshConnections` triggers through
 * `DatasourceRegistry.evictPool` on every registry mutation.
 */
class LakePoolViewsIntegrationTest {
    @TempDir
    lateinit var tempDir: File

    private val adapter = DialectAdapters.forDialect(Dialect.LAKE)

    private fun lakeDatasource(name: String) =
        Datasource(
            name = name,
            displayName = "Lake",
            dialect = Dialect.LAKE,
            jdbcUrl = "jdbc:duckdb:",
            credentialKind = CredentialKind.NONE,
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

    @Test
    fun `registered tables are views - readable bare and three-part - and a later registration appears after evict`() {
        val tripsFile = parquet("trips.parquet", "SELECT 1 AS id, 'a' AS label UNION ALL SELECT 2, 'b'")
        val zoneFile = parquet("zone_day.parquet", "SELECT 'Manhattan' AS zone, 42 AS trips")
        // The registry, as the pool factory sees it: a mutable list the factory re-reads per build.
        val rows =
            mutableListOf(
                LakeRegisteredTable(listOf("nyc", "mobility"), "hvfhv_trips", "parquet", "file://${tripsFile.absolutePath}"),
            )
        val ds = lakeDatasource("lake_views")
        val manager =
            ConnectionPoolManager { datasource ->
                ConnectionPoolManager.buildHikariPool(
                    datasource,
                    LakeViewStatements.forTables(rows.toList(), adapter),
                )
            }

        manager.use {
            val firstPool = manager.poolFor(ds)
            firstPool.leaseConnection().use { connection ->
                assertAll(
                    // The demo content's spelling: the bare name under the single-namespace
                    // search-path rule.
                    { countOf(connection, "SELECT count(*) FROM hvfhv_trips") shouldBe 2 },
                    // …and the fully-qualified three-part name.
                    { countOf(connection, "SELECT count(*) FROM \"nyc\".\"mobility\".\"hvfhv_trips\"") shouldBe 2 },
                )
            }

            // A second table is registered. The pool built BEFORE it cannot see it…
            rows += LakeRegisteredTable(listOf("nyc", "mobility"), "hvfhv_zone_day", "parquet", "file://${zoneFile.absolutePath}")
            firstPool.leaseConnection().use { connection ->
                runsSql(connection, "SELECT count(*) FROM hvfhv_zone_day") shouldBe false
            }

            // …until refreshConnections' eviction: the next poolFor rebuilds from the fresh
            // registry rows, and the new connection's init SQL carries BOTH views.
            manager.evict(ds.name) shouldBe true
            (firstPool as HikariConnectionPool).isClosed shouldBe true
            manager.poolFor(ds).leaseConnection().use { connection ->
                withClue("the second registration was not visible after evict + rebuild") {
                    countOf(connection, "SELECT count(*) FROM hvfhv_zone_day") shouldBe 1
                }
                countOf(connection, "SELECT count(*) FROM hvfhv_zone_day WHERE zone = 'Manhattan'") shouldBe 1
            }
        }
    }

    @Test
    fun `a tableless registry adds no ATTACH and no views - the pool still opens`() {
        val ds = lakeDatasource("lake_empty")
        val manager =
            ConnectionPoolManager { datasource ->
                ConnectionPoolManager.buildHikariPool(datasource, LakeViewStatements.forTables(emptyList(), adapter))
            }

        manager.use {
            manager.poolFor(ds).leaseConnection().use { connection ->
                assertAll(
                    // No ATTACH was emitted: the engine reports no `nyc` catalog…
                    { catalogsOf(connection).contains("nyc") shouldBe false },
                    // …and no view answers a bare name.
                    { runsSql(connection, "SELECT count(*) FROM hvfhv_trips") shouldBe false },
                    // The adapter's own init still ran: preserve_insertion_order is off.
                    { settingOf(connection, "preserve_insertion_order") shouldBe "false" },
                )
            }
        }
    }

    private fun catalogsOf(connection: java.sql.Connection): List<String> =
        connection.metaData.catalogs.use { rs ->
            buildList { while (rs.next()) add(rs.getString(1)) }
        }

    private fun settingOf(
        connection: java.sql.Connection,
        name: String,
    ): String =
        connection.createStatement().use { st ->
            st.executeQuery("SELECT current_setting('$name')").use { rs ->
                rs.next() shouldBe true
                rs.getString(1)
            }
        }

    private fun countOf(
        connection: java.sql.Connection,
        sql: String,
    ): Int =
        connection.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                rs.next() shouldBe true
                rs.getInt(1)
            }
        }

    /** Whether [sql] runs at all — false on the engine's unknown-table failure. */
    private fun runsSql(
        connection: java.sql.Connection,
        sql: String,
    ): Boolean =
        runCatching {
            connection.createStatement().use { st ->
                st.executeQuery(sql).use { it.next() }
            }
        }.isSuccess
}
