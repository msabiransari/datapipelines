package co.datapipelines.web.ui.site

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File

/**
 * No hand-typed count survives on the marketing site (124 §C).
 *
 * The site's numbers are facts of the code — the engine catalog (`Dialect.entries`), the tool
 * catalog (`McpToolCatalog.NAMES`), the calculator kinds, the API-key kinds, the learned-fact
 * kinds and scopes — and they render from `SiteFacts`. A count TYPED into a template or a
 * Kotlin file is a second copy that goes stale silently: the site said "six engines" in nine
 * places and "eight" in two at once, and both were wrong the day a dialect shipped.
 *
 * The sweep reads every site template and every Kotlin file under `ui/site/` as TEXT and
 * fails on a spelled-out or numeric count adjacent to a counted noun. A match is exempt only
 * when it lives inside a Thymeleaf expression that references `${facts` (or the fallback text
 * of the element/attribute that expression rewrites at render), or inside a Kotlin string
 * template that references `${facts` — i.e. when the rendered number is derived and the
 * literal is only the static-file placeholder. Nothing is allowlisted: a count that is not a
 * fact of the code is removed from the copy, not exempted.
 */
class SiteHandTypedCountsGuardTest {
    @Test
    fun `no hand-typed count of a code-owned noun survives in site copy`() {
        val files = templateFiles() + kotlinFiles()
        // Non-vacuity: a sweep that stopped seeing the files would pass by checking nothing.
        check(templateFiles().size >= MIN_TEMPLATES) { "the sweep found only ${templateFiles().size} site templates" }
        check(kotlinFiles().size >= MIN_KOTLIN) { "the sweep found only ${kotlinFiles().size} ui/site Kotlin files" }

        val violations = files.flatMap(::violationsIn)
        violations shouldBe emptyList()
    }

    /**
     * The 133 demo arm: the console and the DAG show a REAL run, so every number they render
     * comes from the ONE `SiteFacts.demo` block — no digit typed into either partial. The
     * sweep strips the places a digit legitimately lives (an SVG's geometry attributes, and
     * any `th:*` attribute whose value is an expression — the value renders from the block),
     * then fails on any remaining numeric digit. A digit hugged by a letter is a product
     * name, not a number ("H2" in the staging column's header), and is not flagged.
     *
     * The second half is the wiring proof: the rendered pages state the run's own numbers
     * — the home page's worked example shows the rows (145), the engineering page shows
     * the console, the DAG and the endpoint panel — so a partial or a page that quietly
     * stopped reading the block fails here too.
     */
    @Test
    fun `the demo console and DAG render every number from the SiteFacts demo block`() {
        val violations =
            DEMO_PARTIALS.flatMap { name ->
                val file = File(repoRoot(), "modules/web/src/main/resources/templates/site/$name")
                check(file.isFile) { "the demo partial $name is gone — the arm would pass by checking nothing" }
                val stripped = stripDemoExempt(file.readText())
                TYPED_DIGIT
                    .findAll(stripped)
                    .map { match ->
                        val line = stripped.take(match.range.first).count { it == '\n' } + 1
                        "$name:$line — a digit typed outside the demo block or the SVG geometry"
                    }.toList()
            }
        violations shouldBe emptyList()

        val home = SitePageRenderer.render(SitePages.HOME)
        listOf(
            "17,660,839",
            "80.27%",
            "77.92%",
            "Staten Island",
            "rendered 3 templates, ran the draft",
            "/api/x/demo/top-company-by-borough",
        ).forEach { fact -> home shouldContain fact }
        val engineering = SitePageRenderer.render(SitePages.HOW_IT_WORKS)
        listOf(
            "17,660,839",
            "6,078,750",
            "80.27%",
            "523 rows · 482 ms",
            "263 rows · 204 ms",
            "377k rows",
            "stage_company_zone",
            "awaiting a human release",
        ).forEach { fact -> engineering shouldContain fact }
    }

    /**
     * The demo partial with its legitimate digit positions blanked: HTML comments, every
     * geometry attribute's value (`x`, `y`, `width`, `height`, `d`, `viewBox` … — layout,
     * not data), and every `th:*` attribute whose value carries a `${…}` expression (the
     * rendered text comes from the block; a `th:` attribute with a LITERAL value keeps its
     * digits and is reported). Blanking preserves offsets, so a violation's line is real.
     */
    private fun stripDemoExempt(text: String): String {
        val chars = text.toCharArray()

        fun blank(range: IntRange) {
            for (i in range) {
                if (chars[i] != '\n') chars[i] = ' '
            }
        }

        HTML_COMMENT.findAll(text).forEach { blank(it.range) }
        DEMO_ATTR.findAll(text).forEach { attr ->
            val name = attr.groupValues[1]
            val value = attr.groups[2] ?: return@forEach
            if (name in SVG_GEOMETRY || (name.startsWith("th:") && "\${" in value.value)) {
                blank(value.range)
            }
        }
        return String(chars)
    }

    /** The counted-noun pattern: a spelled-out or numeric count directly before the noun. */
    private fun violationsIn(file: File): List<String> {
        val text = file.readText()
        val exempt = exemptSpans(text, file.extension == "kt")
        return HAND_TYPED_COUNT
            .findAll(text)
            .filter { match -> exempt.none { match.range.first in it } }
            .map { match ->
                val line = text.take(match.range.first).count { it == '\n' } + 1
                "${file.name}:$line — \"${match.value.replace("\n", " ")}\""
            }.toList()
    }

    /**
     * The spans where a literal count is legal because the RENDERED text is derived: any HTML
     * tag carrying a `th:` attribute that references `${facts` (the whole tag, so the static
     * fallback of a `th:alt` survives), the inner content of an element whose `th:text` or
     * `th:utext` references `${facts`, and — for Kotlin — any string literal referencing
     * `${facts`. The attribute VALUE referencing `${facts` is exempt too, so a count written
     * inside the deriving expression itself is not reported.
     */
    private fun exemptSpans(
        text: String,
        kotlin: Boolean,
    ): List<IntRange> {
        val spans = mutableListOf<IntRange>()
        for (tag in HTML_TAG.findAll(text)) {
            val attrs = tag.groupValues[2]
            if (!FACTS_REF.containsMatchIn(attrs)) continue
            spans += tag.range
            val tagName = tag.groupValues[1]
            if (TH_TEXT_ATTR.containsMatchIn(attrs)) {
                val close = text.indexOf("</$tagName", tag.range.last + 1, ignoreCase = true)
                if (close > 0) spans += (tag.range.last + 1)..close
            }
        }
        for (attr in HTML_ATTR.findAll(text)) {
            val value = attr.groups[1] ?: continue
            if (FACTS_REF.containsMatchIn(value.value)) spans += value.range
        }
        if (kotlin) {
            for (literal in KOTLIN_STRING.findAll(text)) {
                if (FACTS_REF.containsMatchIn(literal.value)) spans += literal.range
            }
        }
        return spans
    }

    private fun templateFiles(): List<File> =
        File(repoRoot(), "modules/web/src/main/resources/templates/site")
            .listFiles { f -> f.extension == "html" }
            ?.sorted()
            ?: error("templates/site not found under ${repoRoot()}")

    private fun kotlinFiles(): List<File> =
        File(repoRoot(), "modules/web/src/main/kotlin/co/datapipelines/web/ui/site")
            .listFiles { f -> f.extension == "kt" }
            ?.sorted()
            ?: error("ui/site not found under ${repoRoot()}")

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "modules/web").isDirectory) dir = dir.parentFile
        return checkNotNull(dir) { "no ancestor of ${File("").absolutePath} holds modules/web" }
    }

    private companion object {
        /**
         * A count adjacent to a counted noun. The nouns are the catalogs the site states
         * numbers about; the count may carry one "SQL " qualifier ("six SQL engines").
         */
        val HAND_TYPED_COUNT =
            Regex(
                """(?i)\b(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|\d+)\s+""" +
                    """(SQL\s+)?(engines?|MCP tools|tools|calculators?|kinds|node types?|error codes?)\b""",
            )

        /** An expression referencing the facts object — the derivation marker (also inside #strings.* wrappers). */
        val FACTS_REF = Regex("""\$\{[^}]*\bfacts\.""")

        /** An HTML start tag: name in group 1, attributes (with leading space) in group 2. */
        val HTML_TAG = Regex("""<([a-zA-Z][a-zA-Z0-9-]*)((?:\s+[\w:-]+(?:\s*=\s*"[^"]*")?)*)\s*/?>""")

        /** An HTML attribute with a quoted value: the value (with offsets) in group 1. */
        val HTML_ATTR = Regex("""[\w:-]+\s*=\s*"([^"]*)"""")

        /** `th:text` or `th:utext` — the attributes whose ELEMENT CONTENT is rewritten at render. */
        val TH_TEXT_ATTR = Regex("""th:(?:u)?text\s*=""")

        /** A single-line Kotlin string literal (escaped quotes handled). */
        val KOTLIN_STRING = Regex(""""(?:[^"\\\n]|\\.)*"""")

        /** 28 templates and 12 Kotlin files today; well under either means the sweep lost the directories. */
        const val MIN_TEMPLATES = 20
        const val MIN_KOTLIN = 8

        /** The four 133 demo partials the arm sweeps. */
        val DEMO_PARTIALS = listOf("_demo-api.html", "_demo-console.html", "_demo-dag.html", "_demo-datasources.html")

        /** An HTML comment. */
        val HTML_COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)

        /** An HTML attribute with a quoted value: the name in group 1, the value in group 2. */
        val DEMO_ATTR = Regex("""([\w:-]+)\s*=\s*"([^"]*)"""")

        /** The SVG attributes whose values are layout geometry, never run data. */
        val SVG_GEOMETRY =
            setOf("x", "y", "x1", "x2", "y1", "y2", "cx", "cy", "r", "rx", "ry", "width", "height", "d", "viewBox", "transform")

        /** A numeric digit — one NOT hugged by a letter ("H2" is a product name, not a number). */
        val TYPED_DIGIT = Regex("""(?<![A-Za-z])\d""")
    }
}
