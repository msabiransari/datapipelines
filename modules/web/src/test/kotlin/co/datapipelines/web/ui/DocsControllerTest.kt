package co.datapipelines.web.ui

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.ui.ExtendedModelMap
import org.springframework.web.servlet.ModelAndView

/**
 * [DocsController] — the in-product spec set's routing contract beside DocsRenderTest's
 * rendering coverage: the grouped index, the slug's case-insensitivity, and the quiet 404
 * for a name that is not a doc (never an error page — a bad link is an ordinary miss).
 */
class DocsControllerTest {
    private val docs = mockk<DocsCatalog>()
    private val controller = DocsController(docs)

    @Test
    fun `the index carries the catalog's groups`() {
        val groups = listOf(DocsCatalog.DocGroup("guides", emptyList()))
        every { docs.index() } returns groups

        val model = ExtendedModelMap()
        controller.index(model) shouldBe "docs/index"
        model["groups"] shouldBe groups
    }

    @Test
    fun `a slug is lowercased before lookup`() {
        val rendered = renderedDoc("Pipeline Contract")
        every { docs.render("pipeline-contract") } returns rendered

        val view = controller.doc("Pipeline-Contract")

        view.modelMap["docTitle"] shouldBe "Pipeline Contract"
        view.modelMap["docHtml"] shouldBe rendered.html
        verify(exactly = 1) { docs.render("pipeline-contract") }
    }

    @Test
    fun `an unknown slug is the 404 view`() {
        every { docs.render(any()) } returns null

        val view = controller.doc("no-such-doc")

        view.viewName shouldBe "error/404"
        view.status shouldBe HttpStatus.NOT_FOUND
    }

    private fun renderedDoc(title: String): DocsCatalog.RenderedDoc {
        val entry = DocsCatalog.DocEntry(slug = "pipeline-contract", title = title, group = "contracts")
        val rendered = mockk<DocsCatalog.RenderedDoc>()
        every { rendered.entry } returns entry
        every { rendered.html } returns "<h1>$title</h1>"
        return rendered
    }
}
