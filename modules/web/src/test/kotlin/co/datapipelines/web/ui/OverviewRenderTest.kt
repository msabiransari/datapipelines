package co.datapipelines.web.ui

import co.datapipelines.pipeline.Inventory
import co.datapipelines.pipeline.SearchNeedle
import co.datapipelines.web.ui.site.REPORT_PROBLEM_URL
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication
import java.time.Instant
import java.util.UUID

/**
 * #556 — the family overview (`overview/family.html`) pinned at the RENDER: every number on the
 * page comes from the one [OverviewPage] the controller builds, so the four bars, the four counts
 * and the hidden table read the same [Inventory]; an unknown inventory shows its reason and no
 * number; the Pipelines guide substitutes the workspace through `th:text`; a family without a
 * guide renders the framework and nothing else (amendment 1: no placeholder prose).
 */
class OverviewRenderTest {
    private val engine =
        SpringTemplateEngine().apply {
            setTemplateResolver(
                ClassLoaderTemplateResolver().apply {
                    prefix = "templates/"
                    suffix = ".html"
                    characterEncoding = "UTF-8"
                },
            )
        }

    @Test
    fun `one inventory feeds the bars, the counts and the table`() {
        val doc = render(page(Inventory(1, 1, 1, 1)))

        val bars = doc.select("[data-overview-chart] [data-bar]")
        bars.map { it.attr("data-bar") } shouldContainExactly listOf("draft", "released", "released-with-draft", "discarded")
        bars.map { it.selectFirst(".app-overview-bar-v")!!.text() } shouldContainExactly listOf("1", "1", "1", "1")
        bars.map { it.selectFirst("rect")!!.attr("width") } shouldContainExactly listOf("100.0", "100.0", "100.0", "100.0")
        bars.map { it.attr("data-tone") } shouldContainExactly listOf("draft", "released", "mixed", "discarded")
        bars[0].selectFirst("svg")!!.attr("aria-label") shouldBe "Draft only: 1 pipeline"

        val counts =
            doc
                .select(
                    "[data-overview-inventory] [data-count]",
                ).associate { it.attr("data-count") to it.selectFirst("dd")!!.text() }
        counts shouldBe mapOf("objects" to "4", "with-drafts" to "2", "with-releases" to "2", "discarded" to "1")

        doc.select("[data-overview-table] tbody td").map { it.text() } shouldContainExactly listOf("1", "1", "1", "1")
        doc.select("[data-overview-unavailable]").shouldBeEmpty()
    }

    @Test
    fun `bar widths are shares of the tallest bar and a zero is a zero-width rect beside its 0`() {
        val doc = render(page(Inventory(draftOnly = 4, releasedOnly = 2, releasedWithDraft = 0, discardedOnly = 1)))

        val bars = doc.select("[data-overview-chart] [data-bar]")
        bars.map { it.selectFirst("rect")!!.attr("width") } shouldContainExactly listOf("100.0", "50.0", "0.0", "25.0")
        bars[2].selectFirst(".app-overview-bar-v")!!.text() shouldBe "0"
        // The SVG width is an attribute: the CSP admits no style attribute (InlineWidthAuditTest).
        bars.forEach { it.select("[style]").shouldBeEmpty() }
    }

    @Test
    fun `an empty family draws an all-zero chart, not a divide by zero`() {
        val doc = render(page(Inventory(0, 0, 0, 0)))

        doc.select("[data-overview-chart] rect").map { it.attr("width") } shouldContainExactly listOf("0", "0", "0", "0")
        doc.select("[data-overview-inventory] [data-count] dd.app-overview-count-v").map { it.text() } shouldContainExactly
            listOf("0", "0", "0", "0")
    }

    @Test
    fun `an unknown inventory says why and shows no number in the inventory area`() {
        val doc = render(page(InventoryResult.Unavailable("Inventory could not be read.")))

        val area = doc.selectFirst("[data-overview-inventory]").shouldNotBeNull()
        area.selectFirst("[data-overview-unavailable]").shouldNotBeNull().text() shouldContain "Inventory could not be read."
        area.select("[data-overview-chart], [data-count], [data-overview-table]").shouldBeEmpty()
        area.text() shouldNotContain "0"
        Regex("""\d""").containsMatchIn(area.text()) shouldBe false
    }

    @Test
    fun `the pipelines links filter the catalog and the discarded bar stays unlinked`() {
        val doc = render(page(Inventory(1, 1, 1, 1)))

        doc.select("[data-overview-chart] [data-bar] a").map { it.attr("href") } shouldContainExactly
            listOf("/pipelines?lifecycle=draft", "/pipelines?lifecycle=released", "/pipelines?lifecycle=released-with-draft")
        doc.selectFirst("[data-bar=discarded] a").shouldBeNull()
        doc.select("[data-overview-inventory] [data-count] a").map { it.attr("href") } shouldContainExactly
            listOf("/pipelines?lifecycle=with-drafts", "/pipelines?lifecycle=with-releases")
        doc.selectFirst("[data-overview-view-all]")!!.attr("href") shouldBe "/pipelines"
        doc.selectFirst("[data-overview-review]")!!.attr("href") shouldBe "/pipelines?lifecycle=with-drafts"
    }

    @Test
    fun `the starter prompt names the active workspace through th-text`() {
        val html = html(page(Inventory(1, 0, 0, 0)), workspace = "<b>acme</b>")
        val doc = Jsoup.parse(html)

        // Escaped on the wire: a workspace name is text, never markup (th:text, not th:utext).
        html shouldNotContain "<b>acme</b>"
        html shouldContain "In workspace &lt;b&gt;acme&lt;/b&gt;, build a draft pipeline"

        val prompt = doc.selectFirst("textarea#overview-prompt").shouldNotBeNull()
        prompt.`val`() shouldContain "In workspace <b>acme</b>, build a draft pipeline to answer: [question]."
        prompt.`val`() shouldContain "Leave release to me."
        prompt.children().shouldBeEmpty()
        doc.selectFirst("[data-overview-context] b")!!.text() shouldBe "<b>acme</b>"
        doc.select("template[data-page-assets]").single().html() shouldContain "/js/overview.mjs"
        doc.selectFirst("[data-overview-copy]")!!.attr("type") shouldBe "button"
    }

    @Test
    fun `the prompt carries no key, credential or configuration`() {
        val text = render(page(Inventory(1, 0, 0, 0))).selectFirst("textarea#overview-prompt")!!.`val`()

        listOf("dpk_", "password", "secret", "token", "://").forEach { text.lowercase() shouldNotContain it }
    }

    @Test
    fun `a family without a guide renders the framework and no placeholder prose`() {
        OverviewPage.Family.entries.filter { !it.guided }.forEach { family ->
            val doc = render(OverviewPage.of(family, false, InventoryResult.Available(Inventory(1, 2, 3, 4)), null, NOW))

            doc.selectFirst("h1")!!.text() shouldBe family.title
            doc.selectFirst("[data-overview-view-all]")!!.attr("href") shouldBe family.catalogHref
            doc.select("[data-overview-chart] [data-bar]").size shouldBe 4
            doc.select("[data-overview-chart] a, [data-overview-inventory] [data-count] a").shouldBeEmpty()
            doc.select("[data-overview-intro], [data-overview-prompt], [data-overview-recent], [data-overview-related]").shouldBeEmpty()
            doc.select("template[data-page-assets]").shouldBeEmpty()
        }
    }

    @Test
    fun `recently updated lists the rows, says when there are none, and says when it is unknown`() {
        val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val listed =
            render(
                page(
                    Inventory(1, 0, 0, 0),
                    RecentResult.Available(listOf(RecentEntry("acme/sales/daily", "/pipelines/$id", NOW.minusSeconds(3600), true))),
                ),
            )
        val row = listed.selectFirst("[data-overview-recent] li").shouldNotBeNull()
        row.selectFirst("a")!!.attr("href") shouldBe "/pipelines/$id"
        row.selectFirst("a")!!.text() shouldBe "acme/sales/daily"
        row.selectFirst("time")!!.attr("datetime") shouldBe NOW.minusSeconds(3600).toString()
        row.selectFirst(".ds-badge")!!.text() shouldBe "draft"

        val empty = render(page(Inventory(0, 0, 0, 0), RecentResult.Available(emptyList())))
        empty.selectFirst("[data-overview-recent-empty]").shouldNotBeNull()
        empty.select("[data-overview-recent] li").shouldBeEmpty()

        val unknown = render(page(Inventory(1, 0, 0, 0), RecentResult.Unavailable("Recently updated could not be read.")))
        unknown.selectFirst("[data-overview-recent]")!!.text() shouldContain "Recently updated could not be read."
        unknown.selectFirst("[data-overview-recent-empty]").shouldBeNull()
    }

    @Test
    fun `a lensed caller reads admitted inventory`() {
        val doc = render(OverviewPage.of(OverviewPage.Family.PIPELINES, true, InventoryResult.Available(Inventory(1, 0, 0, 0)), null, NOW))

        doc.selectFirst("#overview-inventory-h")!!.text() shouldBe "Admitted inventory"
        doc.selectFirst("[data-overview-context]")!!.text() shouldContain "only what your promotion view shows"
    }

    private fun page(
        inventory: Inventory,
        recent: RecentResult? = RecentResult.Available(emptyList()),
    ): OverviewPage = page(InventoryResult.Available(inventory), recent)

    private fun page(
        inventory: InventoryResult,
        recent: RecentResult? = RecentResult.Available(emptyList()),
    ): OverviewPage = OverviewPage.of(OverviewPage.Family.PIPELINES, false, inventory, recent, NOW)

    private fun render(page: OverviewPage): Document = Jsoup.parse(html(page))

    private fun html(
        page: OverviewPage,
        workspace: String = "acme",
    ): String {
        val path = "/overview/${page.family}"
        val context =
            WebContext(
                JakartaServletWebApplication
                    .buildApplication(MockServletContext())
                    .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
            ).withRoles()
        context.setVariable("_csrf", mapOf("token" to "t", "parameterName" to "_csrf"))
        context.setVariable("workspaceHeaderFragment", "")
        context.setVariable("workspaceOptions", emptyList<Any>())
        context.setVariable("activeWorkspace", workspace)
        context.setVariable("activeTheme", "saas")
        context.setVariable("authenticated", true)
        context.setVariable("currentPath", path)
        context.setVariable("crumbGroup", AppNav.crumbFor(path)?.first)
        context.setVariable("crumbPage", AppNav.crumbFor(path)?.second)
        context.setVariable("searchMaxLength", SearchNeedle.MAX_LENGTH)
        context.setVariable("reportProblemUrl", REPORT_PROBLEM_URL)
        context.setVariable(OverviewController.MODEL, page)
        return engine.process(OverviewController.VIEW, context)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-17T12:00:00Z")
    }
}
