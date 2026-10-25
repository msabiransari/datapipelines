package co.datapipelines.web.ui.site

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File

/**
 * #699 — the site states what ships: the scheduler, dashboards, parameter sets and SCRIPT
 * nodes, with keys v2's role rule (docs/auth.md §7.5). Two guards:
 *
 *  1. **No stale capability claim** — the phrasings the pre-#699 copy used for "dashboards and
 *     the scheduler are planned", "no scheduler", "SQL and nothing else" and the retired PK4
 *     key cap are banned from every rendered marketing page (visible text, attributes and the
 *     FAQ JSON-LD) AND from the FAQ sources, so an answer no page renders yet cannot carry one.
 *  2. **The capability pages stay wired** — each of the three pages is a registry page with its
 *     record H1, links the docs and siblings the design record names, and is reachable from the
 *     home page, How it works and the shared header and footer.
 */
class SiteCapabilitiesContentTest {
    private val rendered: Map<SitePage, String> by lazy { SitePages.ALL.associateWith { SitePageRenderer.render(it) } }

    @Test
    fun `no rendered page states a capability as planned that ships`() {
        val offenders =
            rendered.flatMap { (page, html) ->
                val copy = normalize(COMMENT.replace(html, " "))
                val text = normalize(TAG.replace(COMMENT.replace(html, " "), " "))
                STALE_CLAIMS.filter { it in copy || it in text }.map { "${page.path}: \"$it\"" }
            }
        withClue("stale capability claims render (docs/scheduler.md §1, docs/dashboards.md §1, docs/auth.md §7.5)") {
            offenders.shouldBeEmpty()
        }
    }

    @Test
    fun `no FAQ source carries a stale capability claim`() {
        val sources = faqSources()
        withClue("the FAQ sources are gone — the arm would pass by reading nothing") { (sources.size >= FAQ_SOURCE_FLOOR) shouldBe true }
        val offenders =
            sources.flatMap { file ->
                val literals = normalize(stringLiterals(file.readText()))
                STALE_CLAIMS.filter { it in literals }.map { "${file.name}: \"$it\"" }
            }
        withClue("stale capability claims in FAQ answers") { offenders.shouldBeEmpty() }
    }

    @Test
    fun `the three capability pages are registry pages with their record headings`() {
        CAPABILITY_H1.forEach { (page, h1) ->
            SitePages.ALL shouldContain page
            withClue("${page.path} keeps its design-record H1") { normalize(TAG.replace(rendered.getValue(page), " ")) shouldContain h1 }
        }
    }

    @Test
    fun `each capability page links the docs and siblings the record names`() {
        REQUIRED_LINKS.forEach { (page, targets) ->
            val hrefs = hrefs(rendered.getValue(page))
            targets.forEach { target -> withClue("${page.path} links $target") { hrefs shouldContain target } }
        }
    }

    @Test
    fun `home, how it works, the SaaS page and the docs index reach the capability pages`() {
        val docsIndex = hrefs(SitePageRenderer.renderDocsIndex())
        CAPABILITY_H1.keys.forEach { page ->
            listOf(SitePages.HOME, SitePages.HOW_IT_WORKS).forEach { from ->
                withClue("${from.path} links ${page.path} in its own content, not only the shared chrome") {
                    hrefs(mainContent(rendered.getValue(from))) shouldContain page.path
                }
            }
            withClue("the public docs index reaches ${page.path} (its shared Product nav and footer)") { docsIndex shouldContain page.path }
        }
        withClue("/for/saas-teams owns the customer-analytics case and links the dashboards page") {
            hrefs(mainContent(rendered.getValue(SitePages.FOR_SAAS_TEAMS))) shouldContain SitePages.SQL_DASHBOARDS.path
        }
    }

    private fun hrefs(html: String): Set<String> = HREF.findAll(html).map { it.groupValues[1].substringBefore('#') }.toSet()

    /** The page body between the header and the footer, so the shared navigation cannot satisfy a content link. */
    private fun mainContent(html: String): String = html.substringAfter("<main").substringBefore("</main>")

    private fun normalize(s: String): String = WHITESPACE.replace(s, " ").lowercase()

    /** Every string literal in a Kotlin source, joined, so `"a " +` / `"b"` continuations read as one sentence. */
    private fun stringLiterals(source: String): String = STRING_LITERAL.findAll(source).joinToString("") { it.groupValues[1] }

    private fun faqSources(): List<File> =
        File(repoRoot(), "modules/web/src/main/kotlin/co/datapipelines/web/ui/site")
            .listFiles { f -> f.extension == "kt" && (f.name.startsWith("SiteFaqs") || f.name == "SiteCapabilitiesFaqs.kt") }
            ?.sorted()
            ?: error("ui/site not found under ${repoRoot()}")

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "modules/web").isDirectory) dir = dir.parentFile
        return checkNotNull(dir) { "no ancestor of ${File("").absolutePath} holds modules/web" }
    }

    private companion object {
        /** SiteFaqs, SiteFaqsHub, SiteFaqsCluster, SiteFaqsBatch2 and SiteCapabilitiesFaqs at #699. */
        const val FAQ_SOURCE_FLOOR = 5

        /**
         * Lower-case phrasings the pre-#699 site used for what now ships. Each is a whole claim,
         * never a word that a true sentence also uses ("planned" alone stays legitimate: mail,
         * reports, extracts and alerts are planned).
         */
        val STALE_CLAIMS =
            listOf(
                "native dashboards are planned",
                "dashboards are planned",
                "the planned dashboards",
                "planned the dashboard beside it",
                "charts and dashboards are next",
                "native scheduling is planned",
                "scheduling is planned",
                "no scheduler, no dashboards",
                "native embeddable dashboards",
                "script nodes, designed",
                "runs sql and nothing else",
                "capped at author",
                "its person's role",
            )

        val CAPABILITY_H1 =
            mapOf(
                SitePages.PIPELINE_SCHEDULING to "run your pipelines on a schedule.",
                SitePages.SQL_DASHBOARDS to "turn pipeline results into useful dashboards.",
                SitePages.REUSABLE_PARAMETER_SETS to "define inputs once. reuse them in dashboards.",
            )

        /** Design record §5: the docs each page cites, and the siblings it hands the reader to. */
        val REQUIRED_LINKS =
            mapOf(
                SitePages.PIPELINE_SCHEDULING to listOf("/docs/scheduler"),
                SitePages.SQL_DASHBOARDS to listOf("/docs/dashboards", "/for/saas-teams", "/reusable-parameter-sets"),
                SitePages.REUSABLE_PARAMETER_SETS to listOf("/sql-dashboards", "/docs/rest-api", "/docs/mcp-server"),
            )

        val HREF = Regex("""<a[^>]*\shref="([^"]*)"""")
        val TAG = Regex("<[^>]+>")
        val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        val WHITESPACE = Regex("\\s+")
        val STRING_LITERAL = Regex(""""((?:[^"\\\n]|\\.)*)"""")
    }
}
