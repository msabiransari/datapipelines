package co.datapipelines.web.ui

import co.datapipelines.web.ui.site.SITE_ORIGIN
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.servlet.view.InternalResourceViewResolver

/**
 * The raw-Markdown docs route (173 §C.2), on the REAL packaged spec set.
 *
 * Three claims. (1) The rewrite: every relative link in every served `.md` resolves to a
 * packaged slug's `.md` twin or to GitHub — the same two branches `DocsLinkRewriteTest`
 * holds for the HTML, both non-vacuous, fragments kept — so nothing an agent follows out of
 * `/llms-full.txt` dangles. (2) The wire: `/docs/{slug}.md` and `/docs/{slug}` with
 * `Accept: text/markdown` answer the Markdown as `text/markdown` with the public cache
 * window, and an unknown slug is a Markdown 404, not an HTML error page. (3) The HTML page
 * still wins when the caller wants HTML — the negotiation is one mapping, not a takeover.
 */
class DocsMarkdownTest {
    private val catalog = DocsCatalog(javaClass.classLoader)
    private val slugs = catalog.index().flatMap { group -> group.docs.map { it.slug } }

    private val mvc =
        MockMvcBuilders
            .standaloneSetup(DocsController(catalog))
            .setViewResolvers(InternalResourceViewResolver())
            .build()

    @Test
    fun `every served markdown carries only resolvable links - md twins, GitHub, absolute, in-page`() {
        slugs.shouldNotBeEmpty()
        val destinations =
            slugs.flatMap { slug ->
                INLINE_LINK.findAll(checkNotNull(catalog.markdown(slug))).map { it.groupValues[1] }
            }
        val twins = destinations.filter { it.startsWith("/docs/") }
        val github = destinations.filter { it.startsWith(DocsCatalog.GITHUB_BLOB_BASE) }

        // Both branches non-vacuous, at the HTML test's floors.
        twins.size shouldBeGreaterThan 100
        github.size shouldBeGreaterThan 25
        // A twin is always the .md route, fragment after the suffix.
        twins.filterNot { MD_TWIN.matches(it) }.shouldBeEmpty()
        // And a twin always names a packaged slug.
        twins.map { it.removePrefix("/docs/").substringBefore(".md") }.filterNot { it in slugs }.shouldBeEmpty()
        // Nothing relative survives.
        destinations
            .filterNot {
                it.startsWith("/docs/") || it.startsWith("http://") || it.startsWith("https://") ||
                    it.startsWith("#") || it.startsWith("mailto:")
            }.shouldBeEmpty()
    }

    @Test
    fun `the rewrite rule for markdown - packaged slug gets its md twin, fragment kept, the rest as the HTML rule`() {
        val packaged = setOf("docs/rest-api.md", "docs/auth.md")
        DocsCatalog.markdownHref("rest-api.md#6-sse-stream", packaged) shouldBe "/docs/rest-api.md#6-sse-stream"
        DocsCatalog.markdownHref("auth.md", packaged) shouldBe "/docs/auth.md"
        DocsCatalog.markdownHref("SPEC-REVIEW-2026-08.md#x", packaged) shouldBe
            "${DocsCatalog.GITHUB_BLOB_BASE}docs/SPEC-REVIEW-2026-08.md#x"
        DocsCatalog.markdownHref("#local", packaged) shouldBe "#local"
        DocsCatalog.markdownHref("https://example.com/a.md", packaged) shouldBe "https://example.com/a.md"

        // The text rewrite touches the destination and nothing else — a title survives.
        DocsCatalog.rewriteMarkdownLinks("""see [auth](auth.md#7 "Auth") and [x](https://x.y)""", packaged) shouldBe
            """see [auth](/docs/auth.md#7 "Auth") and [x](https://x.y)"""
    }

    @Test
    fun `the md route serves the packaged markdown as text-markdown with the public cache window`() {
        val result =
            mvc
                .perform(get("/docs/auth.md"))
                .andExpect(status().isOk)
                .andExpect(content().contentType(DocsController.MARKDOWN))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("public")))
                .andReturn()
        val body = result.response.contentAsString
        body shouldStartWith "# Auth"
        body shouldBe checkNotNull(catalog.markdown("auth"))
    }

    @Test
    fun `the html route negotiates to markdown when the caller accepts it, and stays html otherwise`() {
        mvc
            .perform(get("/docs/auth").accept(MediaType.parseMediaType("text/markdown")))
            .andExpect(status().isOk)
            .andExpect(content().contentType(DocsController.MARKDOWN))
            .andExpect(content().string(containsString("# Auth")))

        // A browser's Accept (html first, */* after) keeps the page.
        val html =
            mvc
                .perform(get("/docs/auth").accept(MediaType.TEXT_HTML, MediaType.ALL))
                .andExpect(status().isOk)
                .andReturn()
        html.modelAndView?.viewName shouldBe "docs/doc-public"

        // No Accept at all is */*: the page, not the source.
        mvc.perform(get("/docs/auth")).andReturn().modelAndView?.viewName shouldBe "docs/doc-public"
    }

    @Test
    fun `an unknown slug is a markdown 404 that points at the index`() {
        mvc
            .perform(get("/docs/nope.md"))
            .andExpect(status().isNotFound)
            .andExpect(content().contentType(DocsController.MARKDOWN))
            .andExpect(content().string(containsString("/llms.txt")))
    }

    @Test
    fun `the public html page names its markdown twin and the twin is absolute-resolvable`() {
        val model =
            mvc
                .perform(get("/docs/auth"))
                .andReturn()
                .modelAndView
                ?.model
        model?.get("alternateMarkdown") shouldBe "/docs/auth.md"
        (model?.get("canonicalUrl") as String) shouldContain SITE_ORIGIN
    }

    private companion object {
        val INLINE_LINK = Regex("""\]\(([^)\s]+)(?:\s+"[^"]*")?\)""")
        val MD_TWIN = Regex("""/docs/[a-z0-9-]+\.md(#.*)?""")
    }
}
