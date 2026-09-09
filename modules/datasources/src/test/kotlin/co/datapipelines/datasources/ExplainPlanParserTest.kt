package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/**
 * [ExplainPlanParser] over the plan shapes the pinned drivers actually emit — the marker
 * strings here were live-probed (h2 2.3.232, sqlite-jdbc 3.49.1.0, duckdb_jdbc 1.5.5.1,
 * 2026-09-09), and a driver bump that rewords one fails this suite, not a probe at runtime.
 */
class ExplainPlanParserTest {
    @Test
    fun `postgres - a seq scan names itself and carries the root row estimate`() {
        val rows =
            listOf(
                listOf("Seq Scan on trips  (cost=0.00..35.50 rows=2550 width=44)"),
                listOf("  Filter: (id > 10)"),
            )

        val summary = ExplainPlanParser.summarize(Dialect.POSTGRES, listOf("QUERY PLAN"), rows)

        assertAll(
            { summary.scan shouldBe "seq" },
            { summary.estimatedRows shouldBe "2550" },
            { summary.raw shouldContain "Seq Scan" },
            { summary.partitionsScanned shouldBe null },
        )
    }

    @Test
    fun `postgres - an index scan names the index`() {
        val rows = listOf(listOf("Index Scan using idx_city on trips  (cost=0.15..8.17 rows=1 width=44)"))

        val summary = ExplainPlanParser.summarize(Dialect.POSTGRES, listOf("QUERY PLAN"), rows)

        summary.scan shouldBe "index:idx_city"
        summary.estimatedRows shouldBe "1"
    }

    @Test
    fun `postgres - an aggregate-rooted plan still names the scan node beneath it`() {
        // The T200 shape (2026-09-09): the root is the aggregate; the index signal is deeper.
        val rows =
            listOf(
                listOf("Finalize GroupAggregate  (cost=91614.90..91666.21 rows=193 width=42)"),
                listOf("  Group Key: pu_location_id"),
                listOf("  ->  Sort  (cost=90614.87..90615.36 rows=193 width=42)"),
                listOf("        ->  Parallel Index Scan using idx_trips_pickup_date on trips  (cost=0.43..89941.95 rows=88425 width=8)"),
                listOf("              Index Cond: ((pickup_date >= '2024-01-01'::date) AND (pickup_date <= '2024-12-31'::date))"),
            )

        val summary = ExplainPlanParser.summarize(Dialect.POSTGRES, listOf("QUERY PLAN"), rows)

        summary.scan shouldBe "index:idx_trips_pickup_date"
        summary.estimatedRows shouldBe "193"
    }

    @Test
    fun `postgres - a seq scan under an aggregate root still answers seq`() {
        val rows =
            listOf(
                listOf("HashAggregate  (cost=100.00..101.00 rows=10 width=8)"),
                listOf("  ->  Parallel Seq Scan on trips  (cost=0.00..90.00 rows=500 width=8)"),
            )

        val summary = ExplainPlanParser.summarize(Dialect.POSTGRES, listOf("QUERY PLAN"), rows)

        summary.scan shouldBe "seq"
    }

    @Test
    fun `postgres - an unrecognized root degrades to the first-line fallback, never a failure`() {
        val rows = listOf(listOf("Custom Scan (GpuPreAgg)  (cost=1.00..2.00 rows=5 width=8)"))

        val summary = ExplainPlanParser.summarize(Dialect.POSTGRES, listOf("QUERY PLAN"), rows)

        summary.scan shouldBe "Custom Scan (GpuPreAgg)  (cost=1.00..2.0"
        summary.estimatedRows shouldBe "5"
    }

    @Test
    fun `mysql - the tabular plan reads type, key and rows by column label`() {
        val labels =
            listOf(
                "id",
                "select_type",
                "table",
                "partitions",
                "type",
                "possible_keys",
                "key",
                "key_len",
                "ref",
                "rows",
                "filtered",
                "Extra",
            )
        val all = listOf(listOf("1", "SIMPLE", "trips", null, "ALL", null, null, null, null, "1000", "100.00", null))
        val ranged = listOf(listOf("1", "SIMPLE", "trips", null, "ref", "idx_city", "idx_city", "42", "const", "12", "100.00", null))

        val fullScan = ExplainPlanParser.summarize(Dialect.MYSQL, labels, all)
        val indexed = ExplainPlanParser.summarize(Dialect.MYSQL, labels, ranged)

        assertAll(
            { fullScan.scan shouldBe "seq" },
            { fullScan.estimatedRows shouldBe "1000" },
            { indexed.scan shouldBe "index:idx_city" },
            { indexed.estimatedRows shouldBe "12" },
        )
    }

    @Test
    fun `h2 - the access comment distinguishes a table scan from an index lookup`() {
        val scanRows =
            listOf(listOf("SELECT\n    \"PUBLIC\".\"TRIPS\".\"ID\"\nFROM \"PUBLIC\".\"TRIPS\"\n    /* PUBLIC.TRIPS.tableScan */"))
        val indexRows =
            listOf(
                listOf(
                    """SELECT
                        |    "PUBLIC"."TRIPS"."ID"
                        |FROM "PUBLIC"."TRIPS"
                        |    /* PUBLIC.IDX_CITY: CITY = 'x' */
                        |WHERE "CITY" = ?1
                    """.trimMargin(),
                ),
            )

        ExplainPlanParser.summarize(Dialect.H2, listOf("PLAN"), scanRows).scan shouldBe "seq"
        ExplainPlanParser.summarize(Dialect.H2, listOf("PLAN"), indexRows).scan shouldBe "index:PUBLIC.IDX_CITY"
    }

    @Test
    fun `duckdb - the box drawing parses to the pruning marker and the root estimate`() {
        val plan =
            """
            ┌─────────────┴─────────────┐
            │        READ_PARQUET       │
            │    ────────────────────   │
            │       File Filters:       │
            │    (pickup_date = 'd1')   │
            │                           │
            │    Scanning Files: 1/3    │
            │                           │
            │          ~33 rows         │
            └───────────────────────────┘
            """.trimIndent()

        val summary =
            ExplainPlanParser.summarize(
                Dialect.LAKE,
                listOf("explain_key", "explain_value"),
                listOf(listOf("physical_plan", plan)),
            )

        assertAll(
            { summary.scan shouldBe "partition_prune 1/3" },
            { summary.partitionsScanned shouldBe 1 },
            { summary.partitionsTotal shouldBe 3 },
            { summary.estimatedRows shouldBe "33" },
            { summary.raw shouldContain "Scanning Files: 1/3" },
        )
    }

    @Test
    fun `duckdb - a scan with no file filter reports the node name and null partitions`() {
        val plan =
            """
            ┌─────────────┴─────────────┐
            │         TABLE_SCAN        │
            │    ────────────────────   │
            │        Filters: id=5      │
            │                           │
            │          ~1,000 rows      │
            └───────────────────────────┘
            """.trimIndent()

        val summary =
            ExplainPlanParser.summarize(
                Dialect.DUCKDB,
                listOf("explain_key", "explain_value"),
                listOf(listOf("physical_plan", plan)),
            )

        assertAll(
            { summary.scan shouldBe "TABLE_SCAN" },
            { summary.partitionsScanned shouldBe null },
            { summary.partitionsTotal shouldBe null },
            // The thousands separator is normalized away.
            { summary.estimatedRows shouldBe "1000" },
        )
    }

    @Test
    fun `sqlite - the detail column distinguishes SCAN from SEARCH`() {
        val labels = listOf("id", "parent", "notused", "detail")

        val full = ExplainPlanParser.summarize(Dialect.SQLITE, labels, listOf(listOf("2", "0", "216", "SCAN trips")))
        val indexed =
            ExplainPlanParser.summarize(
                Dialect.SQLITE,
                labels,
                listOf(listOf("2", "0", "56", "SEARCH trips USING COVERING INDEX idx_city (city=?)")),
            )

        assertAll(
            { full.scan shouldBe "seq" },
            { indexed.scan shouldBe "index:idx_city" },
        )
    }

    @Test
    fun `a plan shape the parser does not know still answers its raw text`() {
        val rows = listOf(listOf(null, null))

        val summary = ExplainPlanParser.summarize(Dialect.H2, listOf("PLAN"), rows)

        assertAll(
            { summary.scan shouldBe null },
            { summary.estimatedRows shouldBe null },
            { summary.raw shouldBe " | " },
        )
    }
}
