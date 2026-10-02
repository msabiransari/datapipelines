package co.datapipelines.web.ui

import co.datapipelines.application.templates.TemplateUsage
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateService
import co.datapipelines.templates.TemplateVersionDetail
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/**
 * The canonical template workspace (#398): the route's A.3 decision end-to-end (the dotted,
 * multi-segment capture, the grammar-404 before any read), the A.2 resolution order through
 * the REAL model over a stubbed repository — explicit admitted or the family's 404 (never a
 * clamp), release first by default, the draft-only shape, the choose-a-version state — and
 * R5's read-only conjunction on the page's model.
 *
 * The falsification guards the brief names live here: reversing the resolve order (release
 * before the explicit version) turns the wrong-version assertions red, and relaxing the
 * grammar check or the existence 404 turns the not-found assertions red.
 */
class TemplateWorkspaceControllerTest {
    private val repository = mockk<TemplateRepository>()
    private val themeResolver = mockk<ThemeResolver>()
    private val pipelines = mockk<co.datapipelines.pipeline.PipelineRepository>()

    /** The composed usage the model reads (the record's §8.4 arrow), over the same doubles. */
    private val usage =
        TemplateUsage(
            co.datapipelines.templates.TemplateUsageService(repository, pipelines),
            mockk<co.datapipelines.parameters.ParameterSetTemplatePins>(relaxed = true),
            pipelines,
            mockk<co.datapipelines.visualization.ArtifactDependents>(relaxed = true),
        )

    init {
        // The Used-by facts read the composed reverse arrow; the tests here are about the
        // VERSION resolution, so the pin reads answer empty.
        every { pipelines.countWorkingTemplatePinsByPinnedVersion(any(), any()) } returns emptyMap()
        every { pipelines.findAnyVersionTemplatePins(any(), any()) } returns emptyList()
    }

    private val model = TemplateWorkspaceModel(TemplateService(repository), usage, ActorNames(mockk(relaxed = true)))
    private val controller =
        TemplateWorkspaceController(model, themeResolver, co.datapipelines.web.EVERYTHING_LENS)

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate(role: WorkspaceRole = WorkspaceRole.AUTHOR) {
        val principal =
            AuthenticatedPrincipal(
                userId,
                "a@b.c",
                "A",
                AuthMethod.OIDC,
                workspace = WorkspaceContext(workspaceId, "acme", role),
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private fun template(
        id: String,
        version: Int,
        body: String = "SELECT $version",
    ) = Template(
        id = id,
        version = version,
        dialect = Dialect.POSTGRES,
        displayName = id.substringAfterLast('/'),
        description = "d",
        body = body,
        createdAt = Instant.parse("2026-08-01T00:00:00Z"),
        createdBy = userId,
    )

    private val name = "demo/top_carrier.sql"

    /** v2 is the current release; v1 is history. */
    private fun stubReleased() {
        every { repository.existsId(workspaceId, name) } returns true
        every { repository.findLatest(workspaceId, name) } returns template(name, 2)
        every { repository.findDraftDetail(workspaceId, name) } returns null
        every { repository.listVersions(workspaceId, name) } returns
            listOf(
                co.datapipelines.templates.TemplateVersionSummary(name, 2, Instant.parse("2026-08-02T00:00:00Z"), userId),
                co.datapipelines.templates.TemplateVersionSummary(name, 1, Instant.parse("2026-08-01T00:00:00Z"), userId),
            )
        every { repository.findVersion(workspaceId, name, 2) } returns template(name, 2)
        every { repository.findVersion(workspaceId, name, 1) } returns template(name, 1)
        every { repository.findVersionDetail(workspaceId, name, 2) } returns releasedDetail(name, 2)
        every { repository.findVersionDetail(workspaceId, name, 1) } returns releasedDetail(name, 1)
        every { repository.findVersionDetail(workspaceId, name, 3) } returns
            TemplateVersionDetail(name, 3, PipelineVersionStatus.DRAFT, "h3", Instant.EPOCH, userId)
    }

    // ------------------------------------------------------------------ A.3: the route

    @Test
    fun `a dotted multi-segment name reaches the workspace handler whole`() {
        authenticate()
        stubReleased()
        every { themeResolver.resolve(any()) } returns "saas"

        val view = controller.workspace("/$name", null, null, ExtendedModelMap(), MockHttpServletRequest())

        view shouldBe "templates/workspace"
    }

    @Test
    fun `a name that fails the grammar is the family 404 BEFORE any read`() {
        authenticate()
        // Nothing stubbed: reaching the repository would throw (a strict mock). The grammar
        // check must refuse the climb segment first.
        val thrown =
            shouldThrow<co.datapipelines.typesystem.DatapipelinesException> {
                controller.workspace("/../secret", null, null, ExtendedModelMap(), MockHttpServletRequest())
            }
        thrown.code shouldBe "template.not_found"
    }

    @Test
    fun `an absent template and a bare-slash capture answer the same 404`() {
        authenticate()
        every { repository.existsId(workspaceId, "gone.sql") } returns false

        shouldThrow<co.datapipelines.typesystem.DatapipelinesException> {
            controller.workspace("/gone.sql", null, null, ExtendedModelMap(), MockHttpServletRequest())
        }.code shouldBe "template.not_found"

        // The trailing-slash capture is "/" — not a legal name, so the grammar check fires.
        shouldThrow<co.datapipelines.typesystem.DatapipelinesException> {
            controller.workspace("/", null, null, ExtendedModelMap(), MockHttpServletRequest())
        }.code shouldBe "template.not_found"
    }

    @Test
    fun `a malformed version is the house 400, never a silent clamp`() {
        authenticate()

        shouldThrow<ResponseStatusException> {
            controller.workspace("/$name", "two", null, ExtendedModelMap(), MockHttpServletRequest())
        }.statusCode.value() shouldBe 400
    }

    /** 143's page-floor rule, carried onto the workspace. */
    @Test
    fun `the workspace route is floored at template read`() {
        val scope =
            TemplateWorkspaceController::class.java.methods
                .single { it.name == "workspace" }
                .getAnnotation(RequiredScope::class.java)
                .value
        scope shouldBe Permission.TEMPLATE_READ
    }

    // ------------------------------------------------------------------ A.2: the resolution

    @Test
    fun `no version resolves the current RELEASE by default`() {
        authenticate()
        stubReleased()

        val resolved = model.resolve(workspaceId, co.datapipelines.application.lens.LensedView.EVERYTHING, name, null)

        resolved.viewedVersion shouldBe 2
        resolved.viewedIsCurrent shouldBe true
        resolved.viewedIsDraft shouldBe false
    }

    @Test
    fun `an explicit admitted version is the one shown — never the release`() {
        authenticate()
        stubReleased()

        val resolved = model.resolve(workspaceId, co.datapipelines.application.lens.LensedView.EVERYTHING, name, 1)

        // The falsification anchor: release the explicit-version branch and this reads v2 —
        // the clamp the spec forbids.
        resolved.viewedVersion shouldBe 1
        resolved.viewedIsCurrent shouldBe false
        resolved.viewedTemplate!!.body shouldBe "SELECT 1"
    }

    @Test
    fun `an explicit version naming no stored row is the family 404, never a fallback`() {
        authenticate()
        stubReleased()
        every { repository.findVersion(workspaceId, name, 99) } returns null

        shouldThrow<co.datapipelines.typesystem.DatapipelinesException> {
            model.resolve(workspaceId, co.datapipelines.application.lens.LensedView.EVERYTHING, name, 99)
        }.code shouldBe "template.not_found"
    }

    @Test
    fun `a draft is explicit and visibly a draft`() {
        authenticate()
        stubReleased()
        val draft =
            TemplateVersionDetail(name, 3, PipelineVersionStatus.DRAFT, "h3", Instant.EPOCH, userId)
        every { repository.findDraftDetail(workspaceId, name) } returns draft
        every { repository.findVersion(workspaceId, name, 3) } returns template(name, 3, "SELECT 3 -- draft")
        every { repository.listVersions(workspaceId, name) } returns
            listOf(
                co.datapipelines.templates.TemplateVersionSummary(name, 3, Instant.parse("2026-08-03T00:00:00Z"), userId),
                co.datapipelines.templates.TemplateVersionSummary(name, 2, Instant.parse("2026-08-02T00:00:00Z"), userId),
            )

        val resolved = model.resolve(workspaceId, co.datapipelines.application.lens.LensedView.EVERYTHING, name, 3)

        resolved.viewedVersion shouldBe 3
        resolved.viewedIsDraft shouldBe true
        resolved.viewedIsCurrent shouldBe false
        resolved.viewedIsWorkingDraft shouldBe true
    }

    @Test
    fun `the default view is the RELEASE even when a draft exists`() {
        authenticate()
        stubReleased()
        every { repository.findDraftDetail(workspaceId, name) } returns
            TemplateVersionDetail(name, 3, PipelineVersionStatus.DRAFT, "h3", Instant.EPOCH, userId)

        val resolved = model.resolve(workspaceId, co.datapipelines.application.lens.LensedView.EVERYTHING, name, null)

        resolved.viewedVersion shouldBe 2
        resolved.viewedIsDraft shouldBe false
    }

    @Test
    fun `draft-only renders the draft, labelled`() {
        authenticate()
        val draft =
            TemplateVersionDetail("test/only.sql", 1, PipelineVersionStatus.DRAFT, "h1", Instant.EPOCH, userId)
        every { repository.existsId(workspaceId, "test/only.sql") } returns true
        every { repository.findLatest(workspaceId, "test/only.sql") } returns null
        every { repository.findDraftDetail(workspaceId, "test/only.sql") } returns draft
        every { repository.findVersion(workspaceId, "test/only.sql", 1) } returns template("test/only.sql", 1)
        every { repository.listVersions(workspaceId, "test/only.sql") } returns
            listOf(co.datapipelines.templates.TemplateVersionSummary("test/only.sql", 1, Instant.EPOCH, userId))

        val resolved = model.resolve(workspaceId, co.datapipelines.application.lens.LensedView.EVERYTHING, "test/only.sql", null)

        resolved.viewedVersion shouldBe 1
        resolved.viewedIsDraft shouldBe true
        resolved.currentVisible shouldBe null
    }

    @Test
    fun `no current with history is the choose-a-version state - no body selected`() {
        authenticate()
        every { repository.existsId(workspaceId, name) } returns true
        every { repository.findLatest(workspaceId, name) } returns null
        every { repository.findDraftDetail(workspaceId, name) } returns null
        every { repository.listVersions(workspaceId, name) } returns
            listOf(co.datapipelines.templates.TemplateVersionSummary(name, 1, Instant.EPOCH, userId))
        every { repository.findVersion(workspaceId, name, 1) } returns template(name, 1)

        val resolved = model.resolve(workspaceId, co.datapipelines.application.lens.LensedView.EVERYTHING, name, null)

        resolved.hasSelectedBody shouldBe false
        resolved.viewedVersion shouldBe null
        resolved.versions.size shouldBe 1
    }

    @Test
    fun `an unknown or hidden template is the 404 - one answer for every absence`() {
        authenticate()
        every { repository.existsId(workspaceId, "hidden.sql") } returns false

        shouldThrow<co.datapipelines.typesystem.DatapipelinesException> {
            model.resolve(workspaceId, co.datapipelines.application.lens.LensedView.EVERYTHING, "hidden.sql", null)
        }.code shouldBe "template.not_found"
    }

    // ------------------------------------------------------------------ the Render tab's escaping

    /**
     * The Render tab's output, its refusal and every name on this surface are user-typed or
     * user-chosen, and the fragment renders them through `th:text` — the browser must
     * receive TEXT, never markup. The hostile values are the security brief's plants
     * (`</script>`, quotes, `<img onerror>`); this is the witness the falsification flips:
     * a `th:utext` on the render output goes red here.
     */
    @Test
    fun `the render fragment escapes its output and its refusal - text, never markup`() {
        val html = renderRenderFragment(renderOutput = "</script><img src=x onerror=alert(1)>", renderError = null)

        html shouldContain "&lt;/script&gt;"
        html shouldNotContain "<img src=x"
        html shouldNotContain "</script>"

        val refusal = renderRenderFragment(renderOutput = "", renderError = "Render failed: \"quoted\" <b>and marked</b>")

        refusal shouldContain "&lt;b&gt;and marked&lt;/b&gt;"
        refusal shouldContain "&quot;"
    }

    private fun renderRenderFragment(
        renderOutput: String,
        renderError: String?,
    ): String {
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
            WebContext(
                JakartaServletWebApplication
                    .buildApplication(MockServletContext())
                    .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
            )
        context.setVariable("renderOutput", renderOutput)
        context.setVariable("renderError", renderError)
        return engine.process("partials/template-render", context)
    }

    /** A RELEASED row with its provenance — the stub detail the resolve rules read. */
    private fun releasedDetail(
        id: String,
        version: Int,
    ): TemplateVersionDetail =
        TemplateVersionDetail(
            templateId = id,
            version = version,
            status = PipelineVersionStatus.RELEASED,
            bodyHash = "h$version",
            createdAt = Instant.EPOCH,
            createdBy = userId,
            releasedAt = Instant.EPOCH,
            releasedBy = userId,
        )

    // ------------------------------------------------------------------ the page's model

    @Test
    fun `the page stamps the sidebar hook, the tab and the R5 read-only rule`() {
        authenticate()
        stubReleased()
        every { themeResolver.resolve(any()) } returns "saas"

        val page = ExtendedModelMap()
        controller.workspace("/$name", "2", "source", page, MockHttpServletRequest())

        page["navCurrentPath"] shouldBe name
        page["activeTab"] shouldBe "source"
        page["viewedVersion"] shouldBe 2
        page["viewedIsDraft"] shouldBe false
        // R5 on the page: the current release is never the editable surface.
        page["viewedEditable"] shouldBe false
        page["readOnly"] shouldBe true
        page["canAuthor"] shouldBe true
        page["hasSelectedBody"] shouldBe true
    }

    @Test
    fun `an unknown tab resolves to the default, and render resolves to source for a reader`() {
        authenticate(WorkspaceRole.VIEWER)
        stubReleased()
        every { themeResolver.resolve(any()) } returns "saas"

        val unknown = ExtendedModelMap()
        controller.workspace("/$name", null, "bogus", unknown, MockHttpServletRequest())
        unknown["activeTab"] shouldBe "source"

        val render = ExtendedModelMap()
        controller.workspace("/$name", null, "render", render, MockHttpServletRequest())
        // A viewer's render request never reaches the render pane: the tab resolves first.
        render["activeTab"] shouldBe "source"
    }

    @Test
    fun `a transform template has no render tab - the request resolves to source`() {
        authenticate()
        val transformName = "demo/row_pick.jsonata"
        every { repository.existsId(workspaceId, transformName) } returns true
        every { repository.findLatest(workspaceId, transformName) } returns
            TransformFixtures.storedSkeleton(version = 1).let { it.copy(id = transformName, version = 1) }
        every { repository.findDraftDetail(workspaceId, transformName) } returns null
        every { repository.listVersions(workspaceId, transformName) } returns
            listOf(co.datapipelines.templates.TemplateVersionSummary(transformName, 1, Instant.EPOCH, userId))
        every { repository.findVersion(workspaceId, transformName, 1) } returns
            TransformFixtures.storedSkeleton(version = 1).let { it.copy(id = transformName) }
        every { repository.findVersionDetail(workspaceId, transformName, 1) } returns releasedDetail(transformName, 1)
        every { themeResolver.resolve(any()) } returns "saas"

        val page = ExtendedModelMap()
        controller.workspace("/$transformName", null, "render", page, MockHttpServletRequest())

        page["activeTab"] shouldBe "source"
        page["isTransform"] shouldBe true
    }
}
