package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateEngine
import co.datapipelines.templates.TemplateRenderException
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateVersion
import co.datapipelines.templates.TemplateVersionSummary
import co.datapipelines.templates.WorkspaceTemplateEngines
import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import java.time.Instant
import java.util.UUID

class TemplateEditorControllerTest {
    private val templates = mockk<TemplateRepository>()
    private val engine = mockk<TemplateEngine>()
    private val engines =
        mockk<WorkspaceTemplateEngines> {
            every { engineFor(any()) } returns engine
        }
    private val themeResolver = mockk<ThemeResolver>()
    private val controller = TemplateEditorController(templates, engines, themeResolver)

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate() {
        val principal =
            AuthenticatedPrincipal(
                userId,
                "a@b.c",
                "A",
                setOf(Scope.AUTHOR),
                AuthMethod.OIDC,
                workspace = WorkspaceContext(workspaceId, "acme"),
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private val sampleTemplate =
        Template(
            id = "my_template.sql",
            version = 2,
            dialect = Dialect.POSTGRES,
            displayName = "My Template",
            description = "desc",
            body = "SELECT \${x} FROM t",
            createdAt = Instant.parse("2026-08-01T00:00:00Z"),
            createdBy = userId,
        )

    private val sampleVersions =
        listOf(
            TemplateVersionSummary("my_template.sql", 2, Instant.parse("2026-08-02T00:00:00Z"), userId),
            TemplateVersionSummary("my_template.sql", 1, Instant.parse("2026-08-01T00:00:00Z"), userId),
        )

    @Test
    fun `editor page returns editor view with template and versions`() {
        authenticate()
        every { templates.findLatest(any(), "my_template.sql") } returns sampleTemplate
        every { templates.listVersions(any(), "my_template.sql") } returns sampleVersions
        every { themeResolver.resolve(any()) } returns "saas"

        val model: ExtendedModelMap = ExtendedModelMap()
        val viewName = controller.editor("my_template.sql", model, mockk(relaxed = true))

        viewName shouldBe "templates/editor"
        @Suppress("UNCHECKED_CAST")
        (model["template"] as Template).id shouldBe "my_template.sql"
        (model["versions"] as List<*>) shouldHaveSize 2
        model["activeTheme"] shouldBe "saas"
    }

    @Test
    fun `render preview returns rendered output HTML`() {
        authenticate()
        every { templates.lookupVersion(any(), "my_template.sql", 1) } returns
            TemplateVersion(
                id = "my_template.sql",
                version = 1,
                dialect = Dialect.POSTGRES,
                isLibrary = false,
                imports = emptyList(),
                body = "SELECT \${x}",
                createdAt = Instant.EPOCH,
                createdBy = userId,
            )
        every { engine.render(any(), mapOf("x" to 42)) } returns "SELECT 42"

        val html = controller.renderPreview("my_template.sql", 1, "SELECT \${x}", """{"x":42}""")

        html shouldContain "SELECT 42"
    }

    @Test
    fun `render preview returns error on missing template`() {
        authenticate()
        every { templates.lookupVersion(any(), "nope.sql", 1) } returns null
        every { templates.existsId(any(), "nope.sql") } returns false

        val html = controller.renderPreview("nope.sql", 1, "body", """{}""")

        html shouldContain "not found"
    }

    @Test
    fun `render preview returns error on engine failure`() {
        authenticate()
        every { templates.lookupVersion(any(), "my_template.sql", 1) } returns
            TemplateVersion(
                id = "my_template.sql",
                version = 1,
                dialect = Dialect.POSTGRES,
                isLibrary = false,
                imports = emptyList(),
                body = "SELECT \${x}",
                createdAt = Instant.EPOCH,
                createdBy = userId,
            )
        every { engine.render(any(), any<Map<String, Any?>>()) } throws
            TemplateRenderException("Undefined variable: x", TemplateRef("my_template.sql", 1))

        val html = controller.renderPreview("my_template.sql", 1, "SELECT \${x}", """{}""")

        html shouldContain "Render failed"
    }
}
