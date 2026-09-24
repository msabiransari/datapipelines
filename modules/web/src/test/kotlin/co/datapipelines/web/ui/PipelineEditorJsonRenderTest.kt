package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.http.HttpServletRequest
import org.jsoup.Jsoup
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication
import java.time.Instant
import java.util.UUID

/**
 * 185 — the editor's JSON blobs cannot be broken out of by the free text they carry.
 *
 * The controller serialises the pipeline's full tree — `display_name`, `description`, node
 * labels, template names, all free text — and the template inserts it with `th:utext` into
 * `<script type="application/json">` blocks. HTML reads a script element's content as raw
 * text whatever its type, so an unescaped `</script>` inside a value ends the element and
 * the rest renders as markup. This test drives the REAL controller, renders the page the
 * way the browser parses it (jsoup), and asserts the document's script-element set is
 * exactly the one the templates declare: the payload survives only as JSON string content.
 *
 * Falsification: remove the `ScriptSafeJson.forScriptBlock` wrapping in
 * `PipelineEditorController` — the payload closes the block, the parser finds the injected
 * element, and every assertion here goes red.
 */
class PipelineEditorJsonRenderTest {
    private val repository = mockk<PipelineRepository>()
    private val themeResolver = mockk<ThemeResolver>()
    private val controller = PipelineEditorController(co.datapipelines.web.pipelineServiceOver(repository), themeResolver)

    private val pipelineId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

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

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `a closing-tag payload in the display name cannot inject a script element`() {
        authenticate()
        every { repository.findById(any(), pipelineId) } returns record()
        every { repository.findVersionBody(any(), pipelineId, 1) } returns bodyJson(EXPLOIT)
        every { repository.findDraftDetail(any(), pipelineId) } returns null
        every { repository.findCurrentVersionDetail(any(), pipelineId) } returns null
        every { themeResolver.resolve(any()) } returns "saas"

        val model = ExtendedModelMap()
        controller.editor(pipelineId, model, mockk<HttpServletRequest>())
        val html = engine.process("pipelines/editor", webContext(model))
        val doc = Jsoup.parse(html)

        // The document's script elements are exactly the ones the templates declare: every
        // vendor/page script loads from src (188: the rail-state script too — /js/rail.js),
        // and the two JSON blobs are the only inline ones. An injected <script> is a third.
        val inline = doc.select("script").filter { !it.hasAttr("src") }
        inline.map { it.id() } shouldContainExactlyInAnyOrder listOf("pipeline-data", "pipeline-lifecycle")
        inline.filter { it.attr("type") == "application/json" }.map { it.id() } shouldContainExactlyInAnyOrder
            listOf("pipeline-data", "pipeline-lifecycle")

        // The payload crossed as JSON string content only: both blobs carry no closing-tag
        // sequence, and the first one parses back to the tree the controller was given.
        val dataJson = requireNotNull(doc.selectFirst("#pipeline-data")) { "no #pipeline-data block" }.data()
        val dataLifecycle = requireNotNull(doc.selectFirst("#pipeline-lifecycle")) { "no #pipeline-lifecycle block" }.data()
        (dataJson.contains("</", ignoreCase = true)) shouldBe false
        (dataLifecycle.contains("</", ignoreCase = true)) shouldBe false
        PipelineJson.objectMapper().readTree(dataJson)["display_name"].asText() shouldBe EXPLOIT
    }

    // ------------------------------------------------------------------ fixtures

    private fun authenticate() {
        val principal =
            AuthenticatedPrincipal(
                UUID.randomUUID(),
                "a@b.c",
                "A",
                AuthMethod.OIDC,
                workspace = WorkspaceContext(workspaceId, "acme"),
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private fun record(): PipelineRecord =
        PipelineRecord(
            id = pipelineId,
            name = "sample_pipeline",
            displayName = "Sample Pipeline",
            description = "A sample pipeline for testing",
            ownerId = UUID.randomUUID(),
            currentVersion = 1,
            createdAt = Instant.parse("2026-08-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-08-01T00:00:00Z"),
        )

    private fun bodyJson(displayName: String): String =
        """
        {
          "schema_version": 1,
          "name": "sample_pipeline",
          "display_name": ${PipelineJson.objectMapper().writeValueAsString(displayName)},
          "description": "A sample pipeline for testing",
          "settings": {"tempdb": {"engine": "H2"}},
          "parameters": {},
          "nodes": []
        }
        """.trimIndent()

    /** The layout chrome the shell needs, then the controller's model on top of it. */
    private fun webContext(model: ExtendedModelMap): WebContext {
        val context =
            WebContext(
                JakartaServletWebApplication
                    .buildApplication(MockServletContext())
                    .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
            )
        context.setVariable("_csrf", mapOf("token" to "t"))
        context.setVariable("workspaceHeaderFragment", "")
        context.setVariable("workspaceOptions", emptyList<Any>())
        context.setVariable("activeWorkspace", "acme")
        context.setVariable("authenticated", true)
        context.setVariable("currentPath", "/pipelines")
        model.asMap().forEach { (k, v) -> context.setVariable(k, v) }
        return context
    }

    private companion object {
        /** The stored-XSS payload the editor's free-text fields must not be able to actuate. */
        const val EXPLOIT = """x</script><script>alert(1)</script>"""
    }
}
