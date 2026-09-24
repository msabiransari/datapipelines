package co.datapipelines.web.ui.site

import co.datapipelines.application.datasources.LakeTableFormat
import co.datapipelines.mcp.McpToolCatalog
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.web.util.HtmlUtils
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Site v2's four mechanical guards (the mock's SEO table, 2026-09-09) — each one exists so a
 * rule the site depends on cannot rot silently:
 *
 *  1. **FAQ parity** — the `FAQPage` JSON-LD says exactly what the visible `<details>` say,
 *     question for question, answer for answer. The snippet a search engine shows must be a
 *     sentence on the page.
 *  2. **Roadmap freshness** — a public roadmap older than [ROADMAP_MAX_AGE_DAYS] fails the
 *     build: shipped and planned capabilities must be revisited even without release dates.
 *  3. **Tool-page completeness** — `/mcp-tools` lists every name the catalogue ships, and no
 *     other; the page is generated, and this is the proof it stayed so.
 *  4. **Link graph** — every internal link on every registry page resolves to a registry page,
 *     a packaged doc, an in-page anchor, or a known app route; and every registry page is
 *     reachable from at least one OTHER page (no orphans — a page nobody links is a page no
 *     crawler ranks).
 */
class SiteV2GuardsTest {
    private val rendered: Map<SitePage, String> by lazy { SitePages.ALL.associateWith { SitePageRenderer.render(it) } }

    @Test
    fun `every FAQ block's JSON-LD mirrors the visible details, question for question`() {
        val pagesWithFaq = rendered.filter { (_, html) -> "\"@type\":\"FAQPage\"" in html || "\"@type\": \"FAQPage\"" in html }
        pagesWithFaq.keys.map { it.path } shouldContainAll listOf("/", "/faq", "/security", "/published-api", "/tableau")

        pagesWithFaq.forEach { (page, html) ->
            val visible =
                DETAILS
                    .findAll(html)
                    .map { m -> HtmlUtils.htmlUnescape(m.groupValues[1]).trim() to HtmlUtils.htmlUnescape(m.groupValues[2]).trim() }
                    .toList()
            val ld =
                LD_JSON
                    .findAll(html)
                    .map { MAPPER.readTree(it.groupValues[1]) }
                    .first { it["@type"].asText() == "FAQPage" }
            val structured =
                ld["mainEntity"].map { q -> q["name"].asText().trim() to q["acceptedAnswer"]["text"].asText().trim() }
            withClue("FAQ parity on ${page.path}") {
                structured shouldBe visible
            }
            // Non-vacuity: a page that lost its FAQ still has to have one.
            withClue("${page.path} renders at least one FAQ entry") { visible.isNotEmpty() shouldBe true }
        }
    }

    @Test
    fun `the roadmap pages are younger than the promises they make`() {
        // Both roadmap pages are revisited on the same clock, even without release dates.
        // 119 §C.4/§B.7: /pricing promises "no paid tier today" and /semantic-layer dates
        // its today/next list the same way, so both promises are covered by this clock.
        listOf(SitePages.ROADMAP, SitePages.TABLEAU_ROADMAP, SitePages.PRICING, SitePages.SEMANTIC_LAYER).forEach { page ->
            val html = rendered.getValue(page)
            val updated = Regex("""data-roadmap-updated="([0-9-]+)"""").find(html)!!.groupValues[1]
            val age = ChronoUnit.DAYS.between(LocalDate.parse(updated), LocalDate.now())
            val clue =
                "${page.path} says 'Last updated $updated' — $age days ago; its feature status changes, " +
                    "so it is revisited at least every $ROADMAP_MAX_AGE_DAYS days"
            withClue(clue) {
                (age <= ROADMAP_MAX_AGE_DAYS) shouldBe true
            }
            // The JSON-LD's dateModified is the same date — one value, two places.
            html shouldContain "\"dateModified\": \"$updated\""
        }
    }

    /**
     * #120 extends the home page's undated roadmap policy to every marketing route, including
     * metadata and FAQ JSON-LD. A pipeline being reusable next month is not a release promise.
     */
    @Test
    fun `every marketing page keeps release plans undated`() {
        rendered.forEach { (page, html) ->
            // Keep tags so meta descriptions are checked too; normalize wrapped prose.
            val copy = COMMENT.replace(html, " ").replace(Regex("\\s+"), " ")
            val releaseCopy = copy.replace("rerun next month", "rerun later")
            withClue("${page.path}: a dated or month-named release promise is back") {
                DATED_PROMISE.containsMatchIn(releaseCopy) shouldBe false
            }
        }
        val text = TAG.replace(rendered.getValue(SitePages.HOME), " ")
        withClue("/: the planned list is still there (the arm would pass on an empty page)") {
            text shouldContain "Planned"
            text shouldContain "Native embeddable dashboards"
        }
    }

    /**
     * 160 — the lake's physical formats are [LakeTableFormat]'s closed set and nothing else:
     * any page naming one of the format words the copy could drift into (CSV, Avro, ORC,
     * Delta Lake) fails the build. The words were chosen because on this site they only ever
     * appear as lake-format claims; if a legitimate other use ever appears (an export-to-CSV
     * feature, say), this clue is the place the allowlist gets argued, not a silent substring.
     * Word-boundary matched, so `partner_iso_crosswalk.csv` (a file name) and `ORCLPDB1`
     * (a JDBC service name) never fire.
     */
    @Test
    fun `the lake formats the site names are exactly the LakeTableFormat enum's`() {
        val enumFormats = LakeTableFormat.entries.map { it.wire.replaceFirstChar(Char::uppercase) }.toSet()
        val formatWords = enumFormats + setOf("CSV", "Avro", "ORC", "Delta Lake")
        val named = Regex(formatWords.joinToString("|") { Regex.escape(it) }.let { """\b($it)\b""" })
        var pagesNamingAFormat = 0
        rendered.forEach { (page, html) ->
            val text = TAG.replace(COMMENT.replace(html, " "), " ")
            val hit = named.findAll(text).map { it.groupValues[1] }.toSet()
            if (hit.any { it in enumFormats }) pagesNamingAFormat++
            withClue("${page.path}: names lake format(s) outside LakeTableFormat ${enumFormats.sorted()}") {
                (hit - enumFormats).shouldBeEmpty()
            }
        }
        withClue("no rendered page names a lake format at all — the arm would pass on a site that never names one") {
            (pagesNamingAFormat > 0) shouldBe true
        }
    }

    /**
     * 160, re-based by #215 slice (c) — a key carries no scope at all since slice (b) (auth.md
     * §7.5: the `read` ⊂ `execute` ⊂ `author` ⊂ `admin` axis went with V34; a key holds a ROLE).
     * No marketing page may render a key scope, so the four literals a scope table or a scope
     * sentence renders are banned outright: every appearance of them on these pages has been a
     * scope claim. The last three were the tutorial's step 2 and the tools page's family
     * paragraph, rewritten to roles in the same slice; 160 banned only `admin`. The security
     * page's role wording is the exemplar this guard holds the site to.
     */
    @Test
    fun `no page names a key scope`() {
        rendered.forEach { (page, html) ->
            RETIRED_SCOPE_LITERALS.forEach { literal ->
                withClue("${page.path}: $literal renders — keys carry roles, not scopes (docs/auth.md §7.5)") {
                    html.contains(literal) shouldBe false
                }
            }
        }
        val security = rendered.getValue(SitePages.SECURITY)
        listOf("capped at author", "api caller").forEach { phrase ->
            withClue("the security page no longer states the key roles — the arm would pass on an empty page") {
                security.contains(phrase) shouldBe true
            }
        }
    }

    @Test
    fun `the tools page lists every catalogue name and nothing else`() {
        val html = rendered.getValue(SitePages.MCP_TOOLS)
        val rows = TOOL_ROW.findAll(html).map { it.groupValues[1] to it.groupValues[2] }.toList()
        rows.map { it.first }.sorted() shouldBe McpToolCatalog.NAMES.sorted()
        // Every row carries its catalog permission — never the "—" fallback.
        rows.forEach { (name, permission) -> withClue("$name has a permission") { (permission != "—") shouldBe true } }
    }

    @Test
    fun `every internal link resolves, and no registry page is an orphan`() {
        val registryPaths = SitePages.ALL.map { it.path }.toSet()
        val knownAppRoutes = setOf("/login", "/docs", "/skill.md", "/sitemap.xml", "/robots.txt")
        val docSlugs =
            SitePageRenderer.docs
                .index()
                .flatMap { g -> g.docs.map { it.slug } }
                .toSet()
        val inbound = mutableMapOf<String, MutableSet<String>>()
        val dead = mutableListOf<String>()

        fun resolves(
            page: SitePage,
            target: String,
        ): Boolean =
            when {
                target in registryPaths -> {
                    if (target != page.path) inbound.getOrPut(target) { mutableSetOf() }.add(page.path)
                    true
                }

                target.startsWith("/docs/") -> {
                    target.removePrefix("/docs/") in docSlugs
                }

                target.startsWith("/mcp-server/") -> {
                    SitePages.engine(target.removePrefix("/mcp-server/")) != null
                }

                else -> {
                    false
                }
            }

        rendered.forEach { (page, html) ->
            HREF
                .findAll(html)
                .map { it.groupValues[1] }
                .filterNot { it.startsWith("http") || it.startsWith("mailto:") }
                .map { it.substringBefore('#').substringBefore('?') }
                .filter { it.isNotEmpty() && it !in knownAppRoutes }
                .filterNot { resolves(page, it) }
                .forEach { dead += "${page.path} -> $it" }
        }
        withClue("dead internal links") { dead.shouldBeEmpty() }
        val orphans = registryPaths.filter { it != SitePages.HOME.path && inbound[it].isNullOrEmpty() }
        withClue("registry pages no other page links to") { orphans.shouldBeEmpty() }
    }

    /**
     * 133 §B.3 — **no placeholder ships.** Every `[shot: …]` slot was replaced by a rendered
     * component (the console, the DAG, a table) or removed with its copy; a placeholder that
     * comes back fails the build. Falsified at birth: this arm was committed while
     * placeholders still shipped and named every one of them.
     */
    @Test
    fun `no shot placeholder survives on any rendered site page`() {
        val offenders =
            rendered
                .filter { (_, html) -> "[shot:" in html }
                .keys
                .map { it.path }
                .sorted()
        withClue("pages still shipping a [shot: …] placeholder") { offenders.shouldBeEmpty() }
    }

    private companion object {
        const val ROADMAP_MAX_AGE_DAYS = 120L

        /** The retired key scopes as a scope table or sentence renders them (auth.md §7.5, #215). */
        val RETIRED_SCOPE_LITERALS = listOf("<code>read</code>", "<code>execute</code>", "<code>author</code>", "<code>admin</code>")
        val MAPPER = ObjectMapper()
        val LD_JSON = Regex("""<script type="application/ld\+json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

        /**
         * One visible FAQ entry: the summary and the answer paragraph the fragment renders. The
         * question is matched WITHOUT a lazy run across tags: since 130 the header carries a
         * `<details>` of its own (the Product disclosure — its summary is followed by a `<nav>`,
         * not a `<p>`), and a `(.*?)` from that summary would run on to the first FAQ entry.
         */
        val DETAILS = Regex("""<details[^>]*>\s*<summary>([^<]*)</summary>\s*<p>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)

        /** One tool row on /mcp-tools: the name, then its first status chip (the catalog permission). */
        val TOOL_ROW = Regex("""<li>\s*<code>([a-z_]+)</code>\s*<span class="status">([^<]*)</span>""")
        val HREF = Regex("""<a[^>]*\shref="([^"]*)"""")
        val TAG = Regex("<[^>]+>")
        val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

        /**
         * Upcoming-month promises (also hyphenated) and explicit month/year release dates —
         * and, since 160, the dated-roadmap phrasings those month patterns missed (#120):
         * "next release", "with dates", "carries the date", "dates both", ", dated" and the
         * time-bound "this year". The roadmap page's own rule is "no announced release date";
         * every marketing page answers to the same rule.
         */
        val DATED_PROMISE =
            Regex(
                """\b(next[ -](month|release)|with[ -]dates|carries the date|dates both|, dated|this year|""" +
                    """(January|February|March|April|May|June|July|August|September|October|November|December) 20\d\d)\b""",
                RegexOption.IGNORE_CASE,
            )
    }
}
