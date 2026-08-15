package co.datapipelines.datasources

import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException

/**
 * §7A introspection over a **real** in-memory H2 behind a mocked registry — the metadata comes
 * from a live JDBC driver, not from fixtures, so these tests exercise the actual
 * `DatabaseMetaData` column names and nullability codes the production path reads.
 */
class SchemaIntrospectorTest {
    private val h2 = DriverManager.getConnection("jdbc:h2:mem:introspect;DB_CLOSE_DELAY=-1")
    private val registry = mockk<DatasourceRegistry>()
    private val introspector = SchemaIntrospector(registry)

    /** A pool that hands out fresh connections to the same named in-memory DB. */
    private val pool =
        object : ConnectionPool {
            override val name: String = "h2-test"

            override fun leaseConnection(): Connection = DriverManager.getConnection("jdbc:h2:mem:introspect;DB_CLOSE_DELAY=-1")

            override fun close() = Unit
        }

    private fun datasource(): Datasource = Fixtures.h2(name = "h2-test")

    @AfterEach
    fun tearDown() {
        h2.createStatement().use { it.execute("DROP ALL OBJECTS") }
    }

    @Test
    fun `tables lists a created table with its schema`() {
        h2.createStatement().use { it.execute("CREATE TABLE orders (id INT PRIMARY KEY, amount DECIMAL(10,2))") }
        val ds = datasource()
        every { registry.get("h2-test") } returns ds
        every { registry.poolFor(ds) } returns pool

        val tables = introspector.tables("h2-test").tables

        // H2 reports the SQL-standard spelling "BASE TABLE" for a plain table; the introspector
        // passes the driver's raw TABLE_TYPE through untouched (§7A: raw JDBC table type).
        assertAll(
            { tables.any { it.name.equals("orders", ignoreCase = true) } shouldBe true },
            { tables.first { it.name.equals("orders", ignoreCase = true) }.type shouldBe "BASE TABLE" },
            { tables.first { it.name.equals("orders", ignoreCase = true) }.schema shouldBe "PUBLIC" },
        )
    }

    @Test
    fun `tables is capped and flags truncation`() {
        h2.createStatement().use { st -> (1..3).forEach { st.execute("CREATE TABLE t$it (id INT)") } }
        val ds = datasource()
        every { registry.get("h2-test") } returns ds
        every { registry.poolFor(ds) } returns pool

        val capped = introspector.tables("h2-test", maxTables = 2)
        val uncapped = introspector.tables("h2-test")

        assertAll(
            { capped.tables.size shouldBe 2 },
            { capped.truncated shouldBe true },
            { uncapped.tables.size shouldBe 3 },
            { uncapped.truncated shouldBe false },
        )
    }

    @Test
    fun `tables stops iterating at cap plus one row`() {
        // The listing must not walk a huge catalog to its end: after cap+1 `next()` calls the
        // iteration stops (cap rows kept, the +1 proves truncation).
        val meta = mockk<DatabaseMetaData>()
        val tablesRs = mockk<ResultSet>(relaxed = true)
        every { meta.searchStringEscape } returns "\\"
        every { meta.getTables(null, null, "%", any<Array<String>>()) } returns tablesRs
        every { tablesRs.next() } returns true
        val (introspector, name) = introspectorOverMockedMetadata(Dialect.H2, meta)

        val page = introspector.tables(name, maxTables = 2)

        assertAll(
            { io.mockk.verify(exactly = 3) { tablesRs.next() } },
            { page.tables.size shouldBe 2 },
            { page.truncated shouldBe true },
        )
    }

    @Test
    fun `columns maps JDBC metadata through the dialect type mapper`() {
        h2.createStatement().use { it.execute("CREATE TABLE orders (id INT NOT NULL, amount DECIMAL(10,2))") }
        val ds = datasource()
        every { registry.get("h2-test") } returns ds
        every { registry.poolFor(ds) } returns pool

        // The name comes from tables(), exactly as the §7A caller contract documents — JDBC
        // metadata name patterns are case-sensitive and H2 stores unquoted identifiers uppercased.
        val name =
            introspector
                .tables("h2-test")
                .tables
                .first { it.name.equals("orders", ignoreCase = true) }
                .name
        val columns = introspector.columns("h2-test", name)

        assertAll(
            { columns.size shouldBe 2 },
            { columns[0].column.name.equals("id", ignoreCase = true) shouldBe true },
            { columns[0].column.nullable shouldBe false },
            { columns[1].sourceTypeName.isNotBlank() shouldBe true },
        )
    }

    @Test
    fun `an unknown table returns an empty list, not an error`() {
        val ds = datasource()
        every { registry.get("h2-test") } returns ds
        every { registry.poolFor(ds) } returns pool

        introspector.columns("h2-test", "no_such_table") shouldBe emptyList()
    }

    @Test
    fun `an unknown schema filter matches nothing rather than failing`() {
        h2.createStatement().use { it.execute("CREATE TABLE orders (id INT PRIMARY KEY)") }
        val ds = datasource()
        every { registry.get("h2-test") } returns ds
        every { registry.poolFor(ds) } returns pool

        assertAll(
            { introspector.tables("h2-test", schemaFilter = "no_such_schema").tables shouldBe emptyList() },
            { introspector.columns("h2-test", "orders", schemaFilter = "no_such_schema") shouldBe emptyList() },
        )
    }

    @Test
    fun `an unknown datasource is the catalogued not-found`() {
        every { registry.get("nope") } returns null

        shouldThrow<DatapipelinesException> { introspector.tables("nope") }
            .code shouldBe DatasourceErrorCodes.NOT_FOUND
    }

    @Test
    fun `tables excludes the driver's system schemas`() {
        // H2 keeps its catalog in INFORMATION_SCHEMA; those rows must not leak into the
        // listing (pre-fix they ride along on the VIEW type and eat the snapshot cap).
        h2.createStatement().use { it.execute("CREATE TABLE orders (id INT PRIMARY KEY)") }
        val ds = datasource()
        every { registry.get("h2-test") } returns ds
        every { registry.poolFor(ds) } returns pool

        val tables = introspector.tables("h2-test").tables

        tables.none { it.schema?.equals("INFORMATION_SCHEMA", ignoreCase = true) == true } shouldBe true
    }

    @Test
    fun `snapshot excludes the driver's system schemas`() {
        h2.createStatement().use { st -> (1..3).forEach { st.execute("CREATE TABLE t$it (id INT)") } }
        val ds = datasource()
        every { registry.get("h2-test") } returns ds
        every { registry.poolFor(ds) } returns pool

        val snapshot = introspector.snapshot("h2-test", maxTables = 3)

        snapshot.tables.none { it.table.schema?.equals("INFORMATION_SCHEMA", ignoreCase = true) == true } shouldBe true
    }

    @Test
    fun `snapshot flags truncation when the table count exceeds the cap`() {
        h2.createStatement().use { st -> (1..3).forEach { st.execute("CREATE TABLE t$it (id INT)") } }
        val ds = datasource()
        every { registry.get("h2-test") } returns ds
        every { registry.poolFor(ds) } returns pool

        val snapshot = introspector.snapshot("h2-test", maxTables = 2)

        assertAll(
            { snapshot.tables.size shouldBe 2 },
            { snapshot.truncated shouldBe true },
            { snapshot.dialect shouldBe "H2" },
            { snapshot.tables.all { it.columns.isNotEmpty() } shouldBe true },
        )
    }

    @Test
    fun `snapshot leases ONE connection - tables and bulk columns on a single consistent lease`() {
        // One lease per table (up to 201 for a full snapshot) starves the pool and reads the
        // schema across connections that may disagree mid-flight. The snapshot must be one
        // getTables + one bulk getColumns on a single connection.
        h2.createStatement().use { st -> (1..3).forEach { st.execute("CREATE TABLE t$it (id INT, note VARCHAR(10))") } }
        var leases = 0
        val countingPool =
            object : ConnectionPool {
                override val name: String = "h2-test"

                override fun leaseConnection(): Connection {
                    leases++
                    return DriverManager.getConnection("jdbc:h2:mem:introspect;DB_CLOSE_DELAY=-1")
                }

                override fun close() = Unit
            }
        val ds = datasource()
        every { registry.get("h2-test") } returns ds
        every { registry.poolFor(ds) } returns countingPool

        val snapshot = introspector.snapshot("h2-test", maxTables = 2)

        assertAll(
            { leases shouldBe 1 },
            { snapshot.tables.size shouldBe 2 },
            { snapshot.truncated shouldBe true },
            { snapshot.tables.all { it.columns.size == 2 } shouldBe true },
        )
    }

    @Test
    fun `a table filter is exact-match - underscore is not a wildcard`() {
        // `ORDER_ITEMS` and `ORDER1ITEMS` are wildcard siblings: as a raw JDBC LIKE pattern,
        // "ORDER_ITEMS" also matches "ORDER1ITEMS" (`_` = any single character), merging the
        // two tables' columns. Escaping via getSearchStringEscape() must keep them apart.
        h2.createStatement().use { st ->
            st.execute("CREATE TABLE order_items (id INT, items_note VARCHAR(30))")
            st.execute("CREATE TABLE order1items (id INT, rogue_flag INT)")
        }
        val ds = datasource()
        every { registry.get("h2-test") } returns ds
        every { registry.poolFor(ds) } returns pool

        val columns = introspector.columns("h2-test", "ORDER_ITEMS")

        assertAll(
            { columns.map { it.column.name.uppercase() } shouldContainExactly listOf("ID", "ITEMS_NOTE") },
            {
                introspector.columns("h2-test", "ORDER1ITEMS").map { it.column.name.uppercase() } shouldContainExactly
                    listOf("ID", "ROGUE_FLAG")
            },
        )
    }

    @Test
    fun `a schema filter is exact-match - underscore does not match its sibling schemas`() {
        // The filter itself carries the `_`: as a raw LIKE pattern "MY_SCHEMA" also matches
        // "MYOSCHEMA". Exact-match must keep the sibling out.
        h2.createStatement().use { st ->
            st.execute("CREATE SCHEMA my_schema")
            st.execute("CREATE SCHEMA myoschema")
            st.execute("CREATE TABLE my_schema.deals (id INT)")
            st.execute("CREATE TABLE myoschema.ledger (id INT)")
        }
        val ds = datasource()
        every { registry.get("h2-test") } returns ds
        every { registry.poolFor(ds) } returns pool

        val tables = introspector.tables("h2-test", schemaFilter = "MY_SCHEMA").tables

        tables.map { it.schema?.uppercase() } shouldContainExactly listOf("MY_SCHEMA")
    }

    @Test
    fun `snapshot does not cross-contaminate wildcard sibling tables`() {
        h2.createStatement().use { st ->
            st.execute("CREATE TABLE order_items (id INT, items_note VARCHAR(30))")
            st.execute("CREATE TABLE order1items (id INT, rogue_flag INT)")
        }
        val ds = datasource()
        every { registry.get("h2-test") } returns ds
        every { registry.poolFor(ds) } returns pool

        val items =
            introspector
                .snapshot("h2-test")
                .tables
                .first { it.table.name.equals("ORDER_ITEMS", ignoreCase = true) }

        items.columns.map { it.column.name.uppercase() } shouldContainExactly listOf("ID", "ITEMS_NOTE")
    }

    @Test
    fun `mysql routes the schema filter to the catalog argument and reads TABLE_CAT as the schema`() {
        // Connector/J defaults: the database arrives in TABLE_CAT, TABLE_SCHEM is null — a
        // schemaPattern selects nothing. The filter must land in the catalog argument.
        val meta = mockk<DatabaseMetaData>()
        val tablesRs = mockk<ResultSet>(relaxed = true)
        every { meta.searchStringEscape } returns "\\"
        every { meta.getTables("app", null, "%", any<Array<String>>()) } returns tablesRs
        every { tablesRs.next() } returns true andThen false
        every { tablesRs.getString("TABLE_CAT") } returns "app"
        every { tablesRs.getString("TABLE_NAME") } returns "orders"
        every { tablesRs.getString("TABLE_TYPE") } returns "TABLE"
        val columnsRs = mockk<ResultSet>(relaxed = true)
        every { meta.getColumns("app", null, "orders", "%") } returns columnsRs
        every { columnsRs.next() } returns false

        val (introspector, name) = introspectorOverMockedMetadata(Dialect.MYSQL, meta)

        assertAll(
            {
                introspector
                    .tables(name, schemaFilter = "app")
                    .tables
                    .single()
                    .schema shouldBe "app"
            },
            { introspector.columns(name, "orders", schemaFilter = "app") shouldBe emptyList() },
        )
    }

    @Test
    fun `schema-filtered dialects keep the filter in the schemaPattern argument`() {
        // The non-MySQL world: TABLE_SCHEM carries the schema and the filter stays in the
        // schemaPattern argument — routing must not move it for them.
        val meta = mockk<DatabaseMetaData>()
        val tablesRs = mockk<ResultSet>(relaxed = true)
        every { meta.searchStringEscape } returns "\\"
        every { meta.getTables(null, "public", "%", any<Array<String>>()) } returns tablesRs
        every { tablesRs.next() } returns true andThen false
        every { tablesRs.getString("TABLE_SCHEM") } returns "public"
        every { tablesRs.getString("TABLE_NAME") } returns "orders"
        every { tablesRs.getString("TABLE_TYPE") } returns "TABLE"

        val (introspector, name) = introspectorOverMockedMetadata(Dialect.POSTGRES, meta)

        introspector
            .tables(name, schemaFilter = "public")
            .tables
            .single()
            .schema shouldBe "public"
    }

    @Test
    fun `a pool-build failure is the module's unreachable - not an escaping RuntimeException`() {
        // The true Hikari path for a down database: `poolFor`'s `computeIfAbsent` builds the
        // pool and `HikariDataSource` construction throws PoolInitializationException — a
        // RuntimeException, NOT an SQLException. Round 1 caught only SQLException at the
        // surfaces, so this escaped as a raw 500 / -32603.
        val ds = datasource()
        val poolFailure =
            com.zaxxer.hikari.pool.HikariPool.PoolInitializationException(
                RuntimeException("Failed to get driver instance for jdbcUrl"),
            )
        every { registry.get("h2-test") } returns ds
        every { registry.poolFor(ds) } throws poolFailure

        val thrown =
            shouldThrow<DatasourceUnreachableException> {
                introspector.tables("h2-test")
            }

        assertAll(
            { thrown.datasourceName shouldBe "h2-test" },
            { thrown.cause shouldBe poolFailure },
        )
    }

    @Test
    fun `a lease failure SQLException is the module's unreachable`() {
        val ds = datasource()
        every { registry.get("h2-test") } returns ds
        every { registry.poolFor(ds) } returns
            object : ConnectionPool {
                override val name: String = "h2-test"

                override fun leaseConnection(): Connection = throw SQLException("Connection refused")

                override fun close() = Unit
            }

        shouldThrow<DatasourceUnreachableException> { introspector.snapshot("h2-test") }
            .cause
            ?.message shouldBe "Connection refused"
    }

    @Test
    fun `a RuntimeException from the metadata walk itself is NOT translated to unreachable`() {
        // The lease boundary translates; a defect in the walk (or a driver bug) stays what it
        // is — masking it as "datasource unreachable" would hide our own bugs.
        val meta = mockk<DatabaseMetaData>()
        every { meta.getTables(null, null, "%", any<Array<String>>()) } throws IllegalStateException("walk bug")
        val (introspector, name) = introspectorOverMockedMetadata(Dialect.H2, meta)

        shouldThrow<IllegalStateException> { introspector.tables(name) }
    }

    @Test
    fun `columns without a schema filter defaults to the connection's current schema`() {
        // Same-named tables in two schemas: an unfiltered getColumns merges BOTH tables'
        // columns into one list. The default is the connection's current schema (here PUBLIC
        // for a fresh H2 lease), so `DEALS` unambiguously means the current schema's DEALS.
        h2.createStatement().use { st ->
            st.execute("CREATE SCHEMA other")
            st.execute("CREATE TABLE deals (id INT, amount DECIMAL(10,2))")
            st.execute("CREATE TABLE other.deals (id INT, rogue_flag INT)")
        }
        val ds = datasource()
        every { registry.get("h2-test") } returns ds
        every { registry.poolFor(ds) } returns pool

        val columns = introspector.columns("h2-test", "DEALS")

        columns.map { it.column.name.uppercase() } shouldContainExactly listOf("ID", "AMOUNT")
    }

    @Test
    fun `columns excludes the driver's system schemas`() {
        // INFORMATION_SCHEMA.TABLES is a real system table H2 reports; a columns read must not
        // return its rows even when the schema is named explicitly (the house rule: unknown /
        // system schema matches nothing).
        val ds = datasource()
        every { registry.get("h2-test") } returns ds
        every { registry.poolFor(ds) } returns pool

        introspector.columns("h2-test", "TABLES", schemaFilter = "INFORMATION_SCHEMA") shouldBe emptyList()
    }

    @Test
    fun `columns reads the current schema from the schemaPattern argument for schema-filtered dialects`() {
        val meta = mockk<DatabaseMetaData>()
        val columnsRs = mockk<ResultSet>(relaxed = true)
        every { meta.searchStringEscape } returns "\\"
        every { meta.getColumns(null, "sales", "deals", "%") } returns columnsRs
        every { columnsRs.next() } returns false
        val (introspector, name) =
            introspectorOverMockedMetadata(Dialect.POSTGRES, meta) { connection ->
                every { connection.schema } returns "sales"
            }

        introspector.columns(name, "deals") shouldBe emptyList()

        io.mockk.verify(exactly = 1) { meta.getColumns(null, "sales", "deals", "%") }
    }

    @Test
    fun `columns reads the current schema from the catalog argument for catalog-routing dialects`() {
        // Connector/J keeps the current database in the CATALOG (getSchema() returns null under
        // the default databaseTerm) — the default must route exactly like the schema filter does.
        val meta = mockk<DatabaseMetaData>()
        val columnsRs = mockk<ResultSet>(relaxed = true)
        every { meta.searchStringEscape } returns "\\"
        every { meta.getColumns("app", null, "orders", "%") } returns columnsRs
        every { columnsRs.next() } returns false
        val (introspector, name) =
            introspectorOverMockedMetadata(Dialect.MYSQL, meta) { connection ->
                every { connection.catalog } returns "app"
            }

        introspector.columns(name, "orders") shouldBe emptyList()

        io.mockk.verify(exactly = 1) { meta.getColumns("app", null, "orders", "%") }
    }

    @Test
    fun `columns falls back to unfiltered minus system schemas when the driver reports no current schema`() {
        // A driver may return null (or throw SQLFeatureNotSupportedException) from
        // getSchema()/getCatalog() — the read is then unfiltered, with system schemas still
        // excluded row by row.
        assertAll(
            {
                val meta = mockk<DatabaseMetaData>()
                val columnsRs = mockk<ResultSet>(relaxed = true)
                every { meta.searchStringEscape } returns "\\"
                every { meta.getColumns(null, null, "deals", "%") } returns columnsRs
                every { columnsRs.next() } returns false
                val (introspector, name) =
                    introspectorOverMockedMetadata(Dialect.POSTGRES, meta) { connection ->
                        every { connection.schema } returns null
                    }

                introspector.columns(name, "deals") shouldBe emptyList()
                io.mockk.verify(exactly = 1) { meta.getColumns(null, null, "deals", "%") }
            },
            {
                val meta = mockk<DatabaseMetaData>()
                val columnsRs = mockk<ResultSet>(relaxed = true)
                every { meta.searchStringEscape } returns "\\"
                every { meta.getColumns(null, null, "deals", "%") } returns columnsRs
                every { columnsRs.next() } returns false
                val (introspector, name) =
                    introspectorOverMockedMetadata(Dialect.POSTGRES, meta) { connection ->
                        every { connection.schema } throws java.sql.SQLFeatureNotSupportedException()
                    }

                introspector.columns(name, "deals") shouldBe emptyList()
                io.mockk.verify(exactly = 1) { meta.getColumns(null, null, "deals", "%") }
            },
        )
    }

    @Test
    fun `snapshot stops the tables walk at cap plus one and never issues the server-wide bulk getColumns`() {
        // A huge catalog must cost cap+1 `next()` calls, not a full walk — and the old
        // server-wide `getColumns(null, null, "%", "%")` (every column of every schema,
        // including system schemas filtered only afterwards) must be gone entirely.
        val meta = mockk<DatabaseMetaData>()
        val tablesRs = mockk<ResultSet>(relaxed = true)
        every { meta.searchStringEscape } returns "\\"
        every { meta.getTables(null, null, "%", any<Array<String>>()) } returns tablesRs
        every { tablesRs.next() } returns true
        every { tablesRs.getString("TABLE_SCHEM") } returns "public"
        every { tablesRs.getString("TABLE_NAME") } returns "orders"
        every { tablesRs.getString("TABLE_TYPE") } returns "TABLE"
        val columnsRs = mockk<ResultSet>(relaxed = true)
        every { meta.getColumns(null, "public", "orders", "%") } returns columnsRs
        every { columnsRs.next() } returns false
        val (introspector, name) = introspectorOverMockedMetadata(Dialect.POSTGRES, meta)

        val snapshot = introspector.snapshot(name, maxTables = 2)

        assertAll(
            { io.mockk.verify(exactly = 3) { tablesRs.next() } },
            { snapshot.truncated shouldBe true },
            { io.mockk.verify(exactly = 0) { meta.getColumns(null, null, "%", "%") } },
            { io.mockk.verify(exactly = 2) { meta.getColumns(null, "public", "orders", "%") } },
        )
    }

    @Test
    fun `snapshot reads each table's columns with that table's own reported schema`() {
        // Per-table attribution: two same-named tables in two schemas must each get THEIR OWN
        // columns, read via a per-table getColumns carrying the table's own schema.
        h2.createStatement().use { st ->
            st.execute("CREATE SCHEMA sa")
            st.execute("CREATE SCHEMA sb")
            st.execute("CREATE TABLE sa.deals (sa_col INT)")
            st.execute("CREATE TABLE sb.deals (sb_col INT)")
        }
        val ds = datasource()
        every { registry.get("h2-test") } returns ds
        every { registry.poolFor(ds) } returns pool

        val snapshot = introspector.snapshot("h2-test")

        val bySchema = snapshot.tables.associateBy { it.table.schema?.uppercase() }
        assertAll(
            { bySchema.keys shouldContainExactly setOf("SA", "SB") },
            { bySchema.getValue("SA").columns.map { it.column.name.uppercase() } shouldContainExactly listOf("SA_COL") },
            { bySchema.getValue("SB").columns.map { it.column.name.uppercase() } shouldContainExactly listOf("SB_COL") },
        )
    }

    @Test
    fun `snapshot routes each table's columns through the catalog for catalog-routing dialects`() {
        // The per-table read routes each table's own reported schema exactly like the filters:
        // for Connector/J the schema column IS TABLE_CAT, so it must land in the catalog
        // argument — the old bulk read keyed rows by TABLE_SCHEM (null for MySQL) and every
        // table silently reported zero columns via orEmpty().
        val meta = mockk<DatabaseMetaData>()
        val tablesRs = mockk<ResultSet>(relaxed = true)
        every { meta.searchStringEscape } returns "\\"
        every { meta.getTables(null, null, "%", any<Array<String>>()) } returns tablesRs
        every { tablesRs.next() } returns true andThen false
        every { tablesRs.getString("TABLE_CAT") } returns "app"
        every { tablesRs.getString("TABLE_NAME") } returns "orders"
        every { tablesRs.getString("TABLE_TYPE") } returns "TABLE"
        val columnsRs = mockk<ResultSet>(relaxed = true)
        every { meta.getColumns("app", null, "orders", "%") } returns columnsRs
        every { columnsRs.next() } returns false
        val (introspector, name) = introspectorOverMockedMetadata(Dialect.MYSQL, meta)

        val snapshot = introspector.snapshot(name)

        io.mockk.verify(exactly = 1) { meta.getColumns("app", null, "orders", "%") }
        snapshot.tables.single().columns shouldBe emptyList()
    }

    /** An introspector whose registry hands out a connection with the given mocked metadata. Returns (introspector, datasource name). */
    private fun introspectorOverMockedMetadata(
        dialect: Dialect,
        meta: DatabaseMetaData,
        connectionSetup: (Connection) -> Unit = {},
    ): Pair<SchemaIntrospector, String> {
        val ds = Fixtures.forDialect(dialect)
        val connection = mockk<Connection>()
        every { connection.metaData } returns meta
        every { connection.close() } returns Unit
        connectionSetup(connection)
        val registry = mockk<DatasourceRegistry>()
        every { registry.get(ds.name) } returns ds
        every { registry.poolFor(ds) } returns
            object : ConnectionPool {
                override val name: String = ds.name

                override fun leaseConnection(): Connection = connection

                override fun close() = Unit
            }
        return SchemaIntrospector(registry) to ds.name
    }
}
