package co.datapipelines.web.ui

import co.datapipelines.pipeline.SearchNeedle
import co.datapipelines.web.ui.OverviewPage.Bar
import co.datapipelines.web.ui.OverviewPage.Chart
import co.datapipelines.web.ui.OverviewPage.Count
import co.datapipelines.web.ui.OverviewPage.InventoryPanel
import co.datapipelines.web.ui.OverviewPage.RecentItem
import co.datapipelines.web.ui.OverviewPage.RecentPanel
import co.datapipelines.web.ui.OverviewPage.RecentSection
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

/**
 * #583 — the Datasources overview at the RENDER (`overview/family.html` + `overview/guide-datasources.html`):
 * the guide's register-here-then-explore steps, starter prompt and related families; the health and
 * by-dialect charts over the visible set (dialect bars filter the catalog, health bars do not); the
 * Continue your work grants list; and the framework's Unavailable and empty states with no digit and
 * no draft review — the frame the five lifecycle families render.
 */
class OverviewDatasourcesRenderTest {
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
    fun `the guide renders the introduction, the steps, the starter prompt and the related families`() {
        val html = html(page(), workspace = "<b>acme</b>")
        val doc = Jsoup.parse(html)

        doc.selectFirst("h1")!!.text() shouldBe "Datasources"
        doc.selectFirst("[data-overview-intro]")!!.text() shouldContain
            "Datasources are the registered database connections everything else reads through"
        doc.select("[data-overview-quickstart] .app-overview-steps li").size shouldBe 3
        // Escaped on the wire: a workspace name is text, never markup (th:text, not th:utext).
        html shouldNotContain "<b>acme</b>"
        val prompt = doc.selectFirst("textarea#overview-prompt").shouldNotBeNull()
        prompt.`val`() shouldContain "In workspace <b>acme</b>, explore the datasource [name]"
        prompt.`val`() shouldContain "test its connection"
        prompt.`val`() shouldContain "one bounded read-only SQL probe"
        prompt.`val`() shouldContain "Do not register or change anything"
        prompt.children().shouldBeEmpty()
        doc.selectFirst("[data-overview-copy]")!!.attr("type") shouldBe "button"
        doc.select("template[data-page-assets]").single().html() shouldContain "/js/overview.mjs"
        doc.select("[data-overview-related] a").map { it.attr("href") } shouldContainExactly
            listOf("/overview/pipelines", "/overview/templates", "/overview/dashboards", "/docs/mcp-server")
        listOf("dpk_", "password", "secret", "token", "://").forEach { prompt.`val`().lowercase() shouldNotContain it }
    }

    @Test
    fun `the note names the agent's key for an author and a collaborator for everyone else`() {
        Jsoup.parse(html(page())).selectFirst("#overview-prompt-note")!!.text() shouldContain
            "Your agent works with the permissions of its own API key."
        val viewer = Jsoup.parse(html(page(), canAuthor = false)).selectFirst("#overview-prompt-note")!!.text()
        viewer shouldContain "Your role here does not read row data"
        viewer shouldNotContain "its own API key"
    }

    @Test
    fun `the dialect bars filter the catalog, the health bars do not, and widths are shares of the tallest`() {
        val doc = render(page())

        doc.selectFirst("[data-overview-inventory] [data-count] dd.app-overview-count-v")!!.text() shouldBe "4"
        doc.select("[data-bar=ok] a, [data-bar=failed] a, [data-bar=never] a").shouldBeEmpty()
        doc.select("[data-overview-chart][data-chart=dialect] [data-bar] a").map { it.attr("href") } shouldContainExactly
            listOf("/datasources?dialect=POSTGRES", "/datasources?dialect=H2")
        doc.select("[data-overview-chart][data-chart=dialect] rect").map { it.attr("width") } shouldContainExactly
            listOf("100.0", "33.3")
        doc.select("[data-overview-inventory] [data-overview-note]").joinToString(" ") { it.text() } shouldContain
            "Each datasource carries its latest recorded test"
        doc.select("[data-chart-table=dialect] tbody td").map { it.text() } shouldContainExactly listOf("3", "1")
        doc.selectFirst("[data-overview-review]").shouldBeNull()
    }

    @Test
    fun `the recent section links the datasources an empty list says so`() {
        val withEntries = render(page(recent = RecentPanel(review = null, sections = listOf(section(entries())))))
        withEntries.select("[data-overview-recent] .app-overview-recent li a").map { it.attr("href") } shouldContainExactly
            listOf("/datasources/dw-sales", "/datasources/dw-usage")
        withEntries.select("[data-overview-recent] .ds-badge").shouldBeEmpty()

        val empty = render(page(recent = RecentPanel(review = null, sections = listOf(section(emptyList())))))
        empty.selectFirst("[data-overview-recent-empty]").shouldNotBeNull().text() shouldContain
            "No datasources have been added to this workspace yet"
    }

    /** An unavailable section names its read, and an unavailable inventory leaves no digit in the area. */
    @Test
    fun `a failed read is unavailable with no digit and the prompt still answers`() {
        val down =
            render(
                OverviewPage(
                    family = "datasources",
                    title = "Datasources",
                    catalogHref = "/datasources",
                    lensed = false,
                    inventory = null,
                    unavailable = "Inventory could not be read.",
                    recent =
                        RecentPanel(
                            review = null,
                            sections = listOf(section(entries = null, unavailable = "Recently added could not be read.")),
                        ),
                    guided = true,
                ),
            )
        val area = down.selectFirst("[data-overview-inventory]").shouldNotBeNull()
        area.selectFirst("[data-overview-unavailable]").shouldNotBeNull().text() shouldContain "Inventory could not be read."
        area.select("[data-overview-chart], [data-count], [data-overview-table]").shouldBeEmpty()
        Regex("""\d""").containsMatchIn(area.text()) shouldBe false
        down.selectFirst("[data-overview-recent]")!!.text() shouldContain "Recently added could not be read."
        down.selectFirst("textarea#overview-prompt").shouldNotBeNull()
    }

    /** An empty workspace: the zero tile, zero-width bars, a bar-less dialect chart, the empty recent line. */
    @Test
    fun `an empty workspace shows zeros and the empty recent line`() {
        val doc =
            render(
                page(
                    inventory =
                        InventoryPanel(
                            counts = listOf(Count("datasources", "Datasources", 0, "Granted to this workspace.", null)),
                            charts =
                                listOf(
                                    Chart(
                                        "health",
                                        "Last connection test",
                                        listOf(bar("ok", 0), bar("failed", 0), bar("never", 0)),
                                        HEALTH_NOTE,
                                    ),
                                    Chart("dialect", "By dialect", emptyList(), null, listOf("Dialect", "Datasources")),
                                ),
                        ),
                    recent = RecentPanel(review = null, sections = listOf(section(emptyList()))),
                ),
            )

        doc.select("[data-overview-inventory] [data-count] dd.app-overview-count-v").map { it.text() } shouldContainExactly listOf("0")
        doc.select("[data-overview-chart] rect").map { it.attr("width") } shouldContainExactly listOf("0", "0", "0")
        doc.selectFirst("[data-overview-recent-empty]").shouldNotBeNull()
        doc.selectFirst("textarea#overview-prompt").shouldNotBeNull()
    }

    private fun page(
        inventory: InventoryPanel = answerInventory,
        recent: RecentPanel = answerRecent,
    ): OverviewPage =
        OverviewPage(
            family = "datasources",
            title = "Datasources",
            catalogHref = "/datasources",
            lensed = false,
            inventory = inventory,
            unavailable = null,
            recent = recent,
            guided = true,
        )

    private fun entries(): List<RecentItem> =
        listOf(
            RecentItem("dw-sales", "/datasources/dw-sales", AT.toString(), "an hour ago", "18 Oct 2026, 05:00", false),
            RecentItem("dw-usage", "/datasources/dw-usage", AT.minusSeconds(3600).toString(), "2 hours ago", "18 Oct 2026, 04:00", false),
        )

    private fun section(
        entries: List<RecentItem>?,
        unavailable: String? = null,
    ): RecentSection =
        RecentSection(
            "Recently added to this workspace",
            "Newest first.",
            entries,
            unavailable,
            "No datasources have been added to this workspace yet.",
        )

    private fun bar(
        key: String,
        value: Long,
    ): Bar = Bar(key, key, value, OverviewPage.width(value, if (value == 0L) 0 else 4), "released", "$key: $value", null)

    private fun render(page: OverviewPage): Document = Jsoup.parse(html(page))

    private fun html(
        page: OverviewPage,
        workspace: String = "acme",
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

    /** The answering page the chart assertions read: POSTGRES 3 (2 ok, 1 never) and H2 1 (1 failed). */
    private val answerInventory =
        InventoryPanel(
            counts = listOf(Count("datasources", "Datasources", 4, "Granted to this workspace.", null)),
            charts =
                listOf(
                    Chart(
                        "health",
                        "Last connection test",
                        listOf(bar("ok", 3), bar("failed", 1), bar("never", 1)),
                        HEALTH_NOTE,
                    ),
                    Chart(
                        "dialect",
                        "By dialect",
                        listOf(
                            Bar(
                                "POSTGRES",
                                "POSTGRES",
                                3,
                                OverviewPage.width(3, 3),
                                "mixed",
                                "POSTGRES: 3",
                                "/datasources?dialect=POSTGRES",
                            ),
                            Bar("H2", "H2", 1, OverviewPage.width(1, 3), "mixed", "H2: 1", "/datasources?dialect=H2"),
                        ),
                        null,
                        listOf("Dialect", "Datasources"),
                    ),
                ),
        )
    private val answerRecent = RecentPanel(review = null, sections = listOf(section(entries())))

    private companion object {
        val AT: Instant = Instant.parse("2026-10-18T05:00:00Z")
        const val HEALTH_NOTE = "Each datasource carries its latest recorded test — one per datasource, whoever ran it."
    }
}
