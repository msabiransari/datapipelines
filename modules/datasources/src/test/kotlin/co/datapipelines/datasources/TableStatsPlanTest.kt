package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/**
 * The §7C adapter seam ([DialectAdapter.tableStatsPlan]) and the payload's wire projection:
 * which dialects carry a plan, the catalog each names, the descriptor's bind discipline, and
 * the DuckDB `expressions` text parse. Live behavior per dialect lives in TableStatsH2Test,
 * TableStatsEmbeddedTest and the two container suites.
 */
class TableStatsPlanTest {
    @Test
    fun `every dialect's plan presence is a deliberate declaration`() {
        val plans = Dialect.entries.associateWith { DialectAdapters.forDialect(it).tableStatsPlan }

        // LAKE is the one null: its stats come from Parquet footers and the registry, not
        // catalog SQL — TableStatsReader branches on the dialect before consulting a plan.
        plans[Dialect.LAKE] shouldBe null
        plans.filterKeys { it != Dialect.LAKE }.values.forEach { plan ->
            assert(plan != null) { "a non-LAKE dialect must declare its catalog recipe" }
        }
        assertAll(
            { plans[Dialect.POSTGRES]!!.source shouldBe "pg_class" },
            { plans[Dialect.MYSQL]!!.source shouldBe "information_schema.tables" },
            { plans[Dialect.H2]!!.source shouldBe "h2_information_schema" },
            { plans[Dialect.DUCKDB]!!.source shouldBe "duckdb_tables" },
            { plans[Dialect.SQLITE]!!.source shouldBe "sqlite_stat1" },
            { plans[Dialect.ORACLE]!!.source shouldBe "all_tables" },
            { plans[Dialect.MSSQL]!!.source shouldBe "sys.partitions" },
        )
    }

    @Test
    fun `every plan query's placeholder count matches its declared binds`() {
        // A mismatch here fails at runtime as a driver bind error — checkable statically.
        DialectAdapters.all().mapNotNull { it.tableStatsPlan }.forEach { plan ->
            (listOfNotNull(plan.rowEstimate, plan.columnStats) + plan.indexes).forEach { query ->
                query.sql.count { it == '?' } shouldBe query.binds.size
            }
        }
    }

    @Test
    fun `the postgres and sqlite dialect quirks ride on the plan, not the reader`() {
        assertAll(
            // pg_stats.n_distinct's negative-is-a-fraction convention.
            { DialectAdapters.forDialect(Dialect.POSTGRES).tableStatsPlan!!.distinctStoredAsSignedRatio shouldBe true },
            { DialectAdapters.forDialect(Dialect.MYSQL).tableStatsPlan!!.distinctStoredAsSignedRatio shouldBe false },
            // sqlite_stat1 exists only after the first ANALYZE.
            { DialectAdapters.forDialect(Dialect.SQLITE).tableStatsPlan!!.rowEstimateCatalogOptional shouldBe true },
            { DialectAdapters.forDialect(Dialect.H2).tableStatsPlan!!.rowEstimateCatalogOptional shouldBe false },
        )
    }

    @Test
    fun `duckdb index expressions parse conservatively`() {
        // Verified shapes of duckdb_indexes().expressions on duckdb_jdbc 1.5.5.1.
        assertAll(
            { parseIndexExpressions("""['"label"', fare]""") shouldContainExactly listOf("label", "fare") },
            { parseIndexExpressions("[id]") shouldContainExactly listOf("id") },
            // A computed index names no column — dropped, never guessed.
            { parseIndexExpressions("['(a + b)']") shouldContainExactly emptyList() },
            { parseIndexExpressions("[]") shouldContainExactly emptyList() },
            // A quoted identifier inside the repr keeps its inner quotes resolved.
            { parseIndexExpressions("""['"we""ird"']""") shouldContainExactly listOf("""we"ird""") },
        )
    }

    @Test
    fun `the wire projection is snake_case and omits null stats`() {
        val stats =
            TableStats(
                rowEstimate = "999",
                statsAsOf = null,
                statsSource = "pg_class",
                indexes = listOf(IndexStats("orders_pkey", listOf("id"), unique = true, primary = true)),
                columns =
                    listOf(
                        ColumnStats("id", nDistinct = "999", distinctIsRatio = false, nullFraction = null, min = "1", max = "999"),
                    ),
            )

        val wire = stats.toWireMap()

        assertAll(
            { wire["row_estimate"] shouldBe "999" },
            { wire.containsKey("stats_as_of") shouldBe false },
            { wire["stats_source"] shouldBe "pg_class" },
            {
                (wire["indexes"] as List<*>).single() shouldBe
                    mapOf("name" to "orders_pkey", "columns" to listOf("id"), "unique" to true, "primary" to true, "kind" to "index")
            },
            {
                (wire["columns"] as List<*>).single() shouldBe
                    mapOf("name" to "id", "n_distinct" to "999", "distinct_is_ratio" to false, "min" to "1", "max" to "999")
            },
        )
    }

    @Test
    fun `pg histogram bounds split their text form at top level`() {
        assertAll(
            { pgHistogramBounds("{1,22,999}") shouldBe ("1" to "999") },
            // Quoted elements may carry commas; backslash escapes resolve inside quotes.
            { pgHistogramBounds("""{"note-1,x","note-999"}""") shouldBe ("note-1,x" to "note-999") },
            { pgHistogramBounds("""{"a\"b","c"}""") shouldBe ("a\"b" to "c") },
            { pgHistogramBounds("{}") shouldBe (null to null) },
            { pgHistogramBounds(null) shouldBe (null to null) },
            { pgHistogramBounds("not-an-array") shouldBe (null to null) },
        )
    }

    @Test
    fun `the none-source answer is a valid payload, not an error`() {
        val wire = TableStats(null, null, "none", emptyList(), emptyList()).toWireMap()

        assertAll(
            { wire["stats_source"] shouldBe "none" },
            { wire.containsKey("row_estimate") shouldBe false },
            { wire["indexes"] shouldBe emptyList<IndexStats>() },
            { wire["columns"] shouldBe emptyList<ColumnStats>() },
        )
    }
}
