package co.datapipelines.datasources

import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.sql.DriverManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/**
 * §7A introspection behavior over a **real** in-memory H2 — the metadata comes from a live JDBC
 * driver, not from fixtures, so these tests exercise the actual `DatabaseMetaData` column names,
 * case handling and nullability codes the production path reads. (The dialect-routing and
 * lease/cap seams live in [SchemaIntrospectorRoutingTest] and [SchemaIntrospectorCapAndLeaseTest].)
 */
class SchemaIntrospectorH2Test {
    private val h2 = DriverManager.getConnection("jdbc:h2:mem:introspect;DB_CLOSE_DELAY=-1")
    private val registry = mockk<DatasourceRegistry>()
    private val introspector = SchemaIntrospector(registry)

    /** A pool that hands out fresh connections to the same named in-memory DB. */
    private val pool = JdbcUrlPool("jdbc:h2:mem:introspect;DB_CLOSE_DELAY=-1", "h2-test")

    private fun datasource(): Datasource = Fixtures.h2(name = "h2-test")

    private fun wireDatasource() {
        val ds = datasource()
        every { registry.get("h2-test") } returns ds
        every { registry.poolFor(ds) } returns pool
    }

    @AfterEach
    fun tearDown() {
        h2.createStatement().use { it.execute("DROP ALL OBJECTS") }
    }

    @Test
    fun `tables lists a created table with its schema`() {
        h2.createStatement().use { it.execute("CREATE TABLE orders (id INT PRIMARY KEY, amount DECIMAL(10,2))") }
        wireDatasource()

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
        wireDatasource()

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
    fun `columns maps JDBC metadata through the dialect type mapper`() {
        h2.createStatement().use { it.execute("CREATE TABLE orders (id INT NOT NULL, amount DECIMAL(10,2))") }
        wireDatasource()

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
        wireDatasource()

        introspector.columns("h2-test", "no_such_table") shouldBe emptyList()
    }

    @Test
    fun `an unknown schema filter matches nothing rather than failing`() {
        h2.createStatement().use { it.execute("CREATE TABLE orders (id INT PRIMARY KEY)") }
        wireDatasource()

        assertAll(
            { introspector.tables("h2-test", schemaFilter = "no_such_schema").tables shouldBe emptyList() },
            { introspector.columns("h2-test", "orders", schemaFilter = "no_such_schema") shouldBe emptyList() },
        )
    }

    @Test
    fun `tables excludes the driver's system schemas`() {
        // H2 keeps its catalog in INFORMATION_SCHEMA; those rows must not leak into the
        // listing (pre-fix they ride along on the VIEW type and eat the snapshot cap).
        h2.createStatement().use { it.execute("CREATE TABLE orders (id INT PRIMARY KEY)") }
        wireDatasource()

        val tables = introspector.tables("h2-test").tables

        tables.none { it.schema?.equals("INFORMATION_SCHEMA", ignoreCase = true) == true } shouldBe true
    }

    @Test
    fun `snapshot excludes the driver's system schemas`() {
        h2.createStatement().use { st -> (1..3).forEach { st.execute("CREATE TABLE t$it (id INT)") } }
        wireDatasource()

        val snapshot = introspector.snapshot("h2-test", maxTables = 3)

        snapshot.tables.none { it.table.schema?.equals("INFORMATION_SCHEMA", ignoreCase = true) == true } shouldBe true
    }

    @Test
    fun `snapshot flags truncation when the table count exceeds the cap`() {
        h2.createStatement().use { st -> (1..3).forEach { st.execute("CREATE TABLE t$it (id INT)") } }
        wireDatasource()

        val snapshot = introspector.snapshot("h2-test", maxTables = 2)

        assertAll(
            { snapshot.tables.size shouldBe 2 },
            { snapshot.truncated shouldBe true },
            { snapshot.dialect shouldBe "H2" },
            { snapshot.tables.all { it.columns.isNotEmpty() } shouldBe true },
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
        wireDatasource()

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
        wireDatasource()

        val tables = introspector.tables("h2-test", schemaFilter = "MY_SCHEMA").tables

        tables.map { it.schema?.uppercase() } shouldContainExactly listOf("MY_SCHEMA")
    }

    @Test
    fun `snapshot does not cross-contaminate wildcard sibling tables`() {
        h2.createStatement().use { st ->
            st.execute("CREATE TABLE order_items (id INT, items_note VARCHAR(30))")
            st.execute("CREATE TABLE order1items (id INT, rogue_flag INT)")
        }
        wireDatasource()

        val items =
            introspector
                .snapshot("h2-test")
                .tables
                .first { it.table.name.equals("ORDER_ITEMS", ignoreCase = true) }

        items.columns.map { it.column.name.uppercase() } shouldContainExactly listOf("ID", "ITEMS_NOTE")
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
        wireDatasource()

        val columns = introspector.columns("h2-test", "DEALS")

        columns.map { it.column.name.uppercase() } shouldContainExactly listOf("ID", "AMOUNT")
    }

    @Test
    fun `columns excludes the driver's system schemas`() {
        // INFORMATION_SCHEMA.TABLES is a real system table H2 reports; a columns read must not
        // return its rows even when the schema is named explicitly (the house rule: unknown /
        // system schema matches nothing).
        wireDatasource()

        introspector.columns("h2-test", "TABLES", schemaFilter = "INFORMATION_SCHEMA") shouldBe emptyList()
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
        wireDatasource()

        val snapshot = introspector.snapshot("h2-test")

        val bySchema = snapshot.tables.associateBy { it.table.schema?.uppercase() }
        assertAll(
            { bySchema.keys shouldContainExactly setOf("SA", "SB") },
            { bySchema.getValue("SA").columns.map { it.column.name.uppercase() } shouldContainExactly listOf("SA_COL") },
            { bySchema.getValue("SB").columns.map { it.column.name.uppercase() } shouldContainExactly listOf("SB_COL") },
        )
    }

    @Test
    fun `an unknown datasource is the catalogued not-found`() {
        every { registry.get("nope") } returns null

        shouldThrow<DatapipelinesException> { introspector.tables("nope") }
            .code shouldBe DatasourceErrorCodes.NOT_FOUND
    }
}
