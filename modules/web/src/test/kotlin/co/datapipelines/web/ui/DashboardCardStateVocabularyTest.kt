package co.datapipelines.web.ui

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * #536 — the dashboard card's state vocabulary is ONE list, shared by the runtime that writes the
 * classes and the sheet that paints them.
 *
 * The defect this pins: `datapipelines-dashboard.js` set `--success`, `--aborted`, `--empty` and
 * `--stale` while `dashboards.css` styled `--abort`, `--no-data` and `--has-issue` and had no rule
 * for success or stale — so a successful load, a stale card and a cancelled refresh painted no
 * border at all, and nothing in the repo read both files. A browser applies an unstyled class
 * silently; only a test that reads both sides can see the drift.
 *
 * ## Scope, stated
 *
 *   - WRITTEN: every `dp-dashboard-card--x` inside a string literal of the runtime (the class
 *     names `applyCardStatus` assigns live only in string literals; a comment naming one is not
 *     a write).
 *   - STYLED: every `.dp-dashboard-card--x` selector in `dashboards.css`, block comments stripped.
 *   - The two sets must be EQUAL and equal to the expected vocabulary (a rename on both sides is
 *     a design change and edits [EXPECTED] here, deliberately), above a non-vacuity floor.
 *
 * The chip (`.dp-dashboard-status`) is never visible since #536: the border is the state. It keeps
 * its element, `role=status`, text and `data-dp-state` for assistive technology and the suites, so
 * it is hidden by the visually-hidden recipe — never `display: none` (a `role=status` removed from
 * the render tree is not announced) and never shown as an `inline-block` badge.
 */
class DashboardCardStateVocabularyTest {
    private val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)

    private fun read(path: String): String =
        resolver
            .getResources("classpath:static/$path")
            .single()
            .inputStream
            .readBytes()
            .decodeToString()

    private fun String.withoutBlockComments(): String = replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")

    private val runtime: String = read("js/datapipelines-dashboard.js")

    private val sheet: String = read("css/dashboards.css").withoutBlockComments()

    private val written: Set<String> by lazy { classesWritten(runtime) }

    private val styled: Set<String> by lazy { STYLED.findAll(sheet).map { it.groupValues[1] }.toSet() }

    /** The innermost rules of the sheet (an `@media` wrapper's own brace is never matched), as selector to body. */
    private val rules: List<Pair<String, String>> by lazy {
        RULE.findAll(sheet).map { it.groupValues[1].trim() to it.groupValues[2] }.toList()
    }

    @Test
    fun `the runtime writes exactly the card state classes the sheet styles`() {
        withClue(
            "written by the runtime but never styled: ${(written - styled).sorted()}; " +
                "styled but never written: ${(styled - written).sorted()}",
        ) {
            written shouldBe styled
        }
        written shouldBe EXPECTED
    }

    @Test
    fun `the vocabulary read is not vacuous`() {
        written.size shouldBeGreaterThanOrEqual VOCABULARY_FLOOR
        styled.size shouldBeGreaterThanOrEqual VOCABULARY_FLOOR
        // The extractor reads string literals, not comments: a scratch source proves both halves.
        classesWritten("""var a = "dp-dashboard-card dp-dashboard-card--busy"; // dp-dashboard-card--ghost""") shouldBe
            setOf("busy")
    }

    @Test
    fun `the status chip is visually hidden - never display none, never a shown badge`() {
        val chipRules = rules.filter { (selector, _) -> selector.contains(".dp-dashboard-status") }
        withClue("the sheet must style the chip at all") { chipRules.isNotEmpty() shouldBe true }
        val offending =
            chipRules.filter { (_, body) -> DISPLAY_NONE.containsMatchIn(body) || DISPLAY_INLINE_BLOCK.containsMatchIn(body) }
        withClue("chip rules that remove or show the chip: ${offending.map { it.first }}") { offending.shouldBeEmpty() }

        val base =
            chipRules.singleOrNull { (selector, _) -> selector == ".dp-dashboard-status" }
                ?: error("exactly one bare .dp-dashboard-status rule carries the visually-hidden recipe")
        base.second shouldContain "position: absolute;"
        base.second shouldContain "clip-path: inset(50%);"
        base.second shouldContain "overflow: hidden;"
    }

    private fun classesWritten(source: String): Set<String> =
        STRING_LITERAL
            .findAll(source)
            .flatMap { literal -> WRITTEN.findAll(literal.groupValues[2]).map { it.groupValues[1] } }
            .toSet()

    private companion object {
        /** A single- or double-quoted JS string literal (escapes honoured); group 2 is its content. */
        val STRING_LITERAL = Regex("""(["'])((?:\\.|(?!\1)[^\\\n])*)\1""")

        val WRITTEN = Regex("""(?<![\w-])dp-dashboard-card--([a-z][a-z-]*)""")

        val STYLED = Regex("""\.dp-dashboard-card--([a-z][a-z-]*)""")

        val RULE = Regex("""([^{}]+)\{([^{}]*)\}""")

        val DISPLAY_NONE = Regex("""display\s*:\s*none""")

        val DISPLAY_INLINE_BLOCK = Regex("""display\s*:\s*inline-block""")

        /**
         * The wire's state words are the class words (#536): `busy` is in-progress, `abort` and
         * `error` are the loud phase, `has-issue` the settled one after ERROR_ACCENT_MS, `stale`
         * the modifier a ready (or settled) card carries.
         */
        val EXPECTED = setOf("busy", "success", "error", "abort", "no-data", "stale", "has-issue")

        const val VOCABULARY_FLOOR = 7
    }
}
