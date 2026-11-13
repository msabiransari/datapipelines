package co.datapipelines.web.ui.site

import co.datapipelines.visualization.ChartSchemas
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.web.util.HtmlUtils

/**
 * #871 (2026-11-12) — the public site names only the chart families the `chart` kind draws.
 *
 * The truth is `docs/dashboards.md` §2.1.2: the chart document v1 has the bar family, and the
 * served schema files are its contract. The served families are read from [ChartSchemas.FILES]
 * (every file but the shared skeleton), so a new family widens this guard with no edit here.
 *
 * What is scanned: every page in [SitePages.ALL] rendered through [SitePageRenderer] (the real
 * handlers), HTML comments and `<code>` stripped, tags removed, HTML unescaped — the house shape
 * of [SiteProseHygieneTest].
 *
 * The rules, each falsified at birth (the red runs are in the lane's evidence):
 *  1. **No unserved chart family beside a chart.** A visible block (`p`, `li`, `summary`,
 *     `h1`-`h3`) that mentions `chart` or `charts` and names a word of [FAMILY_WORDS] that is not
 *     a served family fails, naming the page and the word. The unit is the block, not the
 *     sentence: the retired dashboards paragraph listed its families in a sentence without the
 *     word `chart`.
 *  2. **No `Plotly` in visible text.** The charting library is the host page's detail
 *     (`docs/dashboards.md` §6.3-§6.4); the public copy promises the document, not the library.
 *  3. **Non-vacuity** — at least [MIN_PAGES] pages and at least [MIN_CHART_BLOCKS] chart blocks,
 *     so a changed extraction shape that scanned nothing fails loudly.
 *
 * Each rule has a named exception map, empty today; an entry carries its reason.
 */
class SiteChartFamilyClaimsTest {
    private data class Page(
        val path: String,
        val visibleText: String,
        val blocks: List<String>,
    )

    /** The families the product serves today, from the code that serves them. */
    private val served: Set<String> =
        ChartSchemas.FILES.map { it.removeSuffix(SCHEMA_SUFFIX) }.toSet() - SKELETON_NAME

    private val pages: List<Page> by lazy {
        SitePages.ALL.map { page ->
            val html = SitePageRenderer.render(page)
            val stripped = CODE.replace(COMMENT.replace(html, " "), " ")
            val blocks =
                SCANNED_TAGS.flatMap { tag ->
                    Regex("<$tag\\b[^>]*>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
                        .findAll(stripped)
                        .map { visible(it.groupValues[1]) }
                        .filter { it.isNotEmpty() }
                        .toList()
                }
            Page(page.path, visible(SCRIPT_OR_STYLE.replace(stripped, " ")), blocks)
        }
    }

    @Test
    fun `the sweep is not vacuous`() {
        val chartBlocks = pages.sumOf { p -> p.blocks.count { CHART_WORD.containsMatchIn(it) } }
        println("SiteChartFamilyClaimsTest: pages=${pages.size} chartBlocks=$chartBlocks served=$served")
        withClue("pages rendered and scanned") { pages.size shouldBeGreaterThanOrEqual MIN_PAGES }
        withClue("visible blocks mentioning chart or charts across the site") {
            chartBlocks shouldBeGreaterThanOrEqual MIN_CHART_BLOCKS
        }
        withClue("served chart families read from ChartSchemas.FILES") { served shouldContain "bar" }
    }

    @Test
    fun `no page names a chart family the chart kind does not draw`() {
        val offenders =
            pages.flatMap { p ->
                p.blocks
                    .filter { CHART_WORD.containsMatchIn(it) }
                    .flatMap { block -> unservedFamilyWords(block, served) }
                    .distinct()
                    .sorted()
                    .filter { word -> (p.path to word) !in FAMILY_EXCEPTIONS }
                    .map { word -> "${p.path} names '$word' (served: ${served.sorted()})" }
            }
        withClue("unserved chart families on the public site (docs/dashboards.md §2.1.2):\n${offenders.joinToString("\n")}") {
            offenders.shouldBeEmpty()
        }
    }

    @Test
    fun `no page names the charting library`() {
        val offenders =
            pages
                .filter { namesPlotly(it.visibleText) && it.path !in PLOTLY_EXCEPTIONS }
                .map { "${it.path} names Plotly in visible text" }
        withClue("the charting library named on the public site (docs/dashboards.md §6.3-§6.4):\n${offenders.joinToString("\n")}") {
            offenders.shouldBeEmpty()
        }
    }

    @Test
    fun `control - the retired dashboards paragraph names seven unserved families`() {
        unservedFamilyWords(OLD_DASHBOARDS_TEXT, setOf("bar")) shouldBe
            listOf("3D", "box", "heatmap", "histogram", "line", "pie", "scatter")
    }

    @Test
    fun `control - the bar-only paragraph names none`() {
        unservedFamilyWords(NEW_DASHBOARDS_TEXT, setOf("bar")).shouldBeEmpty()
    }

    @Test
    fun `control - a served family is not an offender`() {
        unservedFamilyWords("Line charts and bar charts.", setOf("bar", "line")).shouldBeEmpty()
    }

    @Test
    fun `control - the library rule stands apart from the family rule`() {
        val sentence = "Rendered with Plotly."
        namesPlotly(sentence).shouldBeTrue()
        unservedFamilyWords(sentence, setOf("bar")).shouldBeEmpty()
        unservedFamilyWords(sentence, FAMILY_WORDS.map { it.lowercase() }.toSet()).shouldBeEmpty()
        namesPlotly(NEW_DASHBOARDS_TEXT).shouldBeFalse()
        namesPlotly("a plotlyish name").shouldBeFalse()
    }

    private fun visible(fragment: String): String =
        HtmlUtils
            .htmlUnescape(TAG.replace(fragment, " "))
            .replace(WHITESPACE, " ")
            .trim()

    private companion object {
        /** 41 registry pages measured on 2026-11-12; the owner's floor is 35 marketing pages. */
        const val MIN_PAGES = 35

        /** 33 chart blocks measured on 2026-11-12; the floor is 20, and the sweep prints the count. */
        const val MIN_CHART_BLOCKS = 20

        const val SCHEMA_SUFFIX = ".schema.json"
        const val SKELETON_NAME = "skeleton"

        /** The chart-family vocabulary a page might promise; matched as whole words, ignoring case. */
        val FAMILY_WORDS: List<String> =
            listOf("bar", "line", "scatter", "pie", "histogram", "box", "heatmap", "area", "donut", "radar", "3D")

        /** (page path, family word) → reason. Empty: no page may name an unserved family today. */
        val FAMILY_EXCEPTIONS: Map<Pair<String, String>, String> = emptyMap()

        /** Page path → reason. Empty: no page needs to name the library today. */
        val PLOTLY_EXCEPTIONS: Map<String, String> = emptyMap()

        val CHART_WORD = Regex("""\bcharts?\b""", RegexOption.IGNORE_CASE)
        val PLOTLY_WORD = Regex("""\bPlotly\b""", RegexOption.IGNORE_CASE)

        val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        val CODE = Regex("""<code\b.*?</code>""", RegexOption.DOT_MATCHES_ALL)
        val SCRIPT_OR_STYLE = Regex("""<(script|style)\b.*?</\1>""", RegexOption.DOT_MATCHES_ALL)
        val TAG = Regex("<[^>]+>")
        val WHITESPACE = Regex("""\s+""")
        val SCANNED_TAGS = listOf("p", "li", "summary", "h1", "h2", "h3")

        const val OLD_DASHBOARDS_TEXT =
            "A Plotly chart, a table or a KPI card, bound to named inputs rather than to one pipeline. The same " +
                "release can appear on several dashboards. Bar, line and scatter, pie, histogram, box and heatmap are " +
                "supported, with three 3D trace types loaded only when a dashboard uses one."

        const val NEW_DASHBOARDS_TEXT =
            "A chart, a table or a KPI card, bound to named inputs rather than to one pipeline. The same release can " +
                "appear on several dashboards. A chart is a library-neutral document, checked at save against a " +
                "schema the product publishes, so it names what to read and never a charting library's settings. " +
                "Bar charts are the family available today: vertical or horizontal, grouped, stacked or stacked to 100%."

        /**
         * The family words of [block] that are not in [served], in sorted order, spelled as in
         * [FAMILY_WORDS]. Family words only: it knows nothing of the charting library.
         */
        fun unservedFamilyWords(
            block: String,
            served: Set<String>,
        ): List<String> {
            val servedLower = served.map { it.lowercase() }.toSet()
            return FAMILY_WORDS
                .filter { it.lowercase() !in servedLower }
                .filter { Regex("""\b${Regex.escape(it)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(block) }
                .sorted()
        }

        /** True when [text] names the charting library as a whole word. Takes no served set, so none can exempt it. */
        fun namesPlotly(text: String): Boolean = PLOTLY_WORD.containsMatchIn(text)
    }
}
