package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.CreateLifecycle
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateDraft
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateUsageService
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import org.springframework.web.servlet.ModelAndView
import java.time.Instant
import java.util.UUID

/**
 * 7d (#7) — the create modal's TRANSFORM path, over 7b's REAL gate ([TransformFixtures]): the
 * blocks bind through the face's binder (7b's strict deserializer) and the create runs the
 * suite. The one write, `TemplateRepository.create`, is captured for its argument.
 */
class TemplateCreateTransformTest {
    private val repository = mockk<TemplateRepository>()
    private val controller =
        TemplatePartialController(
            repository,
            co.datapipelines.web.templateBrowseModelOver(repository, TemplateUsageService(repository, mockk<PipelineRepository>())),
            TransformFixtures.validator(),
            mockk<AuthoringGuard>(relaxed = true),
            co.datapipelines.web.EVERYTHING_LENS,
        )
    private val userId = UUID.randomUUID()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                AuthenticatedPrincipal(userId, "a@b.c", "A", AuthMethod.OIDC, workspace = WorkspaceContext(UUID.randomUUID(), "acme")),
                null,
                emptyList(),
            )
    }

    private fun stubWrite(): io.mockk.CapturingSlot<TemplateDraft> {
        val created = slot<TemplateDraft>()
        every { repository.existsId(any(), any()) } returns false
        every { repository.create(any(), capture(created), userId, CreateLifecycle.DRAFT, WriteSurface.SESSION) } answers {
            Template(
                id = created.captured.id ?: "x",
                version = 1,
                type = created.captured.type ?: TemplateType.SQL,
                dialect = null,
                displayName = created.captured.displayName,
                description = created.captured.description,
                body = created.captured.body,
                createdAt = Instant.parse("2026-09-25T10:00:00Z"),
                createdBy = userId,
            )
        }
        every { repository.listChildFolders(any(), any(), any(), any(), any()) } returns emptyList()
        every { repository.listChildTemplates(any(), any(), any(), any(), any(), any()) } returns emptyList()
        every { repository.countChildTemplates(any(), any(), any(), any()) } returns 0
        every { repository.findDrafts(any(), any()) } returns emptyMap()
        return created
    }

    private fun create(
        type: String,
        body: String = TransformSkeleton.body,
        contract: String? = TransformSkeleton.contract,
        invariants: String? = TransformSkeleton.invariants,
        tests: String? = TransformSkeleton.tests,
        dialect: String? = null,
    ): Any = controller.create(ExtendedModelMap(), "test/shape/lines.jsonata", type, dialect, null, "d", body, contract, invariants, tests)

    private fun refusalText(result: Any): String {
        val mav = result as ModelAndView
        mav.status shouldBe HttpStatus.BAD_REQUEST
        return mav.model["message"] as String
    }

    @Test
    fun `the modal's example creates a jsonata v1 DRAFT through the real gate - engine none, no dialect, three blocks`() {
        authenticate()
        val created = stubWrite()

        // A dialect a devtools edit left in the form is dropped: a transform declares none.
        val result = create("jsonata", dialect = "POSTGRES")

        result shouldBe "partials/template-created"
        created.captured.type shouldBe TemplateType.JSONATA
        created.captured.engine shouldBe Template.NONE_ENGINE
        created.captured.dialect shouldBe null
        created.captured.contract?.rejects shouldBe true
        created.captured.tests?.size shouldBe 3
    }

    @Test
    fun `a body that breaks a case is refused - the line names the tests pane and 7b's code, and nothing is written`() {
        authenticate()
        stubWrite()

        val text = refusalText(create("jsonata", body = """{ "rows": [], "rejects": [] }"""))

        text shouldContain "[tests] template.test_failed"
        text shouldContain "missing customer is rejected"
        // Not a name problem, so the name grammar stays out of the way.
        text shouldNotContain "folder"
        verify(exactly = 0) { repository.create(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a block that is not JSON is refused naming its pane, before 7b is asked`() {
        authenticate()
        stubWrite()

        refusalText(create("jsonata", contract = "{ nope")) shouldContain "[contract] template.contract_invalid"
        verify(exactly = 0) { repository.create(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a javascript template is refused until round two, in the modal's slot`() {
        authenticate()
        stubWrite()

        refusalText(create("javascript")) shouldContain "transform.js.unavailable"
    }
}
