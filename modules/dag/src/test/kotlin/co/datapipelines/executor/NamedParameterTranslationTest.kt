package co.datapipelines.executor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterUtils

/**
 * Pins the behaviour of the named→positional translation that bound execution depends on
 * (042 D1/D3): spring-jdbc 6.2.19's [NamedParameterUtils], standalone — no driver involved.
 *
 * The dialect-shaped cases are the ones D3 names: every one of the seven dialects executes
 * rendered SQL, and the `:name` translation happens BEFORE the driver sees it, so each
 * dialect's habitual syntax must either survive the translation or fail it loudly. Every
 * row below is a regression pin against the pinned jar, not an exercise — a jar bump that
 * changes any of this must fail the build, never silently re-bind a template.
 *
 * What this suite discovered about the pinned translator (042 D1/D2/D3), recorded here so
 * the next reader does not have to re-derive it:
 *
 *  - **`::` casts survive** — `a::text` is skipped, not read as a parameter, so the
 *    house habit of writing `CAST(:x AS …)` is belt-and-braces, not a requirement
 *    (templates.md cites this test for exactly that).
 *  - **Four dialect constructs are NOT recognized as quotes/comments** by the parser, so a
 *    colon inside them is mis-read as a `:name` parameter: MySQL `#` comments, MSSQL
 *    `[a:b]` identifiers, Oracle `q'[…]'` strings, PostgreSQL `$$…$$` dollar-quoting.
 *    Consequence: such a template fails loudly (`pipeline.node.sql_parameter_missing`)
 *    when the phantom name has no value, and substitutes inside the construct when it
 *    does — a rendering an author sees in `templates_render`. The fix for either is the
 *    author's: rephrase the construct or the name. Values themselves never re-enter the
 *    parser, so the injection property this round exists for is unaffected.
 *  - Recognized and skipped correctly: `'…'` literals, `"…"` identifiers, `--` and
 *    `/* … */` comments, and MySQL `` `…` `` backtick identifiers.
 */
class NamedParameterTranslationTest {
    private fun translate(
        sql: String,
        params: Map<String, Any?> = mapOf("id" to 7),
    ): Pair<String, List<Any?>> {
        val parsed = NamedParameterUtils.parseSqlStatement(sql)
        val source = MapSqlParameterSource(params)
        return NamedParameterUtils.substituteNamedParameters(parsed, source) to
            NamedParameterUtils.buildValueArray(parsed, source, null).toList()
    }

    @Test
    fun `a Postgres cast and a named parameter coexist in one statement`() {
        val (sql, values) = translate("SELECT a::text AS label, '12:30' AS t FROM orders WHERE id = :id")

        sql shouldBe "SELECT a::text AS label, '12:30' AS t FROM orders WHERE id = ?"
        values shouldBe listOf(7)
    }

    @Test
    fun `a parameter that repeats expands once per occurrence`() {
        val (sql, values) = translate("SELECT :id + :id AS twice", mapOf("id" to 3))

        sql shouldBe "SELECT ? + ? AS twice"
        values shouldBe listOf(3, 3)
    }

    @Test
    fun `a cast with no parameter is not a parameter`() {
        val (sql, values) = translate("SELECT a::text FROM t", emptyMap())

        sql shouldBe "SELECT a::text FROM t"
        values shouldBe emptyList()
    }

    @Test
    fun `a trailing cast on a parameter name binds the name once`() {
        val (sql, values) = translate("SELECT 1 FROM t WHERE id = :id::int")

        sql shouldBe "SELECT 1 FROM t WHERE id = ?::int"
        values shouldBe listOf(7)
    }

    @Test
    fun `MySQL backtick identifiers survive`() {
        val (sql, values) = translate("SELECT `a:b` FROM `t` WHERE id = :id")

        sql shouldBe "SELECT `a:b` FROM `t` WHERE id = ?"
        values shouldBe listOf(7)
    }

    @Test
    fun `MySQL dash comments and block comments are not parameter sources`() {
        val (sql, values) = translate("SELECT 1 -- :comment\n WHERE id = :id /* :block */", mapOf("id" to 7))

        sql shouldBe "SELECT 1 -- :comment\n WHERE id = ? /* :block */"
        values shouldBe listOf(7)
    }

    @Test
    fun `a MySQL hash comment is mis-read as a parameter`() {
        val (sql, values) = translate("SELECT 1 # :comment\nFROM t WHERE id = :id", mapOf("comment" to "c", "id" to 7))
        sql shouldBe "SELECT 1 # ?\nFROM t WHERE id = ?"
        values shouldBe listOf("c", 7)

        // And a name the context does not declare fails loudly rather than binding null —
        // the exact property the executor's missing-name check relies on (042 C2).
        shouldThrow<InvalidDataAccessApiUsageException> {
            translate("SELECT 1 # :comment\nFROM t WHERE id = :id")
        }.message shouldBe "No value supplied for the SQL parameter 'comment': No value registered for key 'comment'"
    }

    @Test
    fun `an Oracle q-quoted string is mis-read as a parameter`() {
        // The parser understands single quotes, not q-quoting: the `'` inside `it's` closes its
        // quote, `:b` is then read as a parameter, and everything after the closing `'` is
        // "inside a quote" — so `:id` is left untouched and reaches the driver as text.
        val (sql, values) = translate("SELECT q'[it's a:b]' AS s FROM t WHERE id = :id", mapOf("b" to 9, "id" to 7))

        sql shouldBe "SELECT q'[it's a?]' AS s FROM t WHERE id = :id"
        values shouldBe listOf(9)
    }

    @Test
    fun `an MSSQL bracket identifier with a colon is mis-read as a parameter`() {
        val (sql, values) = translate("SELECT [a:b] AS label FROM [t] WHERE id = :id", mapOf("b" to 9, "id" to 7))

        sql shouldBe "SELECT [a?] AS label FROM [t] WHERE id = ?"
        values shouldBe listOf(9, 7)
    }

    @Test
    fun `a PostgreSQL dollar-quoted string is mis-read as a parameter`() {
        // `$` is a Java identifier part, so the parsed name is `b$$` and the substitution eats
        // the whole tail of the dollar-quote — the strongest mis-parse of the four.
        val (sql, values) = translate("SELECT \$\$a:b\$\$ AS s FROM t WHERE id = :id", mapOf("b\$\$" to 9, "id" to 7))

        sql shouldBe "SELECT \$\$a? AS s FROM t WHERE id = ?"
        values shouldBe listOf(9, 7)
    }

    @Test
    fun `a double-quoted identifier with a colon survives`() {
        val (sql, values) = translate("""SELECT "a:b" AS label FROM "t" WHERE id = :id""")

        sql shouldBe """SELECT "a:b" AS label FROM "t" WHERE id = ?"""
        values shouldBe listOf(7)
    }

    @Test
    fun `an ampersand is not a parameter prefix`() {
        val (sql, values) = translate("SELECT 1 FROM t WHERE id = :id AND x > 0 -- &ignored\n", mapOf("id" to 7))

        sql shouldBe "SELECT 1 FROM t WHERE id = ? AND x > 0 -- &ignored\n"
        values shouldBe listOf(7)
    }
}
