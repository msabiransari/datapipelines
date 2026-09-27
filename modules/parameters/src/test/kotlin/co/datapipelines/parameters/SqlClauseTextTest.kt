package co.datapipelines.parameters

import co.datapipelines.templates.TemplateValidator
import io.kotest.assertions.withClue
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.time.Duration

/**
 * P7's ORDER BY check reads [SqlClauseText.blanked]. Three things are pinned: WHAT is blanked (exact
 * output, so a literal's `--` is never a comment and a quoted `"order by"` is never a clause), that
 * the pass is LINEAR (characters read, counted — deterministic on any box), and that a pathological
 * body at the template body cap is blanked well inside a bound the old three-regex strip missed by
 * two orders of magnitude (measured 5.8 s at 65,535 characters, quadratic — about 90 s at the cap).
 */
class SqlClauseTextTest {
    @Test
    fun `comments, literals and quoted identifiers become one space each, and nothing else changes`() {
        mapOf(
            "SELECT a FROM t ORDER BY a" to "SELECT a FROM t ORDER BY a",
            "SELECT '--' AS x, a FROM t ORDER BY a" to "SELECT   AS x, a FROM t ORDER BY a",
            "SELECT a FROM t /* ORDER BY a */" to "SELECT a FROM t  ",
            "SELECT a FROM t -- ORDER BY a" to "SELECT a FROM t  ",
            "SELECT a FROM t -- note\nORDER BY a" to "SELECT a FROM t  \nORDER BY a",
            "SELECT 'ORDER BY' AS a FROM t" to "SELECT   AS a FROM t",
            "SELECT a AS \"order by\" FROM t" to "SELECT a AS   FROM t",
            "SELECT 'it''s' AS a FROM t ORDER BY a" to "SELECT   AS a FROM t ORDER BY a",
            "SELECT \"a\"\"b\" FROM t" to "SELECT   FROM t",
            "SELECT a FROM t ORDER/**/BY a" to "SELECT a FROM t ORDER BY a",
            "SELECT '/*' AS a FROM t ORDER BY a -- */" to "SELECT   AS a FROM t ORDER BY a  ",
            "SELECT a FROM t /* unterminated ORDER BY a" to "SELECT a FROM t  ",
            "SELECT 'unterminated ORDER BY a" to "SELECT  ",
            "SELECT a - 1 / 2 FROM t" to "SELECT a - 1 / 2 FROM t",
            "" to "",
        ).forEach { (sql, blanked) ->
            withClue(sql) { SqlClauseText.blanked(sql) shouldBe blanked }
        }
    }

    @Test
    fun `every character is read at most twice, whatever the body is made of`() {
        val cap = TemplateValidator.DEFAULT_MAX_BODY_CHARS
        listOf("/* ", "-- ", "'", "''", "\"", "*/", "/*'--\"", "a").forEach { unit ->
            val sql = unit.repeat(cap / unit.length)
            val counted = CountingChars(sql)
            SqlClauseText.blanked(counted)
            withClue("'$unit' × ${cap / unit.length}: ${counted.reads} reads over ${sql.length} characters") {
                // Non-vacuity: the pass read the counted input itself, not a copy made before counting.
                counted.reads shouldBeGreaterThanOrEqual sql.length.toLong()
                counted.reads shouldBeLessThanOrEqual 2L * sql.length
            }
        }
    }

    @Test
    fun `a body of unterminated comment openings at the template cap is blanked inside the preemptive bound`() {
        val sql = "/* ".repeat(TemplateValidator.DEFAULT_MAX_BODY_CHARS / 3)
        val blanked = assertTimeoutPreemptively(Duration.ofSeconds(PREEMPTIVE_SECONDS)) { SqlClauseText.blanked(sql) }
        blanked shouldBe " "
    }

    /** The input, counting every character read through [get] (the `ReadBudgetCharSequence` idea, lane A). */
    private class CountingChars(
        private val text: String,
    ) : CharSequence {
        var reads = 0L
            private set

        override val length: Int get() = text.length

        override fun get(index: Int): Char {
            reads++
            return text[index]
        }

        override fun subSequence(
            startIndex: Int,
            endIndex: Int,
        ): CharSequence = text.subSequence(startIndex, endIndex)

        override fun toString(): String = text
    }

    private companion object {
        /** The linear pass takes milliseconds at the cap; the old strip took about 90 s — the bound sits far from both. */
        const val PREEMPTIVE_SECONDS = 5L
    }
}
