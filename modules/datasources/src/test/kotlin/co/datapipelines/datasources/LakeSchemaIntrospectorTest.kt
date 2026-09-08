package co.datapipelines.datasources

import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.datasources.pooling.ConnectionPoolManager
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
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
 * [SchemaIntrospector]'s LAKE branches (089 §C): schemas/tables read the dp-lake registry
 * through the [LakeTableCatalog] port — never JDBC metadata — and columns read the registered
 * table's VIEW through a real pool (types via the DuckDB mapper, the same one the JDBC path
 * uses). The cache behavior of these branches is pinned here too: one catalog read per TTL,
 * re-derived after [LakeIntrospectionCache.invalidate] (the refreshConnections seam).
 */
class LakeSchemaIntrospectorTest {
    @TempDir
    lateinit var tempDir: File

    private val adapter = DialectAdapters.forDialect(Dialect.LAKE)

    private val zoneDay = LakeRegisteredTable(listOf("nyc", "mobility"), "hvfhv_zone_day", "parquet", "s3://b/z/part-0.parquet")
    private val trips = LakeRegisteredTable(listOf("nyc", "mobility"), "hvfhv_trips", "parquet", "s3://b/t/part-0.parquet")
    private val orders = LakeRegisteredTable(listOf("trade", "root"), "orders", "iceberg", "s3://b/iceberg/orders")

    private fun lakeDatasource(name: String = "lake_ds") =
        Datasource(
            name = name,
            displayName = "Lake",
            dialect = Dialect.LAKE,
            jdbcUrl = "jdbc:duckdb:",
            credentialKind = CredentialKind.NONE,
        )

    /** An introspector over a mock registry; [pool] is stubbed only when a test leases one. */
    private fun introspectorOver(
        rows: List<LakeRegisteredTable>,
        cache: LakeIntrospectionCache = LakeIntrospectionCache.NONE,
        pool: ConnectionPool? = null,
    ): Pair<SchemaIntrospector, Datasource> {
        val ds = lakeDatasource()
        val registry = mockk<DatasourceRegistry>()
        every { registry.get(ds.name) } returns ds
        if (pool != null) every { registry.poolFor(any()) } returns pool
        return SchemaIntrospector(registry, LakeTableCatalog { rows }, cache) to ds
    }

    // ---------------------------------------------------------- schemas

    @Test
    fun `schemas are the registry's distinct namespaces, labeled innermost`() {
        val (introspector, ds) = introspectorOver(listOf(zoneDay, trips, orders))

        val page = introspector.schemas(ds)

        assertAll(
            {
                page.entries shouldContainExactly
                    listOf(
                        SchemaEntry(listOf("nyc", "mobility"), "mobility"),
                        SchemaEntry(listOf("trade", "root"), "root"),
                    )
            },
            { page.truncated shouldBe false },
            // The pre-087 projection keeps working: labels in order.
            { page.schemas shouldContainExactly listOf("mobility", "root") },
        )
    }

    @Test
    fun `schemas reports truncation at the cap`() {
        val (introspector, ds) = introspectorOver(listOf(zoneDay, orders))

        val page = introspector.schemas(ds, maxSchemas = 1)

        assertAll(
            { page.entries shouldContainExactly listOf(SchemaEntry(listOf("nyc", "mobility"), "mobility")) },
            { page.truncated shouldBe true },
        )
    }

    // ---------------------------------------------------------- tables

    @Test
    fun `tables spans the registry without a filter - namespace populated, format in remarks`() {
        val (introspector, ds) = introspectorOver(listOf(zoneDay, trips, orders))

        val page = introspector.tables(ds)

        assertAll(
            {
                page.tables shouldContainExactly
                    listOf(
                        TableInfo(listOf("nyc", "mobility"), "hvfhv_zone_day", "VIEW", "parquet"),
                        TableInfo(listOf("nyc", "mobility"), "hvfhv_trips", "VIEW", "parquet"),
                        TableInfo(listOf("trade", "root"), "orders", "VIEW", "iceberg"),
                    )
            },
            { page.truncated shouldBe false },
            // The wire's derived `schema` is the innermost segment, as on every dialect.
            { page.tables.first().schema shouldBe "mobility" },
        )
    }

    @Test
    fun `a namespace filter matches exactly - a head segment alone selects nothing`() {
        val (introspector, ds) = introspectorOver(listOf(zoneDay, trips, orders))

        assertAll(
            {
                introspector.tables(ds, namespaceFilter = listOf("nyc", "mobility")).tables shouldContainExactly
                    listOf(
                        TableInfo(listOf("nyc", "mobility"), "hvfhv_zone_day", "VIEW", "parquet"),
                        TableInfo(listOf("nyc", "mobility"), "hvfhv_trips", "VIEW", "parquet"),
                    )
            },
            // Exact-match semantics: ["nyc"] names a one-segment namespace, not every namespace
            // under the nyc head — a prefix match would merge nyc and nyc.mobility into one list.
            { introspector.tables(ds, namespaceFilter = listOf("nyc")).tables shouldBe emptyList() },
            // Deeper than the two-segment shape names no real place: empty, like route().
            { introspector.tables(ds, namespaceFilter = listOf("a", "b", "c")).tables shouldBe emptyList() },
            // The pre-087 dotted spelling parses to the same filter.
            {
                introspector.tables(ds, schemaFilter = "trade.root").tables shouldContainExactly
                    listOf(TableInfo(listOf("trade", "root"), "orders", "VIEW", "iceberg"))
            },
        )
    }

    // ---------------------------------------------------------- columns (live, through the view)

    @Test
    fun `columns come from the registered table's view, typed by the DuckDB mapper`() {
        val parquet = seedParquet()
        val rows = listOf(LakeRegisteredTable(listOf("nyc", "mobility"), "trips", "parquet", "file://${parquet.absolutePath}"))
        val ds = lakeDatasource()
        val pool = ConnectionPoolManager.buildHikariPool(ds, LakeViewStatements.forTables(rows, adapter))
        val (introspector, _) = introspectorOver(rows, pool = pool)

        val columns = introspector.columns(ds, "trips", namespaceFilter = listOf("nyc", "mobility"))

        assertAll(
            { columns.map { it.column.name } shouldContainExactly listOf("id", "label", "fare") },
            { columns.map { it.column.type } shouldContainExactly listOf(LogicalType.INTEGER, LogicalType.STRING, LogicalType.DECIMAL) },
            // The source names travel for the wire's source_type field.
            { columns.map { it.sourceTypeName } shouldContainExactly listOf("INTEGER", "VARCHAR", "DECIMAL(10,2)") },
            // DECIMAL's precision/scale survived — the reason this path reads ResultSetMetaData
            // rather than DESCRIBE's strings.
            { columns[2].column.precision shouldBe 10 },
            { columns[2].column.scale shouldBe 2 },
        )
    }

    @Test
    fun `unfiltered columns resolve through the single-namespace rule - several namespaces refuse`() {
        val parquet = seedParquet()
        val single = listOf(LakeRegisteredTable(listOf("nyc", "mobility"), "trips", "parquet", "file://${parquet.absolutePath}"))
        val ds = lakeDatasource()
        val pool = ConnectionPoolManager.buildHikariPool(ds, LakeViewStatements.forTables(single, adapter))
        val (introspector, _) = introspectorOver(single, pool = pool)

        assertAll(
            // One registered namespace: the unfiltered read resolves there (the search_path
            // rule's twin).
            { introspector.columns(ds, "trips").map { it.column.name } shouldContainExactly listOf("id", "label", "fare") },
            // An unregistered table is empty, never an error.
            { introspector.columns(ds, "ghost", namespaceFilter = listOf("nyc", "mobility")) shouldBe emptyList() },
        )
    }

    @Test
    fun `unfiltered columns with several registered namespaces fail with the unknown-current-schema guard`() {
        val (introspector, ds) = introspectorOver(listOf(zoneDay, orders))

        shouldThrow<CurrentSchemaUnknownException> { introspector.columns(ds, "hvfhv_zone_day") }
    }

    @Test
    fun `unfiltered columns on a tableless registry are empty`() {
        val (introspector, ds) = introspectorOver(emptyList())

        introspector.columns(ds, "anything") shouldBe emptyList()
    }

    // ---------------------------------------------------------- the cache seam

    @Test
    fun `registry reads are served from the cache until invalidated`() {
        var reads = 0
        val catalog =
            LakeTableCatalog {
                reads++
                listOf(zoneDay, orders)
            }
        val cache = LakeIntrospectionCache()
        val ds = lakeDatasource()
        val registry = mockk<DatasourceRegistry>()
        every { registry.get(ds.name) } returns ds
        val introspector = SchemaIntrospector(registry, catalog, cache)

        introspector.schemas(ds)
        introspector.schemas(ds)
        withClue("the second schemas read hit the registry again instead of the cache") {
            reads shouldBe 1
        }

        // What LakeTableRegistryService.refreshConnections runs on every registry mutation.
        cache.invalidate(ds.name)
        introspector.schemas(ds)
        reads shouldBe 2
    }

    private fun seedParquet(): File {
        val parquet = tempDir.resolve("trips.parquet")
        DriverManager.getConnection("jdbc:duckdb:").use { writer ->
            writer.createStatement().use {
                it.execute(
                    "COPY (SELECT 1 AS id, 'a' AS label, CAST(12.50 AS DECIMAL(10,2)) AS fare " +
                        "UNION ALL SELECT 2, 'b', CAST(9.99 AS DECIMAL(10,2))) TO '${parquet.absolutePath}' (FORMAT PARQUET)",
                )
            }
        }
        return parquet
    }
}
