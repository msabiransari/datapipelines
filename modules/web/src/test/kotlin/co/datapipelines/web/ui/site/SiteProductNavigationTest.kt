package co.datapipelines.web.ui.site

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * The header's Product navigation (148, #115 reopened): the seven feature pages are reachable
 * from the header again — a `<details>` disclosure in the wide row and a labelled group in
 * the phone menu — and the two menus cannot drift apart.
 *
 *  1. **The seven are the seven, in order, and every one is a registry page.** The wide
 *     disclosure's links equal [PRODUCT_PATHS]; each path is in `SitePages.ALL`, so a page
 *     dropped from the registry (and therefore from the sitemap) is named here, not found by
 *     a visitor as a dead link.
 *  2. **Parity by construction, and proven on the render.** Both menus render ONE fragment
 *     (`productLinks` in the site layout), and the rendered phone group equals the rendered
 *     disclosure link for link — path AND label — so a link edited in one place is the same
 *     link in the other. The source check holds the mechanism; the render check holds the
 *     outcome.
 *  3. **The primary entries stay.** How it works, Use cases, Pricing, Explore, Docs and the
 *     host-aware application entry are still in both menus, in the approved order, after the
 *     disclosure.
 *  4. **Current page, in both menus and nowhere else.** On a feature page, exactly that link
 *     carries `aria-current="page"` in the disclosure and in the phone group; on the homepage
 *     no header link does.
 *  5. **Native and labelled.** The disclosure is a `<details>` whose summary reads "Product"
 *     (usable without JavaScript, like the phone menu); the phone group is a `role="group"`
 *     labelled by its visible heading.
 *
 * Non-vacuity: the list is asserted to be exactly seven, and the layout source must reference
 * the fragment exactly twice. Falsified at birth by removing one link from the fragment (arm 1
 * names the missing path; arm 2's source count stays at two, so the render check is the one
 * that catches a link typed into only one menu).
 */
class SiteProductNavigationTest {
    private val home = SitePageRenderer.render(SitePages.HOME)
    private val featurePage = SitePageRenderer.render(SitePages.SEMANTIC_LAYER)

    @Test
    fun `the wide disclosure links the seven feature pages in order and each is a registry page`() {
        val panel = anchors(section(home, WIDE_PANEL))
        withClue("the disclosure's links, in order") { panel.map { it.href } shouldBe PRODUCT_PATHS }
        panel.size shouldBe PRODUCT_PATHS.size
        PRODUCT_PATHS.size shouldBe SEVEN
        val registry = SitePages.ALL.map { it.path }
        withClue("every Product link is a registry page (the sitemap's source)") { registry shouldContainAll PRODUCT_PATHS }
        panel.forEach { link -> withClue("${link.href} has a label") { link.label.isNotBlank() shouldBe true } }
    }

    @Test
    fun `the phone group renders the same links as the disclosure, from one fragment`() {
        val panel = anchors(section(home, WIDE_PANEL))
        val group = anchors(section(home, PHONE_GROUP))
        withClue("phone group == wide disclosure, path and label, in order") { group shouldBe panel }

        val layout = layoutSource()
        val header = layout.substring(layout.indexOf("<header"), layout.indexOf("</header>"))
        withClue("both menus render the productLinks fragment") { FRAGMENT_USE.findAll(header).count() shouldBe 2 }
        withClue("the fragment is defined once") { FRAGMENT_DEF.findAll(layout).count() shouldBe 1 }
    }

    @Test
    fun `the primary entries follow the disclosure in both menus`() {
        val wide = anchors(section(home, WIDE_ROW)).filterNot { it.href in PRODUCT_PATHS }.map { it.href }
        withClue("the wide row after the disclosure") { wide shouldBe PRIMARY_PATHS + LOGIN }
        val phone = anchors(section(home, PHONE_PANEL)).filterNot { it.href in PRODUCT_PATHS }.map { it.href }
        withClue("the phone menu after the group") { phone shouldBe PRIMARY_PATHS + LOGIN }
        // The disclosure leads the wide row: its summary precedes the first primary link.
        val row = section(home, WIDE_ROW)
        val summaryAt = row.indexOf("<summary")
        val firstPrimaryAt = row.indexOf("href=\"${PRIMARY_PATHS.first()}\"")
        withClue("the disclosure leads the row (summary at $summaryAt, first primary at $firstPrimaryAt)") {
            (summaryAt in 0..<firstPrimaryAt) shouldBe true
        }
    }

    @Test
    fun `on a feature page that link alone is current in both menus, and on the homepage none is`() {
        val current = SitePages.SEMANTIC_LAYER.path
        withClue("wide disclosure marks the current feature page") {
            currentLinks(section(featurePage, WIDE_PANEL)) shouldBe listOf(current)
        }
        withClue("phone group marks the same page") { currentLinks(section(featurePage, PHONE_GROUP)) shouldBe listOf(current) }
        withClue("no other header link is current") { currentLinks(section(featurePage, HEADER)) shouldBe listOf(current, current) }
        withClue("the homepage marks nothing in the header") { currentLinks(section(home, HEADER)).shouldBeEmpty() }
    }

    @Test
    fun `the disclosure is a native details named Product and the phone group is labelled by its heading`() {
        val disclosure = section(home, WIDE_DISCLOSURE)
        disclosure shouldContain "<summary>Product</summary>"
        val group = section(home, PHONE_GROUP)
        val labelledBy = checkNotNull(LABELLED_BY.find(group)?.groupValues?.get(1)) { "the phone group has aria-labelledby" }
        val heading = checkNotNull(HEADING.find(group)) { "the phone group has a heading" }
        withClue("the group is labelled by its own heading") { heading.groupValues[1] shouldBe labelledBy }
        heading.groupValues[2].trim() shouldBe "Product"
        group shouldContain "role=\"group\""
    }

    private data class Link(
        val href: String,
        val label: String,
    )

    /** `(href, label)` for every anchor in [html], in document order. */
    private fun anchors(html: String): List<Link> =
        ANCHOR.findAll(html).map { Link(it.groupValues[1], TAG.replace(it.groupValues[2], "").trim().replace(WS, " ")) }.toList()

    private fun currentLinks(html: String): List<String> = CURRENT.findAll(html).map { it.groupValues[1] }.toList()

    /** The substring of [html] between [bounds]' start marker and end marker (first occurrence). */
    private fun section(
        html: String,
        bounds: Pair<String, String>,
    ): String {
        val from = html.indexOf(bounds.first)
        check(from >= 0) { "render has no ${bounds.first}" }
        val to = html.indexOf(bounds.second, from)
        check(to > from) { "render has no ${bounds.second} after ${bounds.first}" }
        return html.substring(from, to)
    }

    private fun layoutSource(): String =
        checkNotNull(javaClass.classLoader.getResource("templates/site/_layout.html")) { "the site layout is on the classpath" }.readText()

    private companion object {
        /** The seven, in the header's order — 130's list, restored by 148. */
        val PRODUCT_PATHS =
            listOf("/semantic-layer", "/dp-lake", "/federated-query", "/text-to-sql-agent", "/mcp-tools", "/demo-data", "/security")
        const val SEVEN = 7

        /** The primary pages and the docs, in the header's order (145 §2), then the application entry. */
        val PRIMARY_PATHS = listOf("/how-it-works", "/use-cases", "/pricing", "/explore", "/docs")
        const val LOGIN = "/login"

        val HEADER = "<header" to "</header>"
        val WIDE_ROW = "<nav class=\"nav-wide\"" to "</nav>\n    <details class=\"nav-mobile\""
        val WIDE_DISCLOSURE = "<details class=\"nav-menu\"" to "</details>"
        val WIDE_PANEL = "<nav class=\"nav-panel\" aria-label=\"Product\"" to "</nav>"
        val PHONE_PANEL = "<nav class=\"nav-panel\" aria-label=\"Primary mobile\"" to "</nav>"
        val PHONE_GROUP = "<div class=\"nav-group\"" to "</div>"

        val ANCHOR = Regex("""<a[^>]*\shref="([^"]*)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val CURRENT = Regex("""<a[^>]*\shref="([^"]*)"[^>]*\saria-current="page"""")
        val TAG = Regex("<[^>]+>")
        val WS = Regex("\\s+")
        val LABELLED_BY = Regex("""aria-labelledby="([^"]+)"""")
        val HEADING = Regex("""<p class="menu-heading" id="([^"]+)">([^<]*)</p>""")
        val FRAGMENT_USE = Regex("""th:replace="~\{site/_layout :: productLinks}"""")
        val FRAGMENT_DEF = Regex("""th:fragment="productLinks"""")
    }
}
