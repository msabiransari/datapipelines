package co.datapipelines.web.ui

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * #713 — `docs.css` held to the same token discipline `SchedulesCssTokenTest` holds
 * `schedules.css` to, because the sheet gained its first custom-property length literal:
 * `--doc-intrinsic-block`, the `content-visibility` placeholder estimate that keeps the
 * scrollbar honest while off-screen table/pre blocks are skip-rendered. That definition is
 * the sheet's ONE sanctioned length literal, named here; a second one fails the audit
 * until it is named deliberately. The pre-existing `1px` hairline borders are style
 * values, not token definitions — out of this audit's scope, as they have been since 033.
 *
 * Two rules in `SchedulesCssTokenTest`'s shape (no literal colour; every font size on the
 * one scale), plus the length rule that sanctions the hint. The sheet loads from the
 * public layout's `extraCss` slot (ui-screens §4.16), not the app layout head — the two
 * chromes share one body sheet.
 */
class DocsCssTokenTest {
    private val css = read("static/css/docs.css")

    private fun read(path: String): String = requireNotNull(javaClass.classLoader.getResource(path)) { path }.readText()

    private val declarations: List<Pair<Int, String>> =
        css
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)) { m -> m.value.replace(Regex("[^\n]"), " ") }
            .lines()
            .mapIndexed { i, line -> i + 1 to line.trim() }
            .filter { (_, line) -> line.contains(':') && line.endsWith(";") }

    @Test
    fun `no literal colour in any declaration`() {
        val literal = Regex("""#[0-9a-fA-F]{3,8}\b|\brgba?\(|\bhsla?\(|:\s*(white|black|red|green|blue|gray|grey|orange|yellow)\s*;""")
        declarations.size shouldBeGreaterThan 20
        val found = declarations.filter { (_, line) -> literal.containsMatchIn(line) }.map { (n, line) -> "docs.css:$n $line" }
        withClue("a colour must be a design token") { found.shouldBeEmpty() }
    }

    @Test
    fun `every font size is a step of the type scale`() {
        val found =
            declarations
                .filter { (_, line) -> line.startsWith("font-size:") && !TYPE_SCALE.containsMatchIn(line) }
                .map { (n, line) -> "docs.css:$n $line" }
        found.shouldBeEmpty()
    }

    @Test
    fun `the intrinsic-size hint is the sheet's one sanctioned length literal and the only token read by contain-intrinsic-size`() {
        val lengthDefinition = Regex("""^--[a-z0-9-]+:\s*[\d.]+(px|rem|em|vh|vw)\s*;$""")
        val definitions =
            declarations
                .filter { (_, line) -> lengthDefinition.containsMatchIn(line) }
                .map { (n, line) -> "docs.css:$n $line" }
        withClue(
            "the sheet's only length-literal token definition is --doc-intrinsic-block " +
                "(the #713 hint); name a new one deliberately or read a token",
        ) {
            definitions.size shouldBe 1
            definitions.single().shouldContain("--doc-intrinsic-block: 480px;")
        }
        val hintReads = declarations.filter { (_, line) -> line.startsWith("contain-intrinsic-size:") }.map { (_, line) -> line }
        withClue("every contain-intrinsic-size reads the sanctioned hint token with the auto (last-remembered size) prefix") {
            hintReads.size shouldBe 3
            hintReads.forEach { it shouldBe "contain-intrinsic-size: auto var(--doc-intrinsic-block);" }
        }
    }

    private companion object {
        /** A step of the one type scale: a vendored `--text-*` token (same rule as schedules.css). */
        val TYPE_SCALE = Regex("""var\(--(app-)?text-[a-z0-9-]+\)""")
    }
}
