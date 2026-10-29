package co.datapipelines.web.ui.site

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
 *     #763 (owner, 2026-10-28) adds the retired category phrase "sql scheduler" — the site does
 *     not name it even to correct it — with dash variants folded, on every rendered page.
 *  2. **The capability pages stay wired** — each of the three pages is a registry page with its
 *     record H1, links the docs and siblings the design record names, and is reachable from the
 *     home page, How it works and the shared header and footer.
 *  3. **The #700 link architecture** — every contextual link the SEO record's link graph names
 *     is a real `<a href>` inside the source page's `<main>`, so the shared Product menu and the
 *     footer cannot stand in for a reader's next step; a missing one fails as missing discovery,
 *     naming the source and the target, never as a lower total.
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
    fun `no rendered docs page carries the retired scheduler category phrase`() {
        // #763 (owner, 2026-10-28): the docs arm bans THIS phrase only — the docs legitimately
        // narrate history, so the whole STALE_CLAIMS list stays a marketing-pages guard.
        val slugs = SitePageRenderer.docs.index().flatMap { group -> group.docs.map { it.slug } }
        check(slugs.size >= DOCS_FLOOR) { "only ${slugs.size} packaged docs reached the sweep" }
        val rendered =
            slugs.associateWith { SitePageRenderer.renderDoc(it) } + mapOf("index" to SitePageRenderer.renderDocsIndex())
        val offenders =
            rendered.flatMap { (slug, html) ->
                val copy = normalize(COMMENT.replace(html, " "))
                val text = normalize(TAG.replace(COMMENT.replace(html, " "), " "))
                STALE_SCHEDULER_CATEGORY.filter { it in copy || it in text }.map { "docs/$slug: \"$it\"" }
            }
        withClue("the retired scheduler category phrase renders in the packaged docs") { offenders.shouldBeEmpty() }
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
        // #700: in the page's own content — the shared Product menu links every sibling, so a
        // whole-document scan passed with the sibling link deleted from the body.
        REQUIRED_LINKS.forEach { (page, targets) ->
            val hrefs = hrefs(mainContent(rendered.getValue(page)))
            targets.forEach { target -> withClue("${page.path} links $target in its own content") { hrefs shouldContain target } }
        }
    }

    @Test
    fun `every contextual link in the record's link architecture is in its source page's content`() {
        withClue("the link map is non-vacuous") { (LINK_ARCHITECTURE.values.sumOf { it.size } >= LINK_ARCHITECTURE_FLOOR) shouldBe true }
        val missing =
            LINK_ARCHITECTURE.flatMap { (from, targets) ->
                val hrefs = hrefs(mainContent(SitePageRenderer.render(from)))
                targets.filterNot { it.path in hrefs }.map { "missing discovery: ${from.path} has no contextual link to ${it.path}" }
            }
        withClue(missing.joinToString("\n")) { missing.shouldBeEmpty() }
    }

    @Test
    fun `every engine page hands the reader to the SQL MCP hub and the tutorial`() {
        val missing =
            SitePages.ENGINES.map(SitePages::enginePage).flatMap { page ->
                val hrefs = hrefs(mainContent(SitePageRenderer.render(page)))
                listOf(SitePages.PILLAR, SitePages.HOW_IT_WORKS)
                    .filterNot { it.path in hrefs }
                    .map { "missing discovery: ${page.path} has no contextual link to ${it.path}" }
            }
        withClue(missing.joinToString("\n")) { missing.shouldBeEmpty() }
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

    @Test
    fun `the scheduler page keeps the generic category the 2026-10-25 correction requires`() {
        // #735 owner correction (2026-10-25): the product is a generic job scheduler; the
        // pipeline is the job type it runs today. The SQL-only category title/lede and any
        // present-tense report/AI availability must not return; the pipeline example stays.
        val html = rendered.getValue(SitePages.PIPELINE_SCHEDULING)
        val title =
            TITLE_TAG.find(html)?.groupValues?.get(1)
                ?: error("no <title> rendered for ${SitePages.PIPELINE_SCHEDULING.path}")
        withClue("the title must lead with the generic scheduler category (#735)") {
            normalize(title) shouldContain "job scheduler"
        }
        withClue("the retired SQL-only category title must not return (#735)") {
            normalize(title) shouldNotContain "sql scheduler"
        }
        val body = normalize(COMMENT.replace(html, " "))
        withClue("the current job type is pipeline; SQL is the example (#735)") {
            body shouldNotContain "the job it runs today is a sql pipeline"
        }
        withClue("the old SQL-only lede must not return (#735)") { body shouldNotContain "the built-in sql scheduler" }
        withClue("present-tense report/AI availability must not appear (#735)") {
            body shouldNotContain "the scheduler runs reports"
            body shouldNotContain "the scheduler runs ai jobs"
        }
        // Allow side, and the sweep's non-vacuity: the correction is about the CATEGORY, not
        // about deleting SQL — the page must still walk a pipeline example.
        withClue("the pipeline example must survive the correction (#735)") { body shouldContain "sql pipeline" }
    }

    private fun hrefs(html: String): Set<String> = HREF.findAll(html).map { it.groupValues[1].substringBefore('#') }.toSet()

    /** The page body between the header and the footer, so the shared navigation cannot satisfy a content link. */
    private fun mainContent(html: String): String = html.substringAfter("<main").substringBefore("</main>")

    /** Lower-case, dash variants folded to spaces, whitespace collapsed — the keyword table's `norm`. */
    private fun normalize(s: String): String = WHITESPACE.replace(DASH.replace(s.lowercase(), " "), " ")

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
         * DocsPublicSeoTest's floor, reused: the docs arm sweeps the whole packaged catalog,
         * never a sample, so an empty catalog cannot pass by checking nothing.
         */
        const val DOCS_FLOOR = 15

        /**
         * #763 (owner, 2026-10-28): the one phrase the docs arm bans — the public site never
         * says "SQL scheduler", dash variants included, and the packaged docs render through the
         * same public chrome the marketing pages do. The docs' historical narration of other
         * terms stays legitimate; this list must not grow into STALE_CLAIMS.
         */
        val STALE_SCHEDULER_CATEGORY = listOf("sql scheduler")

        /**
         * Lower-case phrasings the pre-#699 site used for what now ships. Each is a whole claim,
         * never a word that a true sentence also uses ("planned" alone stays legitimate: mail,
         * reports, extracts and alerts are planned). The #735 correction (2026-10-25) adds the
         * old SQL-only scheduler lede and the present-tense report/AI availability claims; the
         * category TITLE itself is pinned by the dedicated #735 test below. #763 (owner,
         * 2026-10-28) adds the bare category phrase itself: the public site never says
         * "SQL scheduler", in any spelling the dash fold unifies.
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
                "the built-in sql scheduler",
                "the scheduler runs reports",
                "the scheduler runs ai jobs",
                // #763, owner 2026-10-28: the site never names this category, even to correct it.
                "sql scheduler",
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

        /**
         * #700 design record, "Content and link architecture": source page → the pages its own
         * content must link. Scheduling reaches authoring and the comparisons; dashboards reach
         * parameter sets, authoring and the SaaS case; parameter sets lead back to dashboards;
         * the AI pages, personas and comparisons hand the reader to the capability they describe.
         * The docs targets live in [REQUIRED_LINKS].
         */
        val LINK_ARCHITECTURE: Map<SitePage, List<SitePage>> =
            mapOf(
                SitePages.PIPELINE_SCHEDULING to
                    listOf(SitePages.AI_DATA_PIPELINE, SitePages.COMPARE_AIRFLOW, SitePages.COMPARE_DAGSTER_AIRFLOW),
                SitePages.SQL_DASHBOARDS to
                    listOf(SitePages.REUSABLE_PARAMETER_SETS, SitePages.AI_DATA_PIPELINE, SitePages.FOR_SAAS_TEAMS),
                SitePages.REUSABLE_PARAMETER_SETS to listOf(SitePages.SQL_DASHBOARDS),
                SitePages.AI_DATA_PIPELINE to
                    listOf(
                        SitePages.PIPELINE_SCHEDULING,
                        SitePages.SQL_DASHBOARDS,
                        SitePages.REUSABLE_PARAMETER_SETS,
                        SitePages.PUBLISHED_API,
                    ),
                SitePages.TEXT_TO_SQL_AGENT to listOf(SitePages.AI_DATA_PIPELINE, SitePages.PIPELINE_SCHEDULING, SitePages.SQL_DASHBOARDS),
                SitePages.FOR_SAAS_TEAMS to listOf(SitePages.SQL_DASHBOARDS, SitePages.REUSABLE_PARAMETER_SETS),
                SitePages.FOR_ANALYSTS to listOf(SitePages.PIPELINE_SCHEDULING, SitePages.SQL_DASHBOARDS),
                SitePages.FOR_AGENCIES to listOf(SitePages.PIPELINE_SCHEDULING, SitePages.SQL_DASHBOARDS),
                SitePages.USE_CASES to listOf(SitePages.PIPELINE_SCHEDULING, SitePages.SQL_DASHBOARDS),
                SitePages.COMPARE_AIRFLOW to listOf(SitePages.PIPELINE_SCHEDULING),
                SitePages.COMPARE_DAGSTER_AIRFLOW to listOf(SitePages.PIPELINE_SCHEDULING),
                SitePages.ROADMAP to
                    listOf(SitePages.PIPELINE_SCHEDULING, SitePages.SQL_DASHBOARDS, SitePages.REUSABLE_PARAMETER_SETS),
                SitePages.TABLEAU_ROADMAP to listOf(SitePages.SQL_DASHBOARDS),
            )

        /** The 28 edges above at #700; a map that lost its rows would pass by checking nothing. */
        const val LINK_ARCHITECTURE_FLOOR = 28

        val HREF = Regex("""<a[^>]*\shref="([^"]*)"""")
        val TAG = Regex("<[^>]+>")
        val TITLE_TAG = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
        val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        val DASH = Regex("[‑–—-]")
        val WHITESPACE = Regex("\\s+")
        val STRING_LITERAL = Regex(""""((?:[^"\\\n]|\\.)*)"""")
    }
}
