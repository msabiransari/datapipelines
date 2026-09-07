package co.datapipelines.datasources

import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * §7A namespaces against a REAL two-catalog engine (datasources.md §4.2/§7A, round 087).
 *
 * ## Why DuckDB, and why a direct connection
 *
 * The contract audit INFERRED that a listing of bare schema names would merge two same-named
 * schemas in different catalogs. Inference is not evidence, so this suite reproduces the merge on
 * a live engine: two DuckDB database FILES, each holding a `sales.orders` with a
 * differently-named column, `ATTACH`ed into one connection as `a1` and `a2`. That is exactly the
 * shape every reference target has — Snowflake `database.schema`, Databricks `catalog.schema`,
 * BigQuery `project.dataset` — and the only two-level engine this build already ships a driver
 * for.
 *
 * The connection is opened directly rather than through the registry's pool because the embedded
 * DuckDB adapter locks `enable_external_access = false`, which forbids `ATTACH` outright
 * (that lock is the whole difference between the embedded adapter and the lake one). What is
 * under test here is the INTROSPECTOR's namespace handling, not the adapter's hardening — the
 * hardening has its own suite, and pointing this one at a pool would test the lock instead.
 *
 * ## The red-then-green claim
 *
 * `columns()` without a namespace filter is the sharp end. Before 087 the catalog argument was a
 * hard-coded `null`, and `getColumns(null, "sales", "orders", "%")` on this fixture returns
 * `id, one_col, id, two_col` — BOTH tables' columns, presented as one table's. An authoring agent
 * handed that list writes SQL against columns the table it named does not have. Qualified by
 * catalog, each read returns its own table's columns, and [duckDbMergesWithoutTheCatalog] pins
 * the raw driver behaviour so the assertion below cannot quietly become vacuous if a future
 * driver stops merging.
 */
class SchemaIntrospectorNamespaceTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `two catalogs' same-named schemas stay DISTINCT in the listing`() {
        val (introspector, name) = twoCatalogDuckDb()

        val entries = introspector.schemas(name).entries

        assertAll(
            // Both catalogs' `sales` survive as separate entries — the merge the audit predicted.
            {
                entries.map { it.namespace } shouldContainExactlyInAnyOrder
                    listOf(
                        listOf("a1", "main"),
                        listOf("a1", "sales"),
                        listOf("a2", "main"),
                        listOf("a2", "sales"),
                        listOf("memory", "main"),
                    )
            },
            // The pre-087 projection is exactly what it always was — and exactly why it was not
            // enough: two `sales`, two `main`, nothing to tell them apart.
            { entries.filter { it.label == "sales" } shouldContainExactly entries.filter { it.label == "sales" } },
            { entries.count { it.label == "sales" } shouldBe 2 },
            // DuckDB's own catalogs are gone: `system.main` and `temp.main` are engine internals
            // that a bare `main` exclusion could not remove without hiding every user schema.
            { entries.none { it.namespace.first() in setOf("system", "temp") } shouldBe true },
        )
    }

    @Test
    fun `tables carry their catalog, and a namespace filter selects ONE of the two`() {
        val (introspector, name) = twoCatalogDuckDb()

        val all = introspector.tables(name)
        val onlyA2 = introspector.tables(name, namespaceFilter = listOf("a2", "sales"))

        assertAll(
            {
                all.tables.map { it.namespace to it.name } shouldContainExactlyInAnyOrder
                    listOf(listOf("a1", "sales") to "orders", listOf("a2", "sales") to "orders")
            },
            // The derived field a pre-087 client reads: the LAST segment, still `sales` for both.
            { all.tables.map { it.schema }.distinct() shouldContainExactly listOf("sales") },
            { onlyA2.tables.map { it.namespace to it.name } shouldContainExactly listOf(listOf("a2", "sales") to "orders") },
        )
    }

    @Test
    fun `the dotted shorthand and the array form select the same place`() {
        val (introspector, name) = twoCatalogDuckDb()

        val array = introspector.tables(name, namespaceFilter = listOf("a1", "sales")).tables
        val dotted = introspector.tables(name, schemaFilter = "a1.sales").tables

        dotted shouldContainExactly array
    }

    @Test
    fun `a namespace DEEPER than the dialect's shape matches nothing rather than dropping segments`() {
        val (introspector, name) = twoCatalogDuckDb()

        // DuckDB is two levels. Three names no real place; silently reading `sales.orders` from
        // whatever catalog happened to answer would be a different question answered confidently.
        introspector.tables(name, namespaceFilter = listOf("nope", "a1", "sales")).tables shouldBe emptyList()
        introspector.columns(name, "orders", namespaceFilter = listOf("nope", "a1", "sales")) shouldBe emptyList()
    }

    @Test
    fun `columns qualified by catalog return that table's OWN columns - the merge the audit predicted`() {
        val (introspector, name) = twoCatalogDuckDb()

        val fromA1 = introspector.columns(name, "orders", namespaceFilter = listOf("a1", "sales")).map { it.column.name }
        val fromA2 = introspector.columns(name, "orders", namespaceFilter = listOf("a2", "sales")).map { it.column.name }

        assertAll(
            { fromA1 shouldContainExactly listOf("id", "one_col") },
            { fromA2 shouldContainExactly listOf("id", "two_col") },
        )
    }

    @Test
    fun duckDbMergesWithoutTheCatalog() {
        // The falsification anchor, on the RAW driver: this is what the pre-087 read did, and it
        // is what makes the test above a fix rather than a coincidence. If a future duckdb_jdbc
        // stops merging here, this test fails and the one above stops proving anything — which is
        // the signal to re-derive the claim rather than to keep asserting it.
        connectionWithTwoCatalogs().use { connection ->
            val merged =
                connection.metaData.getColumns(null, "sales", "orders", "%").use { rs ->
                    buildList { while (rs.next()) add(rs.getString("COLUMN_NAME")) }
                }
            withClue("duckdb_jdbc no longer merges an unqualified getColumns across catalogs") {
                merged shouldContainExactly listOf("id", "one_col", "id", "two_col")
            }
        }
    }

    /**
     * An introspector over a DuckDB connection with two ATTACHed database files, plus the
     * datasource name to address it by. The registry is a fake: the point is the metadata walk,
     * and a real pool would apply the embedded adapter's `ATTACH`-forbidding lock.
     */
    private fun twoCatalogDuckDb(): Pair<SchemaIntrospector, String> {
        val datasource = Fixtures.forDialect(Dialect.DUCKDB, name = "lake_probe")
        val connection = connectionWithTwoCatalogs()
        val registry = mockk<DatasourceRegistry>()
        every { registry.get(datasource.name) } returns datasource
        every { registry.poolFor(datasource) } returns
            object : ConnectionPool {
                override val name: String = datasource.name

                // The introspector closes the lease; a per-call connection would lose the ATTACHes.
                override fun leaseConnection(): Connection = NonClosingConnection.wrap(connection)

                override fun close() = Unit
            }
        return SchemaIntrospector(registry) to datasource.name
    }

    /** `:memory:` DuckDB with `a1` and `a2` attached — one `sales.orders` each, different columns. */
    private fun connectionWithTwoCatalogs(): Connection {
        listOf("one" to "one_col", "two" to "two_col").forEach { (file, column) ->
            DriverManager.getConnection("jdbc:duckdb:${tempDir.resolve("$file.db")}").use { seed ->
                seed.createStatement().use {
                    it.execute("CREATE SCHEMA sales")
                    it.execute("CREATE TABLE sales.orders(id INTEGER, $column VARCHAR)")
                }
            }
        }
        val connection = DriverManager.getConnection("jdbc:duckdb:")
        connection.createStatement().use {
            it.execute("ATTACH '${tempDir.resolve("one.db")}' AS a1 (READ_ONLY)")
            it.execute("ATTACH '${tempDir.resolve("two.db")}' AS a2 (READ_ONLY)")
        }
        return connection
    }
}

/**
 * A [Connection] proxy whose `close()` is a no-op, so one physical connection can back several
 * introspector leases. Everything else delegates. Needed because the ATTACHed catalogs live on
 * the connection, and the introspector correctly closes what it leases.
 */
private object NonClosingConnection {
    fun wrap(delegate: Connection): Connection =
        java.lang.reflect.Proxy
            .newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf(Connection::class.java),
            ) { _, method, args ->
                if (method.name == "close") null else method.invoke(delegate, *(args ?: emptyArray()))
            } as Connection
}
