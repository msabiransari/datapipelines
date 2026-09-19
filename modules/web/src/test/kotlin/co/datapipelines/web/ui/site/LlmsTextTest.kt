package co.datapipelines.web.ui.site

import co.datapipelines.web.ui.DocsCatalog
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * `/llms.txt` and `/llms-full.txt` (173 §C.1), held to the sitemap.
 *
 * The one claim that matters: the two files derive from the SAME registries as
 * `/sitemap.xml`, so they cannot list a page the sitemap does not, nor miss one it does —
 * every marketing `<loc>` is a link in the index, every docs slug is linked in the index
 * (at its `.md` twin) and carried in the full file. Then the shape llmstxt.org asks for
 * (H1, blockquote, `- [title](url): description` lines), the summary parity with the
 * homepage's constant, and the wire (media type, cache window).
 */
class LlmsTextTest {
    private val docs: DocsCatalog = SitePageRenderer.docs
    private val index = LlmsText.index(docs)
    private val full = LlmsText.full(docs)
    private val slugs = docs.index().flatMap { group -> group.docs.map { it.slug } }

    private val mvc = MockMvcBuilders.standaloneSetup(LlmsTxtController(docs)).build()

    @Test
    fun `every sitemap location is in the index - marketing pages as pages, docs as their md twins`() {
        val locations = SitemapController(docs).locations()
        locations.size shouldBeGreaterThanOrEqual MIN_LOCATIONS
        val linked = LINK.findAll(index).map { it.groupValues[2] }.toSet()
        val missing =
            locations.mapNotNull { loc ->
                val expected = if (loc.startsWith("$SITE_ORIGIN/docs/")) "$loc.md" else loc
                if (expected in linked) null else "$loc (expected $expected)"
            }
        missing.shouldBeEmpty()
        // And nothing beyond the sitemap: a link the sitemap does not know is a link to a page
        // that is not public.
        val known = locations.map { if (it.startsWith("$SITE_ORIGIN/docs/")) "$it.md" else it }.toSet()
        (linked - known).shouldBeEmpty()
    }

    @Test
    fun `every packaged doc is in the full file under its own title, in catalog order`() {
        val titles = docs.index().flatMap { group -> group.docs.map { it.title } }
        val positions = titles.map { full.indexOf("\n# $it\n") }
        positions.filter { it < 0 }.shouldBeEmpty()
        positions shouldBe positions.sorted()
        slugs.forEach { slug -> full shouldContain "Source: $SITE_ORIGIN/docs/$slug.md" }
        // The separator IS the doc's H1: the doc's own leading `# Title` line was folded into
        // it, so the title heads its section exactly once.
        titles
            .filter { title -> Regex("^# ${Regex.escape(title)}$", RegexOption.MULTILINE).findAll(full).count() != 1 }
            .shouldBeEmpty()
    }

    @Test
    fun `both files open with the llmstxt shape and the homepage's own summary`() {
        listOf(index, full).forEach { text ->
            text shouldStartWith "# ${LlmsText.SITE_NAME}\n\n> $SITE_SUMMARY\n\n"
        }
        // Every link line is `- [title](absolute url): description` — the description is
        // what an agent reads before it fetches.
        val lines = index.lines().filter { it.startsWith("- ") }
        lines.size shouldBeGreaterThanOrEqual MIN_LOCATIONS
        lines.filterNot { LINK_LINE.matches(it) }.shouldBeEmpty()
        // Every registry page has a description (SiteSeoMetaTest holds them non-blank); a
        // packaged doc with no prose paragraph is the one legitimate bare line.
        lines.filter { !it.contains("): ") && !it.contains("$SITE_ORIGIN/docs/") }.shouldBeEmpty()
        // The four groups are /explore's.
        SiteExplore.groups(docs).forEach { group -> index shouldContain "## ${group.name}\n" }
    }

    @Test
    fun `the routes serve markdown with the public cache window`() {
        listOf("/llms.txt" to index, "/llms-full.txt" to full).forEach { (path, expected) ->
            mvc
                .perform(get(path))
                .andExpect(status().isOk)
                .andExpect(content().contentType(LlmsTxtController.MARKDOWN))
                .andExpect(header().string("Cache-Control", containsString("public")))
                .andExpect(content().string(expected))
        }
        // The controller's cache header is the pages' window, from the same constant.
        val response = MockHttpServletResponse()
        LlmsTxtController(docs).index(response)
        response.getHeader("Cache-Control") shouldContain "max-age=${PublicPage.PAGE_MAX_AGE_MINUTES * SECONDS_PER_MINUTE}"
    }

    private companion object {
        /** The registry's pages + the docs index + the packaged docs: well above this. */
        const val MIN_LOCATIONS = 40
        const val SECONDS_PER_MINUTE = 60

        val LINK = Regex("""^- \[([^\]]+)\]\((https://[^)]+)\)""", RegexOption.MULTILINE)
        val LINK_LINE = Regex("""- \[[^\]]+\]\(https://[^)]+\)(: .+)?""")
    }
}
