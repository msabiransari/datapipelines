package co.datapipelines.datasources

import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.sql.DriverManager

/**
 * §7C catalog statistics over a **real** in-memory H2 (the [SchemaIntrospectorH2Test] pattern):
 * `information_schema.tables.row_count_estimate` and the `indexes`/`index_columns` join are
 * exercised against the pinned driver, not fixtures.
 */
class TableStatsH2Test {
    private val h2 = DriverManager.getConnection("jdbc:h2:mem:stats;DB_CLOSE_DELAY=-1")
    private val registry = mockk<DatasourceRegistry>()
    private val introspector = SchemaIntrospector(registry)

    /** A pool that hands out fresh connections to the same named in-memory DB. */
    private val pool = JdbcUrlPool("jdbc:h2:mem:stats;DB_CLOSE_DELAY=-1", "h2-stats")

    private fun wireDatasource() {
        val ds = Fixtures.h2(name = "h2-stats")
        every { registry.get("h2-stats") } returns ds
        every { registry.poolFor(ds) } returns pool
    }

    @AfterEach
    fun tearDown() {
        h2.createStatement().use { it.execute("DROP ALL OBJECTS") }
    }

    @Test
    fun `row estimate and indexes come from the catalog, composite keys in order`() {
        h2.createStatement().use { st ->
            st.execute("CREATE TABLE stats_probe (id INT PRIMARY KEY, a INT, b INT, note VARCHAR(30))")
            st.execute("CREATE UNIQUE INDEX idx_ab ON stats_probe(a, b)")
            st.execute("INSERT INTO stats_probe VALUES (1, 1, 1, 'x'), (2, 2, 2, 'y'), (3, 3, 3, 'z')")
        }
        wireDatasource()

        // H2 stores unquoted identifiers uppercased; the unfiltered read resolves the
        // connection's current schema (PUBLIC) exactly like columns() does.
        val stats = introspector.tableStats("h2-stats", "STATS_PROBE")

        val primary = stats.indexes.single { it.primary }
        val composite = stats.indexes.single { it.name == "IDX_AB" }
        assertAll(
            { stats.statsSource shouldBe "h2_information_schema" },
            { stats.rowEstimate shouldBe "3" },
            { stats.statsAsOf shouldBe null },
            // H2 names the backing index of a PRIMARY KEY constraint itself.
            { primary.name shouldStartWith "PRIMARY_KEY" },
            { primary.unique shouldBe true },
            { primary.columns shouldContainExactly listOf("ID") },
            { composite.unique shouldBe true },
            { composite.primary shouldBe false },
            { composite.columns shouldContainExactly listOf("A", "B") },
            { composite.kind shouldBe IndexStats.INDEX_KIND_INDEX },
            // H2 keeps no per-column catalog statistics.
            { stats.columns shouldBe emptyList<ColumnStats>() },
        )
    }

    @Test
    fun `an unknown table answers empty stats, not an error`() {
        wireDatasource()

        val stats = introspector.tableStats("h2-stats", "NO_SUCH_TABLE")

        assertAll(
            { stats.statsSource shouldBe "h2_information_schema" },
            { stats.rowEstimate shouldBe null },
            { stats.indexes shouldBe emptyList<IndexStats>() },
            { stats.columns shouldBe emptyList<ColumnStats>() },
        )
    }

    @Test
    fun `an unknown datasource is the catalogued not-found`() {
        every { registry.get("nope") } returns null

        shouldThrow<DatapipelinesException> { introspector.tableStats("nope", "t") }
            .code shouldBe DatasourceErrorCodes.NOT_FOUND
    }

    @Test
    fun `an explicit schema filter is honored`() {
        h2.createStatement().use { st ->
            st.execute("CREATE SCHEMA stats_other")
            st.execute("CREATE TABLE stats_other.probe (id INT PRIMARY KEY)")
            st.execute("INSERT INTO stats_other.probe VALUES (1)")
        }
        wireDatasource()

        val stats = introspector.tableStats("h2-stats", "PROBE", namespaceFilter = listOf("STATS_OTHER"))

        stats.rowEstimate shouldBe "1"
    }
}
