package co.datapipelines.web.ui

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication

/**
 * The in-product docs screens (033), rendered exactly as DocsController hands them to the
 * engine — against the REAL packaged spec set (classpath:docs from processResources).
 *
 * 033/B2: the "no unresolved expression" assertion is scoped to the CHROME (layout, nav,
 * docs-index markup). The doc body is exempt BY DESIGN: it reaches the page via th:utext,
 * so a `${...}` inside it is data — and the positive proof that holds is the verbatim
 * survival of a real `${...}` sample from a real doc.
 */
class DocsRenderTest {
    private val catalog = DocsCatalog(javaClass.classLoader)

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
    fun `the docs index renders all groups and titles with clean chrome`() {
        val html =
            engine.process(
                "docs/index",
                webContext().apply { setVariable("groups", catalog.index()) },
            )

        html shouldContain "Operations manual"
        html shouldContain "Contracts"
        html shouldContain "Reference"
        html shouldContain "href=\"/docs/deployment\""
        html shouldContain "href=\"/docs/mcp-server\""
        html shouldContain "href=\"/docs/pipeline-contract\""

        // " th:" with the leading space — an unprocessed th:* attribute. Comments are
        // stripped first: the layout documents its own contract in prose ("th:if CANNOT
        // share an element with th:replace"), and the root's xmlns:th declaration survives
        // every render legitimately.
        val swept = html.replace(COMMENTS, "")
        swept shouldNotContain " th:"
        swept shouldNotContain ("\${")
    }

    @Test
    fun `a rendered doc page keeps dollar-brace placeholders verbatim and its chrome clean`() {
        val doc = catalog.render("auth") ?: error("auth doc must be packaged")
        val sample =
            Regex("""\$\{[^}]+}""").find(doc.html)?.value
                ?: error("auth.md carries a \${...} sample (033/B) — the test would be vacuous without it")

        val html =
            engine.process(
                "docs/doc",
                webContext().apply { setVariable("docHtml", doc.html) },
            )

        // B2 positive: the placeholder is DATA — th:utext inserted it literally.
        html shouldContain sample
        // GFM tables and heading anchors actually render.
        html shouldContain "<table>"
        html shouldContain "id=\""
        // B2 scope: the CHROME (everything up to the doc body) is expression-free —
        // comments stripped (they quote th:* in prose) and " th:" with a leading space
        // (the layout root's xmlns:th declaration is legitimate).
        val chrome = html.substringBefore("<article").replace(COMMENTS, "")
        chrome shouldNotContain " th:"
        chrome shouldNotContain ("\${")
    }

    @Test
    fun `every packaged doc renders through the docs-doc template without error`() {
        catalog.index().flatMap { it.docs }.shouldNotBeEmpty()
        catalog.index().flatMap { it.docs }.forEach { entry ->
            val rendered = catalog.render(entry.slug) ?: error("${entry.slug} indexed but not rendered")
            engine.process(
                "docs/doc",
                webContext().apply { setVariable("docHtml", rendered.html) },
            ) shouldContain "<article"
        }
    }

    @Test
    fun `an unknown slug is a 404, never the 500 handler`() {
        val mv = DocsController(catalog).doc("no-such-doc")

        mv.status shouldBe HttpStatus.NOT_FOUND
        mv.viewName shouldBe "error/404"
    }

    private fun webContext(): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        ).apply { fillLayoutChrome() }

    private companion object {
        val COMMENTS = Regex("<!--[\\s\\S]*?-->")
    }

    /** The UiWorkspaceAdvice set every layout render needs. */
    private fun WebContext.fillLayoutChrome() {
        setVariable("_csrf", mapOf("token" to "t"))
        setVariable("workspaceHeaderFragment", "")
        setVariable("workspaceOptions", emptyList<Any>())
        setVariable("activeWorkspace", null)
        setVariable("activeTheme", "saas")
        setVariable("authenticated", true)
        setVariable("currentPath", "/docs")
    }
}
