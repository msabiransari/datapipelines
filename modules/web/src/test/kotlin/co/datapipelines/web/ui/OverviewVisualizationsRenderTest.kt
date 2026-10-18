package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.pipeline.Inventory
import co.datapipelines.pipeline.NavigationFamily
import co.datapipelines.pipeline.SearchNeedle
import co.datapipelines.visualization.VisualizationRepository
import co.datapipelines.web.ui.site.REPORT_PROBLEM_URL
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import org.jsoup.Jsoup
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

/** #581 — family-specific guide, links and real failure boundary, rendered through the shared template. */
class OverviewVisualizationsRenderTest {
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
    fun `the guide escapes the workspace keeps placeholders and links the related families`() {
        val rendered = html(page(InventoryResult.Available(Inventory(1, 1, 1, 1))), "<b>acme</b>")
        val doc = Jsoup.parse(rendered)
        rendered shouldNotContain "<b>acme</b>"
        val prompt = requireNotNull(doc.selectFirst("#overview-prompt")).`val`()
        prompt shouldContain "In workspace <b>acme</b>, create a draft visualization for [question]"
        prompt shouldContain "[chart, table or KPI]"
        prompt shouldContain "[fields and their meaning]"
        prompt shouldContain "normal, empty and boundary test cases"
        prompt shouldContain "Leave release to me."
        doc.select("[data-overview-related] a").map { it.attr("href") } shouldBe
            listOf("/overview/pipelines", "/overview/dashboards", "/docs/mcp-server")
        doc.select("[data-bar] a").map { it.attr("href") } shouldBe
            listOf("/visualizations?lifecycle=draft", "/visualizations?lifecycle=released", "/visualizations?lifecycle=released-with-draft")
        doc.select("[data-bar=discarded] a").isEmpty() shouldBe true
        requireNotNull(doc.selectFirst("[data-overview-review]")).attr("href") shouldBe "/visualizations?lifecycle=with-drafts"
        doc.select("[data-overview-prompt] ol li").size shouldBe 3
        doc.select("[data-overview-intro]").text() shouldContain "each borough's rideshare share"
        doc.select("[data-overview-prompt]").text() shouldContain "Preview (test fixtures)"
    }

    @Test
    fun `an empty workspace shows zeros the empty line and the prompt`() {
        val doc = Jsoup.parse(html(page(InventoryResult.Available(Inventory(0, 0, 0, 0)))))
        doc.select("[data-count] dd.app-overview-count-v").map { it.text() } shouldBe listOf("0", "0", "0", "0")
        doc.select("[data-overview-chart] rect").map { it.attr("width") } shouldBe listOf("0", "0", "0", "0")
        doc.select("[data-overview-recent-empty]").isEmpty() shouldBe false
        doc.select("#overview-prompt").isEmpty() shouldBe false
    }

    @Test
    fun `a throwing visualization repository renders unavailable without numbers or chart`() {
        val repository = mockk<VisualizationRepository>()
        every { repository.inventory(any(), any()) } throws IllegalStateException("Injected read failure")
        val inventory =
            FamilyInventory(mockk(), mockk(), mockk(), repository, mockk())
                .forFamily(NavigationFamily.VISUALIZATIONS, UUID.randomUUID(), LensedView.EVERYTHING)
        inventory shouldBe InventoryResult.Unavailable("Inventory could not be read.")
        val doc = Jsoup.parse(html(page(inventory)))
        val area = requireNotNull(doc.selectFirst("[data-overview-inventory]"))
        area.text() shouldContain "The inventory is unavailable."
        Regex("""\d""").containsMatchIn(area.text()) shouldBe false
        area.text() shouldNotContain "0"
        area.select("[data-count], [data-overview-chart], [data-overview-table]").isEmpty() shouldBe true
    }

    @Test
    fun `filtered empty state and pager preserve search and lifecycle independently`() {
        val variables =
            mapOf(
                "lensUnavailable" to null,
                "visualizations" to emptyList<Any>(),
                "rootId" to VisualizationBrowseModel.PAGE_ROOT_ID,
                "q" to "cost",
                "lifecycle" to "with-drafts",
                "lifecycleLabel" to "With drafts",
                "offset" to 25,
                "hasMore" to true,
                "total" to 51,
            )
        val model = page(InventoryResult.Available(Inventory(0, 0, 0, 0)))
        val empty = Jsoup.parse(html(model, template = "partials/visualization-search", variables = variables))
        empty.text() shouldContain "No visualizations match: With drafts, with your search"
        requireNotNull(empty.selectFirst("button:contains(Clear search)")).attr("hx-include") shouldBe "#visualization-filter-lifecycle"
        requireNotNull(empty.selectFirst("button:contains(Clear filters)")).attr("data-dp-clear-filters") shouldBe
            "#visualization-filter-q, #visualization-filter-lifecycle"
        val row = VisualizationBrowseModel.LeafView(UUID.randomUUID(), "acme/cost", "cost", "Cost", 1, released = false)
        val listed =
            Jsoup.parse(
                html(model, template = "partials/visualization-search", variables = variables + ("visualizations" to List(25) { row })),
            )
        listed.select("[hx-get]").map { it.attr("hx-get") } shouldBe
            listOf(
                "/partials/visualizations?q=cost&lifecycle=with-drafts&offset=0",
                "/partials/visualizations?q=cost&lifecycle=with-drafts&offset=50",
            )
    }

    @Test
    fun `a reader who cannot author gets collaborator guidance and can still copy`() {
        val doc = Jsoup.parse(html(page(InventoryResult.Available(Inventory(0, 0, 0, 0))), variables = mapOf("canAuthor" to false)))
        doc.select("#overview-prompt-note").text() shouldContain "does not author visualizations"
        doc.select("#overview-prompt-note").text() shouldContain "a collaborator who can"
        doc.select("[data-overview-copy]").isEmpty() shouldBe false
    }

    private fun page(inventory: InventoryResult) =
        OverviewPage.of(
            OverviewPage.Family.VISUALIZATIONS,
            false,
            inventory,
            RecentResult.Available(emptyList()),
            Instant.parse("2026-10-17T12:00:00Z"),
        )

    private fun html(
        page: OverviewPage,
        workspace: String = "acme",
        template: String = OverviewController.VIEW,
        variables: Map<String, Any?> = emptyMap(),
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
        variables.forEach { (name, value) -> context.setVariable(name, value) }
        return engine.process(template, context)
    }
}
