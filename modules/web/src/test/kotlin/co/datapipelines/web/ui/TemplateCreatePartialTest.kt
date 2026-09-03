package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateDraft
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateUsageService
import co.datapipelines.templates.TemplateValidationException
import co.datapipelines.templates.TemplateValidationFailure
import co.datapipelines.templates.TemplateValidationResult
import co.datapipelines.templates.TemplateValidator
import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import java.time.Instant
import java.util.UUID

/**
 * The create modal's action (template-hierarchy-design §9.3).
 *
 * `type` is a create-time input, immutable afterwards (§5.3), and `dialect` is conditional on
 * it: required for `sql`, absent for `html`. The form hides the dialect control for `html` —
 * these tests are about the fact that the SERVER, not the form, is what makes that rule true.
 * A hidden field is one devtools edit away from being submitted; `chk_type_dialect` and this
 * controller are what actually hold.
 */
class TemplateCreatePartialTest {
    private val repository = mockk<TemplateRepository>()
    private val pipelines = mockk<PipelineRepository>()
    private val validator = mockk<TemplateValidator>()
    private val authoring = mockk<AuthoringGuard>(relaxed = true)
    private val controller =
        TemplatePartialController(
            repository,
            TemplateBrowseModel(repository),
            validator,
            authoring,
            TemplateUsageService(repository, pipelines),
        )

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                AuthenticatedPrincipal(
                    userId,
                    "a@b.c",
                    "A",
                    setOf(Scope.AUTHOR),
                    AuthMethod.OIDC,
                    workspace = WorkspaceContext(workspaceId, "acme"),
                ),
                null,
                emptyList(),
            )
    }

    private fun stubWrite(): CapturingSlot<TemplateDraft> {
        val captured = slot<TemplateDraft>()
        every { repository.existsId(any(), any()) } returns false
        every { validator.validateOrThrow(capture(captured), any()) } answers { captured.captured }
        every { repository.create(any(), any(), any()) } answers {
            val d = secondArg<TemplateDraft>()
            Template(
                id = d.id!!,
                version = 1,
                type = d.type ?: TemplateType.SQL,
                dialect = d.dialect,
                displayName = d.displayName,
                description = d.description,
                body = d.body,
                createdAt = Instant.parse("2026-09-02T10:00:00Z"),
                createdBy = userId,
            )
        }
        // The success path refreshes the ROOT level out-of-band.
        every { repository.listChildFolders(any(), any(), any(), any(), any()) } returns emptyList()
        every { repository.listChildTemplates(any(), any(), any(), any(), any(), any()) } returns emptyList()
        every { repository.countChildTemplates(any(), any(), any(), any()) } returns 0
        every { repository.findDrafts(any(), any()) } returns emptyMap()
        return captured
    }

    @Test
    fun `a sql template keeps its dialect and lands through the shared validator`() {
        authenticate()
        val captured = stubWrite()
        val model = ExtendedModelMap()

        val view = controller.create(model, "acme/finance/monthly_revenue", "sql", "POSTGRES", null, "Revenue.", "SELECT 1")

        view shouldBe "partials/template-created"
        captured.captured.id shouldBe "acme/finance/monthly_revenue"
        captured.captured.type shouldBe TemplateType.SQL
        captured.captured.dialect shouldBe Dialect.POSTGRES
        // The display name defaults to the path when the optional field is blank.
        captured.captured.displayName shouldBe "acme/finance/monthly_revenue"
        model["createdName"] shouldBe "acme/finance/monthly_revenue"
        model["oob"] shouldBe true
        verify { authoring.requireTemplateAuthoring() }
    }

    @Test
    fun `an html template's dialect is DROPPED even when the request still carries one`() {
        authenticate()
        val captured = stubWrite()

        // Exactly the devtools case: the form hides the control, the request sends it anyway.
        controller.create(ExtendedModelMap(), "acme/docs/report", "html", "POSTGRES", null, "A report.", "<p>hi</p>")

        captured.captured.type shouldBe TemplateType.HTML
        captured.captured.dialect shouldBe null
    }

    @Test
    fun `a name the server rejects comes back as an inline refusal, with the grammar`() {
        authenticate()
        every { repository.existsId(any(), any()) } returns false
        every { validator.validateOrThrow(any(), any()) } throws
            TemplateValidationException(
                TemplateValidationResult(
                    listOf(TemplateValidationFailure("template.validation.invalid_id", "Invalid template id.", emptyMap())),
                ),
            )

        val response = controller.create(ExtendedModelMap(), "Acme/Finance", "sql", "POSTGRES", null, "d", "SELECT 1")

        val entity = response as ResponseEntity<*>
        entity.statusCode shouldBe HttpStatus.BAD_REQUEST
        entity.body.toString() shouldContain "Invalid template id."
        // §9.5: the server's refusal is the one that counts, and it says what the rule is.
        entity.body.toString() shouldContain "at most 10 segments"
    }

    @Test
    fun `a duplicate name is refused before any write`() {
        authenticate()
        every { repository.existsId(any(), "acme/finance/monthly_revenue") } returns true

        val response = controller.create(ExtendedModelMap(), "acme/finance/monthly_revenue", "sql", "POSTGRES", null, "d", "SELECT 1")

        (response as ResponseEntity<*>).statusCode shouldBe HttpStatus.BAD_REQUEST
        response.body.toString() shouldContain "already exists"
        verify(exactly = 0) { repository.create(any(), any(), any()) }
    }

    @Test
    fun `an unknown type is refused, never defaulted`() {
        authenticate()

        val response = controller.create(ExtendedModelMap(), "acme/x", "pdf", null, null, "d", "x")

        (response as ResponseEntity<*>).statusCode shouldBe HttpStatus.BAD_REQUEST
        response.body.toString() shouldContain "Unknown template type"
        verify(exactly = 0) { repository.create(any(), any(), any()) }
    }

    @Test
    fun `the versions fragment derives DRAFT from the one draft pointer`() {
        authenticate()
        every { repository.listVersions(any(), "acme/x") } returns emptyList()
        every { repository.findLatest(any(), "acme/x") } returns null
        every { repository.findDraftDetail(any(), "acme/x") } returns null
        every { pipelines.countWorkingTemplatePinsByPinnedVersion(any(), "acme/x") } returns emptyMap()

        val model = ExtendedModelMap()
        controller.versions(model, "acme/x") shouldBe "partials/template-detail"

        model["templateId"] shouldBe "acme/x"
        model["draftVersion"] shouldBe null
        model["versions"].shouldNotBeNull()
        // 040 D6: the same service's in-use counts ride the model — the empty map renders
        // every row's em dash, never a missing-variable error.
        model["inUse"] shouldBe emptyMap<Int, Int>()
    }
}
