package co.datapipelines.datasources

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.sql.SQLException

/**
 * The reference-scanner slice's §3 probe matrix (#778), one fixture per row, reproduced on THIS build's pinned
 * `duckdb_jdbc` through the production extractor, plus the parse-only connection's lock-down (the brief's Security
 * section: a rendered `read_csv('/etc/passwd')` is classified without any file being read).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqlTableReferenceExtractorTest {
    private val extractor = SqlTableReferenceExtractor()

    @AfterAll
    fun close() = extractor.close()

    /** A fixture: rendered text → the (catalog, schema, table, statement, timeTravel, cte) rows the parser must name. */
    data class Fixture(
        val name: String,
        val sql: String,
        val tables: List<WrittenTableReference>,
        val functions: List<String> = emptyList(),
    ) {
        override fun toString() = name
    }

    private fun parsed(sql: String) = extractor.extract(sql).shouldBeInstanceOf<SqlTableExtraction.Parsed>()

    @ParameterizedTest(name = "{0}")
    @MethodSource("matrix")
    fun `every probe-matrix row names exactly its tables`(fixture: Fixture) {
        val result = parsed(fixture.sql)
        result.tables shouldContainExactly fixture.tables
        result.functions.map { it.function } shouldContainExactly fixture.functions
    }

    @Test
    fun `a multi-statement text numbers each statement`() {
        val result = parsed("SELECT * FROM a.x; SELECT 1; SELECT * FROM b.y")
        result.statementCount shouldBe 3
        result.tables.map { it.statementIndex } shouldContainExactly listOf(0, 2)
    }

    @Test
    fun `PIVOT is unscannable, not empty`() {
        val refused = extractor.extract("PIVOT a.x ON k USING sum(v)").shouldBeInstanceOf<SqlTableExtraction.Unscannable>()
        refused.errorType shouldBe "not implemented"
        refused.message shouldContain "Only SELECT statements"
    }

    @Test
    fun `INSERT … SELECT is unscannable`() {
        extractor.extract("INSERT INTO a.x SELECT * FROM b.y").shouldBeInstanceOf<SqlTableExtraction.Unscannable>().errorType shouldBe
            "not implemented"
    }

    @Test
    fun `one DML statement among SELECTs makes the whole text unscannable`() {
        extractor.extract("SELECT * FROM a.x; DELETE FROM a.x").shouldBeInstanceOf<SqlTableExtraction.Unscannable>()
    }

    @Test
    fun `a syntax error is unscannable with the parser's word, its message bounded`() {
        val refused = extractor.extract("SELECT 1 FRM broken").shouldBeInstanceOf<SqlTableExtraction.Unscannable>()
        refused.errorType shouldBe "parser"
        (refused.message.length <= SqlTableReferenceExtractor.MAX_MESSAGE) shouldBe true
    }

    @Test
    fun `a rendered read of a local file is classified without the file being read`() {
        val result = parsed("SELECT * FROM read_csv('/etc/passwd')")
        result.tables.shouldBeEmpty()
        result.functions.map { it.function } shouldContainExactly listOf("read_csv")
    }

    @Test
    fun `the parse-only connection cannot read a file even when asked to execute`() {
        SqlTableReferenceExtractor.openParseOnlyConnection().use { connection ->
            val refused =
                shouldThrow<SQLException> {
                    connection.createStatement().use { it.executeQuery("SELECT * FROM read_csv('/etc/passwd')") }
                }
            refused.message.orEmpty().lowercase() shouldContain "disabled"
        }
    }

    @Test
    fun `the parse-only connection's lock-down cannot be lifted by a statement`() {
        SqlTableReferenceExtractor.openParseOnlyConnection().use { connection ->
            shouldThrow<SQLException> { connection.createStatement().use { it.execute("SET enable_external_access = true") } }
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT current_setting('enable_external_access')").use { rows ->
                    rows.next() shouldBe true
                    rows.getBoolean(1) shouldBe false
                }
            }
        }
    }

    @Test
    fun `the engine facts name the default catalog and its internal views`() {
        val facts = extractor.engineFacts()
        facts.defaultCatalog shouldBe "memory"
        facts.internalViews shouldContain "duckdb_tables"
    }

    /**
     * The falsification the brief asks for, per blind-spot class: [LakeTableGateSemantics] restates the runtime gate's
     * token match (`LakeTableGate.referencePatterns`, `dag`) and answers WRONG on each fixture, while the extractor —
     * the same fixture, the same expected answer — answers right. Replacing the extractor by the regex would turn
     * each of these red.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("regexBlindSpots")
    fun `the runtime gate's regex semantics fail exactly where the parser succeeds`(spot: BlindSpot) {
        val parsedSays = parsed(spot.sql).tables.any { !it.cte && it.matches(spot.table) }
        val regexSays = LakeTableGateSemantics.references(spot.sql, spot.table)
        parsedSays shouldBe spot.referenced
        regexSays shouldNotBe spot.referenced
    }

    data class BlindSpot(
        val name: String,
        val sql: String,
        val table: List<String>,
        val referenced: Boolean,
    ) {
        override fun toString() = name
    }

    private fun WrittenTableReference.matches(qualified: List<String>): Boolean =
        listOfNotNull(catalog, schema, table).map { it.lowercase() } == qualified.map { it.lowercase() }

    /** The runtime gate's four token-bounded spellings (`LakeTableGate.referencePatterns`), restated for the falsification. */
    private object LakeTableGateSemantics {
        fun references(
            sql: String,
            qualified: List<String>,
        ): Boolean {
            val name = qualified.last()
            val quotedQualified = qualified.joinToString(".") { "\"$it\"" }
            return listOf(qualified.joinToString("."), quotedQualified, name, "\"$name\"")
                .distinct()
                .map { Regex("""(?<![\w."])""" + Regex.escape(it) + """(?![\w"])""") }
                .any { it.containsMatchIn(sql) }
        }
    }

    companion object {
        private fun t(
            catalog: String?,
            schema: String?,
            table: String,
            statement: Int = 0,
            timeTravel: Boolean = false,
            cte: Boolean = false,
        ) = WrittenTableReference(catalog, schema, table, statement, timeTravel, cte)

        @JvmStatic
        fun matrix(): List<Fixture> =
            listOf(
                Fixture("three-part", "SELECT * FROM lake.sales.orders", listOf(t("lake", "sales", "orders"))),
                Fixture("two-part", "SELECT * FROM sales.orders", listOf(t(null, "sales", "orders"))),
                Fixture("bare", "SELECT * FROM orders", listOf(t(null, null, "orders"))),
                Fixture("quoted mixed-case", "SELECT * FROM \"Sales\".\"Orders\"", listOf(t(null, "Sales", "Orders"))),
                Fixture(
                    "CTE + UNION",
                    "WITH c AS (SELECT * FROM a.x) SELECT * FROM c UNION ALL SELECT * FROM b.y",
                    listOf(t(null, "a", "x"), t(null, null, "c", cte = true), t(null, "b", "y")),
                ),
                Fixture(
                    "IN-subquery",
                    "SELECT * FROM a.x WHERE id IN (SELECT id FROM b.y)",
                    listOf(t(null, "a", "x"), t(null, "b", "y")),
                ),
                Fixture(
                    "multi-statement",
                    "SELECT * FROM a.x; SELECT * FROM b.y",
                    listOf(t(null, "a", "x"), t(null, "b", "y", statement = 1)),
                ),
                Fixture("FROM-first", "FROM a.x SELECT id", listOf(t(null, "a", "x"))),
                Fixture("USING SAMPLE", "SELECT * FROM a.x USING SAMPLE 10%", listOf(t(null, "a", "x"))),
                Fixture("AT (VERSION => 3)", "SELECT * FROM a.x AT (VERSION => 3)", listOf(t(null, "a", "x", timeTravel = true))),
                Fixture("DESCRIBE", "DESCRIBE a.x", listOf(t(null, "a", "x"))),
                Fixture("bound ?", "SELECT * FROM a.x WHERE id = ? AND d > ?", listOf(t(null, "a", "x"))),
                Fixture("join", "SELECT * FROM a.x JOIN lake.b.y USING (id)", listOf(t(null, "a", "x"), t("lake", "b", "y"))),
                Fixture("query_table", "SELECT * FROM query_table('a.x')", emptyList(), listOf("query_table")),
                Fixture("read_parquet", "SELECT * FROM read_parquet('s3://b/k.parquet')", emptyList(), listOf("read_parquet")),
                Fixture("string literal", "SELECT 'FROM a.x' AS s", emptyList()),
                Fixture("comment", "SELECT 1 -- FROM a.x\n", emptyList()),
            )

        @JvmStatic
        fun regexBlindSpots(): List<BlindSpot> =
            listOf(
                BlindSpot("string literal names the table", "SELECT 'see a.x' AS note FROM b.y", listOf("a", "x"), referenced = false),
                BlindSpot("comment names the table", "SELECT * FROM b.y -- was a.x", listOf("a", "x"), referenced = false),
                BlindSpot("a CTE named like the table", "WITH x AS (SELECT 1 AS v) SELECT * FROM x", listOf("x"), referenced = false),
                BlindSpot("a column named like the table", "SELECT x FROM b.y", listOf("x"), referenced = false),
                BlindSpot("mixed quoting of the qualified name", "SELECT * FROM a.\"x\"", listOf("a", "x"), referenced = true),
            )
    }
}
