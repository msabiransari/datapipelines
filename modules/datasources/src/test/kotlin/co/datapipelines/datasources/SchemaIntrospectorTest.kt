package co.datapipelines.datasources

import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.sql.Connection
import java.sql.DriverManager

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

        val tables = introspector.tables("h2-test")

        // H2 reports the SQL-standard spelling "BASE TABLE" for a plain table; the introspector
        // passes the driver's raw TABLE_TYPE through untouched (§7A: raw JDBC table type).
        assertAll(
            { tables.any { it.name.equals("orders", ignoreCase = true) } shouldBe true },
            { tables.first { it.name.equals("orders", ignoreCase = true) }.type shouldBe "BASE TABLE" },
            { tables.first { it.name.equals("orders", ignoreCase = true) }.schema shouldBe "PUBLIC" },
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
        val name = introspector.tables("h2-test").first { it.name.equals("orders", ignoreCase = true) }.name
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
            { introspector.tables("h2-test", schemaFilter = "no_such_schema") shouldBe emptyList() },
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
            { introspector.columns("h2-test", "ORDER1ITEMS").map { it.column.name.uppercase() } shouldContainExactly listOf("ID", "ROGUE_FLAG") },
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

        val tables = introspector.tables("h2-test", schemaFilter = "MY_SCHEMA")

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
            introspector.snapshot("h2-test").tables
                .first { it.table.name.equals("ORDER_ITEMS", ignoreCase = true) }

        items.columns.map { it.column.name.uppercase() } shouldContainExactly listOf("ID", "ITEMS_NOTE")
    }
}
