package co.datapipelines.datasources

import co.datapipelines.datasources.pooling.ConnectionPoolManager
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
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
 * §7C for a LAKE datasource (the [LakeSchemaIntrospectorTest] pattern): the registry is the
 * catalog, the row estimate and column min/max are Parquet FOOTER reads through
 * `parquet_metadata(...)` on a real pool, and the registry's partition column reports as the
 * `partition` pseudo-index. Iceberg's no-stats answer is pinned without a container.
 */
class LakeTableStatsTest {
    @TempDir
    lateinit var tempDir: File

    private val adapter = DialectAdapters.forDialect(Dialect.LAKE)

    private fun lakeDatasource(name: String = "lake_stats") =
        Datasource(
            name = name,
            displayName = "Lake",
            dialect = Dialect.LAKE,
            jdbcUrl = "jdbc:duckdb:",
            credentialKind = CredentialKind.NONE,
        )

    private fun introspectorOver(
        rows: List<LakeRegisteredTable>,
        pool: co.datapipelines.datasources.pooling.ConnectionPool? = null,
    ): Pair<SchemaIntrospector, Datasource> {
        val ds = lakeDatasource()
        val registry = mockk<DatasourceRegistry>()
        every { registry.get(ds.name) } returns ds
        if (pool != null) every { registry.poolFor(any()) } returns pool
        return SchemaIntrospector(registry, LakeTableCatalog { rows }) to ds
    }

    @Test
    fun `a parquet table's stats are its footers, plus the partition pseudo-index`() {
        val parquet = seedParquet()
        val rows =
            listOf(
                LakeRegisteredTable(
                    listOf("nyc", "mobility"),
                    "trips",
                    "parquet",
                    "file://${parquet.absolutePath}",
                    partitionColumn = "pickup_date",
                ),
            )
        val ds = lakeDatasource()
        val pool = ConnectionPoolManager.buildHikariPool(ds, LakeViewStatements.forTables(rows, adapter))
        val (introspector, _) = introspectorOver(rows, pool)

        val stats = introspector.tableStats(ds, "trips", namespaceFilter = listOf("nyc", "mobility"))

        val id = stats.columns.single { it.name == "id" }
        val label = stats.columns.single { it.name == "label" }
        assertAll(
            { stats.statsSource shouldBe "parquet_metadata" },
            // range(1, 1000) — one row group of 999 rows.
            { stats.rowEstimate shouldBe "999" },
            { stats.statsAsOf shouldBe null },
            // A lake has no indexes; the partition column reports as the pseudo-index it is.
            {
                stats.indexes shouldContainExactly
                    listOf(
                        IndexStats(
                            name = "partition",
                            columns = listOf("pickup_date"),
                            unique = false,
                            primary = false,
                            kind = IndexStats.INDEX_KIND_PARTITION,
                        ),
                    )
            },
            // Footer bounds, numeric-aware (SQL's MIN over the VARCHAR stats columns would
            // compare lexically).
            { id.min shouldBe "1" },
            { id.max shouldBe "999" },
            { id.nullFraction shouldBe 0.0 },
            { label.min shouldBe "v1" },
            { label.max shouldBe "v999" },
            { id.nDistinct shouldBe null },
            { id.distinctIsRatio shouldBe false },
        )
    }

    @Test
    fun `an unpartitioned parquet table reports no indexes`() {
        val parquet = seedParquet()
        val rows = listOf(LakeRegisteredTable(listOf("nyc"), "trips", "parquet", "file://${parquet.absolutePath}"))
        val ds = lakeDatasource()
        val pool = ConnectionPoolManager.buildHikariPool(ds, LakeViewStatements.forTables(rows, adapter))
        val (introspector, _) = introspectorOver(rows, pool)

        // One registered namespace: the unfiltered read resolves there (the search-path rule's twin).
        val stats = introspector.tableStats(ds, "trips")

        assertAll(
            { stats.rowEstimate shouldBe "999" },
            { stats.indexes shouldBe emptyList<IndexStats>() },
        )
    }

    @Test
    fun `an iceberg table answers no catalog stats - the footer read does not apply`() {
        // The registered location is the table's metadata JSON; DuckDB 1.5.x exposes no
        // per-column iceberg statistics a footer-style read can reach (the reader's KDoc
        // records the gap). The partition pseudo-index still reports — it is registry data.
        val rows =
            listOf(
                LakeRegisteredTable(
                    listOf("trade", "root"),
                    "orders",
                    "iceberg",
                    "s3://b/iceberg/orders/metadata/00001.metadata.json",
                    partitionColumn = "day",
                ),
            )
        val (introspector, ds) = introspectorOver(rows)

        val stats = introspector.tableStats(ds, "orders")

        assertAll(
            { stats.statsSource shouldBe "none" },
            { stats.rowEstimate shouldBe null },
            { stats.columns shouldBe emptyList<ColumnStats>() },
            {
                stats.indexes shouldContainExactly
                    listOf(
                        IndexStats("partition", listOf("day"), unique = false, primary = false, kind = IndexStats.INDEX_KIND_PARTITION),
                    )
            },
        )
    }

    @Test
    fun `an unregistered table answers empty stats, never an error`() {
        val (introspector, ds) = introspectorOver(emptyList())

        val stats = introspector.tableStats(ds, "ghost")

        assertAll(
            { stats.statsSource shouldBe "none" },
            { stats.rowEstimate shouldBe null },
            { stats.indexes shouldBe emptyList<IndexStats>() },
        )
    }

    @Test
    fun `an unfiltered read with several registered namespaces refuses like columns does`() {
        val rows =
            listOf(
                LakeRegisteredTable(listOf("nyc"), "a", "parquet", "s3://b/a.parquet"),
                LakeRegisteredTable(listOf("trade"), "b", "parquet", "s3://b/b.parquet"),
            )
        val (introspector, ds) = introspectorOver(rows)

        shouldThrow<CurrentSchemaUnknownException> { introspector.tableStats(ds, "a") }
    }

    private fun seedParquet(): File {
        val parquet = tempDir.resolve("trips.parquet")
        DriverManager.getConnection("jdbc:duckdb:").use { writer ->
            writer.createStatement().use {
                it.execute(
                    "COPY (SELECT i AS id, 'v' || i AS label FROM range(1, 1000) t(i)) " +
                        "TO '${parquet.absolutePath}' (FORMAT PARQUET)",
                )
            }
        }
        return parquet
    }
}
