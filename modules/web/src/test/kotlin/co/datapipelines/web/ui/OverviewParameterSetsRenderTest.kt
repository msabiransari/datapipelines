package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.pipeline.Inventory
import co.datapipelines.pipeline.NavigationFamily
import co.datapipelines.pipeline.SearchNeedle
import co.datapipelines.web.ui.site.REPORT_PROBLEM_URL
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
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
 * #582 — the Parameter Sets overview (`overview/family.html` with `overview/guide-parameter-sets.html`)
 * pinned at the RENDER, beside #556's family-agnostic [OverviewRenderTest]: the counts and bars drill
 * into `/parameter-sets?lifecycle=<wire>` except the discarded bar (the catalog does not offer
 * `discarded`: a discarded-only set has no page); Review drafts is `?lifecycle=with-drafts`; the
 * guide's introduction, starter prompt and related links render, the prompt naming the workspace
 * through `th:text`; a failing parameter-set read shows its reason and no digit; an empty workspace
 * shows zeros, the empty line and the prompt.
 */
class OverviewParameterSetsRenderTest {
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
    fun `the counts and bars filter the parameter-sets catalog and the discarded bar stays unlinked`() {
        val doc = render(page(Inventory(1, 1, 1, 1)))

        doc.select("[data-overview-chart] [data-bar] a").map { it.attr("href") } shouldContainExactly
            listOf(
                "/parameter-sets?lifecycle=draft",
                "/parameter-sets?lifecycle=released",
                "/parameter-sets?lifecycle=released-with-draft",
            )
        doc.selectFirst("[data-bar=discarded] a").shouldBeNull()
        doc.select("[data-overview-inventory] [data-count] a").map { it.attr("href") } shouldContainExactly
            listOf("/parameter-sets?lifecycle=with-drafts", "/parameter-sets?lifecycle=with-releases")
        doc.selectFirst("[data-overview-view-all]")!!.attr("href") shouldBe "/parameter-sets"
        doc.selectFirst("[data-overview-review]")!!.attr("href") shouldBe "/parameter-sets?lifecycle=with-drafts"
        doc.selectFirst("h1")!!.text() shouldBe "Parameter Sets"
    }

    @Test
    fun `the starter prompt names the active workspace through th-text and leaves release to the person`() {
        val html = html(page(Inventory(1, 0, 0, 0)), workspace = "<b>acme</b>")
        val doc = Jsoup.parse(html)

        html shouldNotContain "<b>acme</b>"
        html shouldContain "In workspace &lt;b&gt;acme&lt;/b&gt;, create a draft parameter set for"

        val prompt = doc.selectFirst("textarea#overview-prompt").shouldNotBeNull()
        prompt.children().shouldBeEmpty()
        val text = prompt.`val`()
        text shouldContain "In workspace <b>acme</b>, create a draft parameter set for [use case, such as the dashboard it will drive]."
        // The workflow the tool description and the skill offer: selectors are templates, a new
        // root is agreed first, a MULTI is asked about first, and the draft is checked by evaluating.
        text shouldContain "reuse suitable existing templates for options that come from data"
        text shouldContain "Choose an existing folder with me if the destination is unclear"
        text shouldContain "ask me how any multi-select control should render before you create it"
        text shouldContain "Verify the draft by evaluating it: a first render, representative choices, and empty or invalid ones."
        text shouldContain "Leave release to me."
        doc.select("template[data-page-assets]").single().html() shouldContain "/js/overview.mjs"
        doc.selectFirst("[data-overview-copy]")!!.attr("type") shouldBe "button"
        doc.selectFirst("[data-overview-copy]")!!.attr("aria-controls") shouldBe "overview-prompt"
    }

    @Test
    fun `without a workspace the prompt keeps its bracketed placeholder`() {
        val prompt = render(page(Inventory(0, 0, 0, 0)), workspace = null).selectFirst("textarea#overview-prompt")!!.`val`()

        prompt shouldContain "In workspace [workspace], create a draft parameter set"
    }

    @Test
    fun `the prompt and the guide carry no key, credential, configuration or absolute URL`() {
        val doc = render(page(Inventory(1, 0, 0, 0)))
        val prompt = doc.selectFirst("textarea#overview-prompt")!!.`val`().lowercase()
        val guide = doc.select("[data-overview-intro], [data-overview-prompt], [data-overview-related]").text().lowercase()

        listOf("dpk_", "password", "secret", "token", "://").forEach {
            prompt shouldNotContain it
            guide shouldNotContain it
        }
    }

    @Test
    fun `the introduction and the related families render with the help link`() {
        val doc = render(page(Inventory(1, 0, 0, 0)))

        doc.selectFirst("[data-overview-intro]").shouldNotBeNull().text() shouldContain
            "Parameter sets define reusable inputs and how their values are evaluated together."
        doc.selectFirst("[data-overview-intro]")!!.attr("data-title") shouldBe "What parameter sets are for"
        doc.select("[data-overview-quickstart] ol li").size shouldBe 3
        doc.select("[data-overview-related] a").map { it.attr("href") } shouldContainExactly
            listOf("/overview/templates", "/overview/dashboards", "/docs/mcp-server")
        doc.selectFirst("[data-overview-related] a[href='/docs/mcp-server']")!!.text() shouldBe "MCP server reference"
    }

    @Test
    fun `a role that does not author parameter sets is told to copy the request for a collaborator`() {
        val author = render(page(Inventory(1, 0, 0, 0)), canAuthor = true).selectFirst("#overview-prompt-note")!!.text()
        val viewer = render(page(Inventory(1, 0, 0, 0)), canAuthor = false).selectFirst("#overview-prompt-note")!!.text()

        author shouldContain "Your agent works with the permissions of its own API key."
        author shouldNotContain "collaborator"
        viewer shouldContain "Your role here does not author parameter sets, so copy the request for a collaborator who can."
        viewer shouldNotContain "its own API key"
    }

    @Test
    fun `recently updated links each set's workspace page and marks a draft`() {
        val id = UUID.fromString("00000000-0000-0000-0000-000000000582")
        val doc =
            render(
                page(
                    Inventory(1, 0, 0, 0),
                    RecentResult.Available(listOf(RecentEntry("acme/filters/geo", "/parameter-sets/$id", NOW.minusSeconds(60), true))),
                ),
            )

        val row = doc.selectFirst("[data-overview-recent] li").shouldNotBeNull()
        row.selectFirst("a")!!.attr("href") shouldBe "/parameter-sets/$id"
        row.selectFirst("a")!!.text() shouldBe "acme/filters/geo"
        row.selectFirst("time")!!.attr("datetime") shouldBe NOW.minusSeconds(60).toString()
        row.selectFirst(".ds-badge")!!.text() shouldBe "draft"
    }

    /**
     * The Unavailable state through the production [FamilyInventory] over a [ParameterSetRepository]
     * whose read throws: the reason, no chart, no count and no digit; the same stub answering renders
     * the counts — the pair is the falsification.
     */
    @Test
    fun `a failing parameter-set read shows the reason and no digit, and the same stub answering shows the counts`() {
        val sets = mockk<ParameterSetRepository>()
        val inventory = FamilyInventory(mockk(), mockk(), mockk(), mockk(), sets)
        val workspace = UUID.randomUUID()
        every { sets.inventory(workspace, any()) } throws DataAccessResourceFailureException("injected")

        val failed = inventory.forFamily(NavigationFamily.PARAMETER_SETS, workspace, LensedView.EVERYTHING)
        failed shouldBe InventoryResult.Unavailable("Inventory could not be read.")
        val area = render(page(failed)).selectFirst("[data-overview-inventory]").shouldNotBeNull()
        area.selectFirst("[data-overview-unavailable]").shouldNotBeNull().text() shouldContain "Inventory could not be read."
        area.select("[data-overview-chart], [data-count], [data-overview-table]").shouldBeEmpty()
        Regex("""\d""").containsMatchIn(area.text()) shouldBe false

        every { sets.inventory(workspace, any()) } returns Inventory(2, 1, 0, 0)
        val read = render(page(inventory.forFamily(NavigationFamily.PARAMETER_SETS, workspace, LensedView.EVERYTHING)))
        read.selectFirst("[data-count=objects] dd")!!.text() shouldBe "3"
        read.select("[data-overview-unavailable]").shouldBeEmpty()
    }

    @Test
    fun `an empty workspace shows zeros, the empty line and the starter prompt`() {
        val doc = render(page(Inventory(0, 0, 0, 0)))

        doc.select("[data-overview-inventory] [data-count] dd.app-overview-count-v").map { it.text() } shouldContainExactly
            listOf("0", "0", "0", "0")
        doc.selectFirst("[data-overview-recent-empty]").shouldNotBeNull()
        doc.select("[data-overview-recent] .app-overview-recent li").shouldBeEmpty()
        doc.selectFirst("textarea#overview-prompt").shouldNotBeNull()
    }

    private fun page(
        inventory: Inventory,
        recent: RecentResult? = RecentResult.Available(emptyList()),
    ): OverviewPage = page(InventoryResult.Available(inventory), recent)

    private fun page(
        inventory: InventoryResult,
        recent: RecentResult? = RecentResult.Available(emptyList()),
    ): OverviewPage = OverviewPage.of(OverviewPage.Family.PARAMETER_SETS, false, inventory, recent, NOW)

    private fun render(
        page: OverviewPage,
        workspace: String? = "acme",
        canAuthor: Boolean = true,
    ): Document = Jsoup.parse(html(page, workspace, canAuthor))

    private fun html(
        page: OverviewPage,
        workspace: String? = "acme",
        canAuthor: Boolean = true,
    ): String {
        val path = "/overview/${page.family}"
        val context =
            WebContext(
                JakartaServletWebApplication
                    .buildApplication(MockServletContext())
                    .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
            ).withRoles(canAuthor = canAuthor)
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
        val NOW: Instant = Instant.parse("2026-10-18T05:00:00Z")
    }
}
