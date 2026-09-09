package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateUsageService
import co.datapipelines.templates.TemplateValidator
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import org.springframework.web.servlet.ModelAndView
import java.util.UUID

/**
 * [TemplatePartialController] — the browse/search routing and the modal's create path,
 * not the fragments (TemplateCreatePartialTest already pins the created fragment's model).
 *
 * The behaviors pinned: one route chooses its fragment shape by `prefix` (a folder
 * expansion never returns the whole list), `q` is ignored while browsing a level, the
 * versions pane's model (draft pointer + in-use counts), and the create path's refusal
 * ladder — unknown type, duplicate name, authoring gate, validator rejection — every one
 * an inline 400 fragment, never an error page.
 */
class TemplatePartialControllerTest {
    private val templates = mockk<TemplateRepository>()
    private val browse = mockk<TemplateBrowseModel>(relaxed = true)
    private val validator = mockk<TemplateValidator>(relaxed = true)
    private val usage = mockk<TemplateUsageService>()
    private val controller =
        TemplatePartialController(templates, browse, validator, AuthoringGuard(true), usage)

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()
    private val model = ExtendedModelMap()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    init {
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

    private fun template(id: String) =
        Template(
            id = id,
            version = 1,
            dialect = Dialect.POSTGRES,
            displayName = id,
            description = "",
            body = "",
            createdAt = java.time.Instant.EPOCH,
            createdBy = userId,
        )

    // ------------------------------------------------------------ list

    @Test
    fun `no prefix - the wrapper fragment with the trimmed query`() {
        controller.list(model, q = "  rev  ", dialect = null, type = null, prefix = null, offset = 0)

        verify { browse.fillWrapper(model, workspaceId, q = "rev", dialect = null, type = null, offset = 0) }
    }

    @Test
    fun `a prefix - exactly one tree level, and q is ignored while browsing`() {
        controller.list(model, q = "rev", dialect = null, type = null, prefix = "acme/finance", offset = 0)

        verify {
            browse.fillLevel(model, workspaceId, prefix = "acme/finance", dialect = null, type = null, offset = 0)
        }
        verify(exactly = 0) { browse.fillWrapper(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an empty-string prefix is the root level - still the level fragment`() {
        controller.list(model, q = null, dialect = null, type = null, prefix = "", offset = 0)

        verify { browse.fillLevel(model, workspaceId, prefix = "", dialect = null, type = null, offset = 0) }
    }

    @Test
    fun `a blank query is not a query`() {
        controller.list(model, q = "   ", dialect = null, type = null, prefix = null, offset = 0)

        verify { browse.fillWrapper(model, workspaceId, q = null, dialect = null, type = null, offset = 0) }
    }

    // ------------------------------------------------------------ versions

    @Test
    fun `the versions pane carries template, versions, the draft pointer and in-use counts`() {
        val template = template("acme/rev").copy(version = 3)
        val versions =
            listOf(
                co.datapipelines.templates.TemplateVersionSummary("acme/rev", 3, java.time.Instant.EPOCH, userId),
                co.datapipelines.templates.TemplateVersionSummary("acme/rev", 2, java.time.Instant.EPOCH, userId),
            )
        // D55/§7.1: the detail pane reads the WORKING version — a never-released template has
        // no released projection, and the pane must show its draft rather than an empty card.
        every { templates.findWorking(workspaceId, "acme/rev") } returns template
        every { templates.listVersions(workspaceId, "acme/rev") } returns versions
        every { templates.findDraftDetail(workspaceId, "acme/rev") } returns null
        every { usage.inUseCounts(workspaceId, "acme/rev") } returns mapOf(3 to 4)

        controller.versions(model, name = "acme/rev")

        controller.versions(ExtendedModelMap(), "acme/rev") shouldBe "partials/template-detail"
        model["template"] shouldBe template
        model["versions"] shouldBe versions
        model["draftVersion"] shouldBe null
        model["inUse"] shouldBe mapOf(3 to 4)
    }

    // ------------------------------------------------------------ create

    @Test
    fun `create puts the draft through the same validator and repository as the REST surface`() {
        every { templates.existsId(workspaceId, "acme/new") } returns false
        val draftSlot = CapturingSlot<co.datapipelines.templates.TemplateDraft>()
        every { templates.create(workspaceId, capture(draftSlot), userId, co.datapipelines.pipeline.CreateLifecycle.DRAFT) } returns
            template("acme/new")

        val result =
            controller.create(
                model,
                name = " acme/new ",
                type = "sql",
                dialect = "postgres",
                displayName = "  ",
                description = " d ",
                body = "SELECT 1",
            )

        result shouldBe "partials/template-created"
        model["oob"] shouldBe true
        model["createdName"] shouldBe "acme/new"
        val draft = draftSlot.captured
        draft.id shouldBe "acme/new"
        draft.type shouldBe TemplateType.SQL
        draft.dialect shouldBe Dialect.POSTGRES
        draft.displayName shouldBe "acme/new" // blank display falls back to the name
        draft.description shouldBe "d"
        verify { validator.validateOrThrow(any(), workspaceId) }
    }

    @Test
    fun `an html template never carries a dialect - even if the form sends one`() {
        every { templates.existsId(workspaceId, "acme/page") } returns false
        val draftSlot = CapturingSlot<co.datapipelines.templates.TemplateDraft>()
        every { templates.create(workspaceId, capture(draftSlot), userId, co.datapipelines.pipeline.CreateLifecycle.DRAFT) } returns
            template("acme/page")

        controller.create(
            model,
            name = "acme/page",
            type = "html",
            dialect = "postgres",
            displayName = null,
            description = null,
            body = "<p>x</p>",
        )

        draftSlot.captured.dialect shouldBe null
    }

    @Test
    fun `an unknown type is the inline refusal`() {
        val response =
            controller.create(
                model,
                name = "x",
                type = "yaml",
                dialect = null,
                displayName = null,
                description = null,
                body = "b",
            ) as ModelAndView

        response.status shouldBe HttpStatus.BAD_REQUEST
        refusalMessage(response) shouldContain "Unknown template type"
    }

    @Test
    fun `a duplicate name is the inline refusal`() {
        every { templates.existsId(workspaceId, "dupe") } returns true

        val response =
            controller.create(
                model,
                name = "dupe",
                type = "sql",
                dialect = "postgres",
                displayName = null,
                description = null,
                body = "SELECT 1",
            ) as ModelAndView

        response.status shouldBe HttpStatus.BAD_REQUEST
        refusalMessage(response) shouldContain "already exists"
    }

    @Test
    fun `a repository rejection surfaces as the inline refusal, escaped`() {
        every { templates.existsId(workspaceId, "acme/x") } returns false
        every { templates.create(any(), any(), any(), co.datapipelines.pipeline.CreateLifecycle.DRAFT) } throws
            DatapipelinesException("template.invalid", "body uses <forbidden> construct")

        val response =
            controller.create(
                model,
                name = "acme/x",
                type = "sql",
                dialect = "postgres",
                displayName = null,
                description = null,
                body = "SELECT 1",
            ) as ModelAndView

        response.status shouldBe HttpStatus.BAD_REQUEST
        // 097 §C: the escaping moved from a hand-written `.replace("<", "&lt;")` in the
        // controller to `th:text` in the fragment, so this asserts the RENDERED refusal —
        // the thing the browser receives — rather than the string the controller built.
        refusalMessage(response) shouldContain "<forbidden>"
        renderRefusal(response) shouldContain "&lt;forbidden&gt;"
    }

    @Test
    fun `a promotion receiver cannot author - the authoring gate applies on this surface too`() {
        val gated =
            TemplatePartialController(
                templates,
                browse,
                validator,
                AuthoringGuard(false),
                usage,
            )
        every { templates.existsId(workspaceId, "acme/y") } returns false

        val response =
            gated.create(
                ExtendedModelMap(),
                name = "acme/y",
                type = "sql",
                dialect = "postgres",
                displayName = null,
                description = null,
                body = "SELECT 1",
            ) as ModelAndView

        response.status shouldBe HttpStatus.BAD_REQUEST
    }

    /**
     * 097 §C: an inline refusal is `partials/inline-refusal` and a status now, not a Kotlin
     * string, so the assertions read the MESSAGE the fragment will escape and render.
     */
    private fun refusalMessage(result: ModelAndView): String = result.model["message"] as String

    /** The refusal as the browser receives it — the fragment, rendered. */
    private fun renderRefusal(result: ModelAndView): String {
        val engine =
            org.thymeleaf.spring6.SpringTemplateEngine().apply {
                setTemplateResolver(
                    org.thymeleaf.templateresolver.ClassLoaderTemplateResolver().apply {
                        prefix = "templates/"
                        suffix = ".html"
                        characterEncoding = "UTF-8"
                    },
                )
            }
        val context =
            org.thymeleaf.context.WebContext(
                org.thymeleaf.web.servlet.JakartaServletWebApplication
                    .buildApplication(
                        org.springframework.mock.web
                            .MockServletContext(),
                    ).buildExchange(
                        org.springframework.mock.web
                            .MockHttpServletRequest(),
                        org.springframework.mock.web
                            .MockHttpServletResponse(),
                    ),
            )
        result.model.forEach { (k, v) -> context.setVariable(k, v) }
        return engine.process(result.viewName!!, context)
    }
}
