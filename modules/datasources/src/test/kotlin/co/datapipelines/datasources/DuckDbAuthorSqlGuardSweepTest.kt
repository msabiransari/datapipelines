package co.datapipelines.datasources

import co.datapipelines.typesystem.jdbc.DuckDbAuthorSqlGate
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * #816 A3 (datasources.md §5.8, TEMPORARY): the separator sweep. For every code point U+0001–U+2FFFF
 * (surrogates skipped), a call form of the first SQL-text carrier — its name, the code point, then
 * `('SELECT 1')`, unquoted and quoted — is put to the PINNED ENGINE; every form the engine accepts must be
 * refused by [DuckDbAuthorSqlGate]. The engine, not a list, decides what a separator is.
 *
 * ## Method (measured, lane #816 evidence)
 *
 * 1. One statement asks the engine's own parser (`json_serialize_sql`) about all 389,118 forms at once
 *    and keeps those that parse to a call of the carrier's table function — a form the parser reads as
 *    another name or refuses is not a call to the carrier, so it cannot run it.
 *    ~1 s, where preparing each form over JDBC took ~34 s; both methods found the same 46 forms.
 * 2. Each candidate is then EXECUTED over JDBC on a raw connection — the engine runs it — and the
 *    gate must refuse it.
 * 3. Cross-check of step 1 where it matters: every form in U+0001–U+30FF (the range holding every
 *    separator the engine accepts) that the gate PASSES is executed over JDBC and must fail.
 *
 * Non-vacuity floor: at least [FLOOR] accepted forms (23 separators × 2 spellings on the pinned
 * 1.5.6.0, re-measured 2026-11-13); fewer means the sweep stopped seeing separators, and the test is red. Falsified at
 * birth (handback, gate G3): with the gate's separator class reverted to `\s`, the U+00A0 form is
 * accepted by the engine and passed by the gate — red.
 */
class DuckDbAuthorSqlGuardSweepTest {
    @Test
    fun `every call form the pinned engine runs is refused by the gate`() {
        DriverManager.getConnection("jdbc:duckdb:").use { raw ->
            val candidates = parsedCarrierCalls(raw)
            println("event=separator_sweep tried=$TRIED accepted=${candidates.size}")
            candidates.size shouldBeGreaterThanOrEqual FLOOR

            val passedByTheGate = mutableListOf<String>()
            candidates.forEach { (cp, name) ->
                val sql = form(cp, name)
                withClue("U+%04X after %s must run on the engine (the parser's verdict is a proxy)".format(cp, name)) {
                    runs(raw, sql) shouldBe true
                }
                val refused = runCatching { DuckDbAuthorSqlGate.check(sql) }.exceptionOrNull()
                if ((refused as? SQLException)?.sqlState != DuckDbAuthorSqlGate.SQL_STATE) {
                    passedByTheGate += "U+%04X %s".format(cp, name)
                }
            }
            withClue("call forms the engine runs and the gate passes") { passedByTheGate.shouldBeEmpty() }
        }
    }

    @Test
    fun `cross-check - no form the gate passes in the separator-dense range runs on the engine`() {
        DriverManager.getConnection("jdbc:duckdb:").use { raw ->
            val passed =
                (1..DENSE_RANGE_END)
                    .flatMap { cp -> NAMES.map { name -> cp to name } }
                    .filter { (cp, name) -> runCatching { DuckDbAuthorSqlGate.check(form(cp, name)) }.isSuccess }
            val ran = passed.filter { (cp, name) -> runs(raw, form(cp, name)) }.map { (cp, name) -> "U+%04X %s".format(cp, name) }
            println("event=separator_cross_check gate_passed_forms=${passed.size} ran=${ran.size}")
            (passed.size > DENSE_RANGE_END) shouldBe true
            withClue("forms the gate passed that the engine ran") { ran.shouldBeEmpty() }
        }
    }

    @Test
    fun `the sweep's own probe distinguishes a carrier call from another name`() {
        // The two halves of step 1's filter, on known inputs, so a JSON-path drift cannot empty it.
        DriverManager.getConnection("jdbc:duckdb:").use { raw ->
            runs(raw, form(' '.code, "query")) shouldBe true
            shouldThrow<SQLException> { raw.createStatement().use { it.executeQuery("SELECT 1 FROM queryx('SELECT 1')") } }
            val parsed = parsedCarrierCalls(raw)
            withClue("the space separator is a carrier call, both spellings") {
                parsed.filter { it.first == ' '.code }.map { it.second }.sorted() shouldBe NAMES.sorted()
            }
            withClue("a letter makes another name, which the filter must drop") {
                parsed.none { Character.isLetterOrDigit(it.first) } shouldBe true
            }
        }
    }

    private fun form(
        cp: Int,
        name: String,
    ): String = "SELECT 1 FROM $name${String(Character.toChars(cp))}('SELECT 1') LIMIT 0"

    private fun runs(
        raw: Connection,
        sql: String,
    ): Boolean =
        runCatching {
            raw.prepareStatement(sql).use { ps -> ps.executeQuery().use { rs -> rs.next() } }
        }.isSuccess

    /** Step 1: every (code point, spelling) the engine's parser reads as a call to the carrier. */
    private fun parsedCarrierCalls(raw: Connection): List<Pair<Int, String>> =
        raw.createStatement().use { st ->
            st.executeQuery(PARSER_SWEEP).use { rs ->
                buildList {
                    while (rs.next()) {
                        rs.getLong("tried") shouldBe TRIED
                        add(rs.getInt("cp") to rs.getString("q"))
                    }
                }
            }
        }

    private companion object {
        /** 23 separators × the unquoted and quoted spelling, on the pinned 1.5.6.0, re-measured 2026-11-13. */
        const val FLOOR = 46

        /** U+0001–U+2FFFF minus the 2,048 surrogates, × 2 spellings. */
        const val TRIED = 389_118L

        /** The cross-check's range: every separator the engine accepts lies below it. */
        const val DENSE_RANGE_END = 0x30FF

        val NAMES = listOf("query", "\"query\"")

        const val PARSER_SWEEP = """
            WITH forms AS (
              SELECT cp, q, 'SELECT 1 FROM ' || q || chr(cp::INTEGER) || '(''SELECT 1'') LIMIT 0' AS stmt
              FROM range(1, 196608) r(cp), (VALUES ('query'), ('"query"')) v(q)
              WHERE cp < 55296 OR cp > 57343),
            parsed AS (SELECT cp, q, json_serialize_sql(stmt) AS j FROM forms)
            SELECT (SELECT count(*) FROM forms) AS tried, cp, q FROM parsed
            WHERE NOT (j->>'$.error')::BOOLEAN
              AND lower(j->>'$.statements[0].node.from_table.function.function_name') = 'query'
            ORDER BY cp, q
        """
    }
}
