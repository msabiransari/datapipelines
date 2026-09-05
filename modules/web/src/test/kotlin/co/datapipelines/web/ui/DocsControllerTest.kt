package co.datapipelines.web.ui

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.ui.ExtendedModelMap
import org.springframework.ui.Model

/**
 * [DocsController] — the in-product spec set's routing contract beside DocsRenderTest's
 * rendering coverage: the grouped index, the slug's case-insensitivity, and the quiet 404
 * for a name that is not a doc (never an error page — a bad link is an ordinary miss).
 *
 * 073 adds the split that made the docs public: an ANONYMOUS request renders the public
 * chrome with an SEO head, a SIGNED-IN one keeps the app chrome and gets no head model at
 * all. Both assertions matter — the second is the no-regression half, and the pair is what
 * would go red if the branch were dropped in either direction.
 */
class DocsControllerTest {
    private val docs = mockk<DocsCatalog>()
    private val controller = DocsController(docs)

    @Test
    fun `the index carries the catalog's groups`() {
        val groups = listOf(DocsCatalog.DocGroup("guides", emptyList()))
        every { docs.index() } returns groups

        val model = ExtendedModelMap()
        controller.index(model, MockHttpServletResponse()) shouldBe "docs/index-public"
        model["groups"] shouldBe groups
    }

    @Test
    fun `a slug is lowercased before lookup`() {
        val rendered = renderedDoc("Pipeline Contract")
        every { docs.render("pipeline-contract") } returns rendered

        val view = controller.doc("Pipeline-Contract", ExtendedModelMap(), MockHttpServletResponse())

        view.modelMap["docTitle"] shouldBe "Pipeline Contract"
        view.modelMap["docHtml"] shouldBe rendered.html
        verify(exactly = 1) { docs.render("pipeline-contract") }
    }

    @Test
    fun `an unknown slug is the 404 view`() {
        every { docs.render(any()) } returns null

        val view = controller.doc("no-such-doc", ExtendedModelMap(), MockHttpServletResponse())

        view.viewName shouldBe "error/404"
        view.status shouldBe HttpStatus.NOT_FOUND
    }

    @Test
    fun `anonymous gets the public chrome, an SEO head and a public cache window`() {
        every { docs.render("pipeline-contract") } returns renderedDoc("Pipeline Contract")
        val response = MockHttpServletResponse()

        val view = controller.doc("pipeline-contract", ExtendedModelMap(), response)

        view.viewName shouldBe "docs/doc-public"
        view.modelMap["pageTitle"] shouldBe "Pipeline Contract — datapipelines.co docs"
        view.modelMap["pageDescription"] shouldBe DESCRIPTION
        view.modelMap["canonicalUrl"] shouldBe "https://datapipelines.co/docs/pipeline-contract"
        view.modelMap["extraCss"] shouldBe "/css/docs.css"
        response.getHeader("Cache-Control") shouldContain "public"
    }

    @Test
    fun `signed in, the same doc keeps the app chrome and no head model`() {
        every { docs.render("pipeline-contract") } returns renderedDoc("Pipeline Contract")

        val view = controller.doc("pipeline-contract", signedIn(), MockHttpServletResponse())

        view.viewName shouldBe "docs/doc"
        view.modelMap["pageTitle"] shouldBe null
        view.modelMap["canonicalUrl"] shouldBe null
    }

    @Test
    fun `signed in, the index keeps the app chrome`() {
        every { docs.index() } returns emptyList()

        controller.index(signedIn(), MockHttpServletResponse()) shouldBe "docs/index"
    }

    @Test
    fun `a doc title that overruns the display limit falls back to the H1's lead segment`() {
        // Fits: used whole.
        DocsController.docPageTitle("Auth & Security Specification") shouldBe
            "Auth & Security Specification — datapipelines.co docs"
        // key-providers.md, verbatim: 63 characters before the suffix, 87 after.
        DocsController.docPageTitle("Key Providers — implementing a KMS-backed credential key source") shouldBe
            "Key Providers — datapipelines.co docs"
        // A colon is the other break these specs use.
        DocsController.docPageTitle("Versioning: draft, release, promotion, and everything that follows from them") shouldBe
            "Versioning — datapipelines.co docs"
        // No break at all: cut at a word boundary, never mid-word, and never over the limit.
        val runOn = DocsController.docPageTitle("A very long heading with no natural break anywhere in it at all whatsoever")
        runOn.length shouldBe 65
        runOn shouldBe "A very long heading with no natural break — datapipelines.co docs"
    }

    /** The model UiWorkspaceAdvice hands a handler when a principal resolved. */
    private fun signedIn(): Model = ExtendedModelMap().addAttribute("authenticated", true)

    private fun renderedDoc(title: String): DocsCatalog.RenderedDoc {
        val entry =
            DocsCatalog.DocEntry(
                slug = "pipeline-contract",
                title = title,
                group = "contracts",
                description = DESCRIPTION,
            )
        val rendered = mockk<DocsCatalog.RenderedDoc>()
        every { rendered.entry } returns entry
        every { rendered.html } returns "<h1>$title</h1>"
        return rendered
    }

    private companion object {
        const val DESCRIPTION = "The declarative JSON a pipeline is."
    }
}
