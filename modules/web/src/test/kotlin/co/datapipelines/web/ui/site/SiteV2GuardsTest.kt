package co.datapipelines.web.ui.site

import co.datapipelines.mcp.McpToolCatalog
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
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
 *     build: the page promises months, so it has to be revisited.
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
    fun `the roadmap page is younger than the promise it makes`() {
        val html = rendered.getValue(SitePages.ROADMAP)
        val updated = Regex("""data-roadmap-updated="([0-9-]+)"""").find(html)!!.groupValues[1]
        val age = ChronoUnit.DAYS.between(LocalDate.parse(updated), LocalDate.now())
        withClue(
            "/roadmap says 'Last updated $updated' — $age days ago; the page names months, so it is revisited at least every $ROADMAP_MAX_AGE_DAYS days",
        ) {
            (age <= ROADMAP_MAX_AGE_DAYS) shouldBe true
        }
        // The JSON-LD's dateModified is the same date — one value, two places.
        html shouldContain "\"dateModified\": \"$updated\""
    }

    @Test
    fun `the tools page lists every catalogue name and nothing else`() {
        val html = rendered.getValue(SitePages.MCP_TOOLS)
        val rows = TOOL_ROW.findAll(html).map { it.groupValues[1] to it.groupValues[2] }.toList()
        rows.map { it.first }.sorted() shouldBe McpToolCatalog.NAMES.sorted()
        // Every row carries its scope from the matrix — never the "—" fallback.
        rows.forEach { (name, scope) -> withClue("$name has a scope") { (scope != "—") shouldBe true } }
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

        rendered.forEach { (page, html) ->
            HREF.findAll(html).map { it.groupValues[1] }.forEach { href ->
                val target = href.substringBefore('#').substringBefore('?')
                val external = href.startsWith("http") || href.startsWith("mailto:")
                val anchorOnly = target.isEmpty()
                if (!external && !anchorOnly && target !in knownAppRoutes) {
                    val resolves =
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
                    if (!resolves) dead += "${page.path} -> $href"
                }
            }
        }
        withClue("dead internal links") { dead.shouldBeEmpty() }
        val orphans = registryPaths.filter { it != SitePages.HOME.path && inbound[it].isNullOrEmpty() }
        withClue("registry pages no other page links to") { orphans.shouldBeEmpty() }
    }

    private companion object {
        const val ROADMAP_MAX_AGE_DAYS = 120L
        val MAPPER = ObjectMapper()
        val LD_JSON = Regex("""<script type="application/ld\+json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

        /** One visible FAQ entry: the summary and the answer paragraph the fragment renders. */
        val DETAILS = Regex("""<details[^>]*>\s*<summary>(.*?)</summary>\s*<p>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)

        /** One tool row on /mcp-tools: the name, then its first status chip (the scope). */
        val TOOL_ROW = Regex("""<li>\s*<code>([a-z_]+)</code>\s*<span class="status">([^<]*)</span>""")
        val HREF = Regex("""<a[^>]*\shref="([^"]*)"""")
    }
}
