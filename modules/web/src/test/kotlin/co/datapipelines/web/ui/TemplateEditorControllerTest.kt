package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateDraft
import co.datapipelines.templates.TemplateDraftService
import co.datapipelines.templates.TemplateEngine
import co.datapipelines.templates.TemplateRenderException
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateVersion
import co.datapipelines.templates.TemplateVersionDetail
import co.datapipelines.templates.TemplateVersionSummary
import co.datapipelines.templates.WorkspaceTemplateEngines
import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
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
    private val drafts = mockk<TemplateDraftService>()
    private val controller = TemplateEditorController(templates, engines, themeResolver, drafts)

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
        every { templates.findDraftDetail(any(), any()) } returns null
        every { themeResolver.resolve(any()) } returns "saas"

        val model: ExtendedModelMap = ExtendedModelMap()
        val viewName = controller.editor("my_template.sql", null, model, mockk(relaxed = true))

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

    // ------------------------------------------------------------------ R5 (054)
    //
    // Selecting a version LOADS it, read-only; Edit is the only way to an edit target,
    // and it applies the lifecycle rule 035/039 shipped rather than re-deciding it.

    private val olderVersion = sampleTemplate.copy(version = 1, body = "SELECT old FROM t")

    private fun releasedDetail(version: Int) =
        TemplateVersionDetail(
            templateId = "my_template.sql",
            version = version,
            status = PipelineVersionStatus.RELEASED,
            bodyHash = "hash-v$version",
            createdAt = Instant.parse("2026-08-01T00:00:00Z"),
            createdBy = userId,
            releasedAt = Instant.parse("2026-08-02T09:30:00Z"),
            releasedBy = userId,
        )

    private fun draftDetail(version: Int) =
        TemplateVersionDetail(
            templateId = "my_template.sql",
            version = version,
            status = PipelineVersionStatus.DRAFT,
            bodyHash = "hash-draft",
            createdAt = Instant.parse("2026-08-03T00:00:00Z"),
            createdBy = userId,
        )

    @Test
    fun `no version parameter shows the working version, editable`() {
        authenticate()
        every { templates.findLatest(any(), "my_template.sql") } returns sampleTemplate
        every { templates.listVersions(any(), "my_template.sql") } returns sampleVersions
        every { templates.findDraftDetail(any(), any()) } returns null
        every { themeResolver.resolve(any()) } returns "saas"

        val model = ExtendedModelMap()
        controller.editor("my_template.sql", null, model, mockk(relaxed = true))

        model["readOnly"] shouldBe false
        model["selectedVersion"] shouldBe 2
        model["workingVersion"] shouldBe 2
    }

    @Test
    fun `selecting an older version loads it read-only with its badge and release metadata`() {
        authenticate()
        every { templates.findLatest(any(), "my_template.sql") } returns sampleTemplate
        every { templates.findVersion(any(), "my_template.sql", 1) } returns olderVersion
        every { templates.findVersionDetail(any(), "my_template.sql", 1) } returns releasedDetail(1)
        every { templates.findDraftDetail(any(), any()) } returns null

        val model = ExtendedModelMap()
        controller.source("my_template.sql", 1, model) shouldBe "partials/template-source"

        model["readOnly"] shouldBe true
        model["selectedVersion"] shouldBe 1
        model["selectedStatus"] shouldBe "RELEASED"
        model["releasedAt"] shouldBe Instant.parse("2026-08-02T09:30:00Z")
        model["releasedBy"] shouldBe userId.toString()
        (model["template"] as Template).body shouldBe "SELECT old FROM t"
    }

    @Test
    fun `the DRAFT is the working version, so selecting it is the editable view`() {
        authenticate()
        every { templates.findDraftDetail(any(), "my_template.sql") } returns draftDetail(3)
        every { templates.findLatest(any(), "my_template.sql") } returns sampleTemplate
        every { templates.findVersion(any(), "my_template.sql", 3) } returns sampleTemplate.copy(version = 3)

        val model = ExtendedModelMap()
        controller.source("my_template.sql", 3, model)

        model["readOnly"] shouldBe false
        model["workingVersion"] shouldBe 3
    }

    @Test
    fun `a version parameter naming no stored row falls back to the current release`() {
        authenticate()
        every { templates.findDraftDetail(any(), "my_template.sql") } returns null
        every { templates.findLatest(any(), "my_template.sql") } returns sampleTemplate
        every { templates.findVersion(any(), "my_template.sql", 99) } returns null

        val model = ExtendedModelMap()
        controller.source("my_template.sql", 99, model)

        // Never an empty editable textarea claiming to be v99.
        (model["template"] as Template).version shouldBe 2
        model["selectedVersion"] shouldBe 2
        model["readOnly"] shouldBe false
    }

    @Test
    fun `Edit on a released version copies THAT version into a new draft`() {
        authenticate()
        every { templates.findDraftDetail(any(), "my_template.sql") } returns null
        every { templates.findVersion(any(), "my_template.sql", 1) } returns olderVersion
        every { templates.findLatest(any(), "my_template.sql") } returns sampleTemplate
        every { templates.findVersionDetail(any(), "my_template.sql", 2) } returns releasedDetail(2)
        val written = slot<TemplateDraft>()
        val expectedHash = slot<String>()
        every { drafts.write(any(), "my_template.sql", capture(written), capture(expectedHash), userId) } returns draftDetail(3)

        val response = controller.edit("my_template.sql", 1)

        response.statusCode.value() shouldBe 200
        response.headers.getFirst("HX-Redirect") shouldBe "/templates/editor?name=my_template.sql"
        // The COPY is of the selected version, not of the current release...
        written.captured.body shouldBe "SELECT old FROM t"
        // ...and the precondition is the CURRENT RELEASE's hash, which is the row the
        // create-draft guard reads. Basing it on the selected version's hash would 409.
        expectedHash.captured shouldBe "hash-v2"
    }

    @Test
    fun `Edit with a draft present opens THAT draft and asks for no second one`() {
        authenticate()
        every { templates.findDraftDetail(any(), "my_template.sql") } returns draftDetail(3)

        val response = controller.edit("my_template.sql", 1)

        response.headers.getFirst("HX-Redirect") shouldBe "/templates/editor?name=my_template.sql"
        // The invariant: the UI never asks for a second draft, and never overwrites the
        // author's in-progress one with the body of the version they were merely reading.
        verify(exactly = 0) { drafts.write(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `Edit on a version that does not exist is refused in place, never a write`() {
        authenticate()
        every { templates.findDraftDetail(any(), "my_template.sql") } returns null
        every { templates.findVersion(any(), "my_template.sql", 9) } returns null

        val response = controller.edit("my_template.sql", 9)

        response.statusCode.value() shouldBe 200
        response.headers.getFirst("HX-Redirect") shouldBe null
        response.body!! shouldContain "was not found"
        verify(exactly = 0) { drafts.write(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a name with a slash survives the redirect it never travels a path segment in`() {
        authenticate()
        every { templates.findDraftDetail(any(), "acme/finance/rev.sql") } returns draftDetail(2)

        val response = controller.edit("acme/finance/rev.sql", 1)

        response.headers.getFirst("HX-Redirect") shouldBe "/templates/editor?name=acme%2Ffinance%2Frev.sql"
    }
}
