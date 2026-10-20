package co.datapipelines.web.ui

import co.datapipelines.pipeline.Activity
import co.datapipelines.pipeline.ChartInventory
import co.datapipelines.pipeline.Inventory
import co.datapipelines.pipeline.MaturityBin
import co.datapipelines.pipeline.ReleaseBin
import co.datapipelines.pipeline.SearchNeedle
import co.datapipelines.web.ui.site.REPORT_PROBLEM_URL
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication
import java.time.Instant

/**
 * #599 — what the family homes' render and E2E tests share now that all five are lifecycle homes:
 * the production template rendered with the controller's model ([html]), a chart group consistent
 * with an [Inventory] ([chartsOf]), and the frame's reading of one family's guide words
 * ([assertGuideWords]) against the words each family's test states for itself.
 */
internal object OverviewRenderSupport {
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

    /** `overview/family.html` for [page], the way [OverviewController] models it, for a reader who may author or not. */
    fun html(
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

    /** A chart group consistent with [inventory]: never released objects in bin 0, every other in bin 1. */
    fun chartsOf(
        inventory: Inventory,
        activity: Activity,
    ): ChartInventory =
        ChartInventory(
            inventory,
            ReleaseBin.entries.map { releases ->
                when (releases) {
                    ReleaseBin.ZERO -> MaturityBin(releases, inventory.draftOnly, inventory.discardedOnly)
                    ReleaseBin.ONE -> MaturityBin(releases, inventory.releasedWithDraft, inventory.releasedOnly)
                    else -> MaturityBin(releases, 0, 0)
                }
            },
            activity,
        )

    /** One family's guide words as its page must show them; [prompt] is the textarea's whole text, byte for byte. */
    data class GuideWords(
        val subtitle: String,
        val headline: String,
        val sentence: String,
        val steps: List<Pair<String, String>>,
        val prompt: String,
        val howItWorksTitle: String,
        val howItWorks: String,
        val related: List<String>,
    )

    /**
     * The frame's regions read [words] for [family]: the subtitle under the title, the quick start's
     * headline, sentence and three steps, the starter prompt (equal to the string, not containing it),
     * How it works' title and opening paragraph, Build on your results' links, the task rows and the
     * strip's notes in the family's plural.
     */
    fun assertGuideWords(
        doc: Document,
        family: OverviewPage.Family,
        words: GuideWords,
    ) {
        withClue("$family's guide words") {
            doc.selectFirst(".app-overview-subtitle").shouldNotBeNull().text() shouldBe words.subtitle
            val quickstart = doc.selectFirst("[data-overview-quickstart]").shouldNotBeNull()
            quickstart.selectFirst("#overview-quickstart-h").shouldNotBeNull().text() shouldBe words.headline
            quickstart.selectFirst(".app-overview-panel-sub").shouldNotBeNull().text() shouldBe words.sentence
            quickstart.select(".app-overview-steps-row li").map {
                it.selectFirst("b")!!.text() to it.selectFirst("small")!!.text()
            } shouldContainExactly
                words.steps
            doc.selectFirst("textarea#overview-prompt").shouldNotBeNull().`val`() shouldBe words.prompt
            val howItWorks = doc.selectFirst("[data-dialog-section=howitworks]").shouldNotBeNull()
            howItWorks.attr("data-title") shouldBe words.howItWorksTitle
            howItWorks.selectFirst("p").shouldNotBeNull().text() shouldBe words.howItWorks
            doc.selectFirst("[data-overview-related] .app-overview-resources-k").shouldNotBeNull().text() shouldBe "Build on your results"
            doc.select("[data-overview-related] a").map { it.attr("href") } shouldContainExactly words.related + "/docs/mcp-server"
            doc.select("[data-task] b").map { it.text() } shouldContainExactly listOf("Review your drafts", "Explore live ${family.plural}")
            doc.selectFirst("dl.app-overview-counts dt").shouldNotBeNull().text() shouldBe "Total ${family.plural}"
            doc.select("dl.app-overview-counts .app-overview-count-help").map { it.text() }.take(STRIP_NOTES.size) shouldContainExactly
                STRIP_NOTES
        }
    }

    /**
     * The summary strip's four exclusive groups (Live and In development in the strip, Live with
     * changes and Retired in its foot) sum to its Total, and that Total is [objects]: the page's
     * numbers are the read's, not a re-derivation that agrees with itself.
     */
    fun assertStripSumsTo(
        doc: Document,
        objects: Long,
    ) {
        val cells = doc.select("[data-overview-inventory] [data-count]").associate { it.attr("data-count") to value(it) }
        withClue("the strip's cells $cells") {
            LIFECYCLE_GROUPS.sumOf { cells.getValue(it) } shouldBe cells.getValue("total")
            cells.getValue("total") shouldBe objects
        }
    }

    private fun value(cell: Element): Long =
        cell
            .selectFirst(".app-overview-count-v")
            .shouldNotBeNull()
            .text()
            .toLong()

    private val LIFECYCLE_GROUPS = listOf("live", "in-development", "live-changes", "retired")

    /** The strip's first three notes — the frame's words, the same for every family. */
    private val STRIP_NOTES = listOf("Across this workspace", "Released, with no open changes", "Awaiting their first release")

    /** The actively developed window the E2E reads use: `datapipelines.ui.actively-developed-days`' default ([UiProperties]). */
    const val ACTIVE_DAYS = UiProperties.DEFAULT_ACTIVELY_DEVELOPED_DAYS

    /** The fixed clock of the render tests. */
    val NOW: Instant = Instant.parse("2026-10-18T05:00:00Z")
}
