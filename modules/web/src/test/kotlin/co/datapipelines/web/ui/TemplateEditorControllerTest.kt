package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateDraft
import co.datapipelines.templates.TemplateDraftService
import co.datapipelines.templates.TemplateEngine
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateVersion
import co.datapipelines.templates.TemplateVersionDetail
import co.datapipelines.templates.TemplateVersionSummary
import co.datapipelines.templates.WorkspaceTemplateEngines
import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import java.time.Instant
import java.util.UUID

/**
 * The editor controller after #398: the old PAGE is a compatibility REDIRECT onto the
 * canonical workspace, the source-column partial is unchanged (the workspace's Source tab is
 * its second paint), and **Edit** keeps its lifecycle rule (a draft exists ⇒ open it, write
 * nothing; else copy the selected version through the same service the REST write uses) with
 * its landing moved to the workspace, draft explicit.
 *
 * The workspace PAGE's own model — including R5's read-only conjunction — has its tests in
 * [TemplateWorkspaceControllerTest]; the no-clamp rule (an unknown explicit version is the
 * 404, never the release) is [TemplateWorkspaceModelTest]'s. The partial keeps the old
 * column's tolerance here, because the partial is the editor contract's own fragment.
 */
class TemplateEditorControllerTest {
    private val templates = mockk<TemplateRepository>()
    private val engine = mockk<TemplateEngine>()
    private val engines =
        mockk<WorkspaceTemplateEngines> {
            every { engineFor(any()) } returns engine
        }
    private val drafts = mockk<TemplateDraftService>()
    private val controller =
        TemplateEditorController(
            templates,
            engines,
            drafts,
            co.datapipelines.templates.TemplateService(templates),
            co.datapipelines.web.EVERYTHING_LENS,
        )

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    /**
     * The session under test. An AUTHOR by default — the pre-143 tests were written about an
     * author's editor (the route floored at MUTATE, so nobody else could reach it); 143 opens
     * the page to every reader, and the tests below pass the membership they mean.
     */
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

    private val sampleTemplate =
        Template(
            id = "test/my_template.sql",
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
            TemplateVersionSummary("test/my_template.sql", 2, Instant.parse("2026-08-02T00:00:00Z"), userId),
            TemplateVersionSummary("test/my_template.sql", 1, Instant.parse("2026-08-01T00:00:00Z"), userId),
        )

    // ------------------------------------------------------------------ the redirect (#398)

    @Test
    fun `the old editor route is a 302 onto the canonical workspace`() {
        authenticate()

        val view = controller.editor("test/my_template.sql", null, null)

        view.url shouldBe "/templates/test/my_template.sql"
    }

    @Test
    fun `an explicit version and a supported tab survive the redirect`() {
        authenticate()

        val view = controller.editor("test/my_template.sql", "2", "overview")

        view.url shouldBe "/templates/test/my_template.sql?version=2&tab=overview"
    }

    @Test
    fun `an unsupported tab is dropped, never forwarded`() {
        authenticate()

        val view = controller.editor("test/my_template.sql", null, "not-a-tab")

        view.url shouldBe "/templates/test/my_template.sql"
    }

    @Test
    fun `a malformed version is the same house 400 the canonical route answers`() {
        authenticate()

        val thrown =
            io.kotest.assertions.throwables.shouldThrow<org.springframework.web.server.ResponseStatusException> {
                controller.editor("test/my_template.sql", "two", null)
            }
        thrown.statusCode.value() shouldBe 400
    }

    /** The name travels the path as SEGMENTS (the workspace's capture variable), never %2F. */
    @Test
    fun `a name with a slash lands as path segments on the workspace URL`() {
        authenticate()

        val view = controller.editor("acme/finance/rev.sql", null, null)

        view.url shouldBe "/templates/acme/finance/rev.sql"
    }

    /** The success half of `edit`: an HX-Redirect entity, not a fragment. */
    @Suppress("UNCHECKED_CAST")
    private fun redirect(result: Any) = result as org.springframework.http.ResponseEntity<String>

    @Test
    fun `render preview returns rendered output HTML`() {
        authenticate()
        every { templates.lookupVersion(any(), "test/my_template.sql", 1) } returns
            TemplateVersion(
                id = "test/my_template.sql",
                version = 1,
                dialect = Dialect.POSTGRES,
                isLibrary = false,
                imports = emptyList(),
                body = "SELECT \${x}",
                createdAt = Instant.EPOCH,
                createdBy = userId,
            )
        every { engine.render(any(), mapOf("x" to 42)) } returns "SELECT 42"

        val preview = controller.renderPreview("test/my_template.sql", 1, "SELECT \${x}", """{"x":42}""")

        // 097 §C: the preview is a fragment and a model now, not a Kotlin-built card. The
        // rendered SQL is DATA on that model, which is what makes it escaped by construction.
        preview.viewName shouldBe "partials/template-render"
        preview.model["renderOutput"] shouldBe "SELECT 42"
        preview.model["renderError"] shouldBe null
    }

    @Test
    fun `render preview of a missing template is the refusal fragment`() {
        authenticate()
        every { templates.lookupVersion(any(), "gone.sql", 1) } returns null
        every { templates.existsId(any(), "gone.sql") } returns false

        val preview = controller.renderPreview("gone.sql", 1, "SELECT 1", "{}")

        preview.model["renderError"] shouldBe "Template 'gone.sql' not found."
    }

    @Test
    fun `render preview with broken context JSON is the refusal fragment`() {
        authenticate()
        every { templates.lookupVersion(any(), "test/my_template.sql", 1) } returns
            TemplateVersion(
                id = "test/my_template.sql",
                version = 1,
                dialect = Dialect.POSTGRES,
                isLibrary = false,
                imports = emptyList(),
                body = "SELECT 1",
                createdAt = Instant.EPOCH,
                createdBy = userId,
            )

        val preview = controller.renderPreview("test/my_template.sql", 1, "SELECT 1", "{oops")

        (preview.model["renderError"] as String) shouldContain "Invalid context JSON"
    }

    private val olderVersion =
        Template(
            id = "test/my_template.sql",
            version = 1,
            dialect = Dialect.POSTGRES,
            displayName = "My Template",
            description = "desc",
            body = "SELECT old FROM t",
            createdAt = Instant.parse("2026-08-01T00:00:00Z"),
            createdBy = userId,
        )

    private fun releasedDetail(version: Int) =
        TemplateVersionDetail(
            templateId = "test/my_template.sql",
            version = version,
            status = PipelineVersionStatus.RELEASED,
            bodyHash = "hash-v$version",
            createdAt = Instant.parse("2026-08-01T09:30:00Z"),
            createdBy = userId,
            releasedAt = Instant.parse("2026-08-02T09:30:00Z"),
            releasedBy = userId,
        )

    private fun draftDetail(version: Int) =
        TemplateVersionDetail(
            templateId = "test/my_template.sql",
            version = version,
            status = PipelineVersionStatus.DRAFT,
            bodyHash = "hash-draft",
            createdAt = Instant.parse("2026-08-03T00:00:00Z"),
            createdBy = userId,
        )

    /** 143 — the partial the Source tab paints keeps its read-only conjunction over the role. */
    @Test
    fun `a non-author opens the working version read-only on the partial`() {
        authenticate(WorkspaceRole.VIEWER)
        every { templates.findLatest(any(), "test/my_template.sql") } returns sampleTemplate
        every { templates.findWorking(any(), "test/my_template.sql") } returns sampleTemplate
        every { templates.listVersions(any(), "test/my_template.sql") } returns sampleVersions
        every { templates.findDraftDetail(any(), any()) } returns null
        every { templates.findVersionDetail(any(), "test/my_template.sql", 2) } returns
            TemplateVersionDetail("test/my_template.sql", 2, PipelineVersionStatus.RELEASED, "h2", Instant.EPOCH, userId)

        val partial = ExtendedModelMap()
        controller.source("test/my_template.sql", null, partial)
        partial["readOnly"] shouldBe true
        partial["canAuthor"] shouldBe false
    }

    /** 143 — the page route floors at READ: the screen's lowest role reads it (the 122 rule). */
    @Test
    fun `the editor route is floored at template read and the writes stay on their authoring permissions`() {
        val scopeOf = { name: String ->
            TemplateEditorController::class.java.methods
                .single { it.name == name }
                .getAnnotation(RequiredScope::class.java)
                .value
        }
        scopeOf("editor") shouldBe Permission.TEMPLATE_READ
        scopeOf("source") shouldBe Permission.TEMPLATE_READ
        scopeOf("edit") shouldBe Permission.TEMPLATE_UPDATE
        scopeOf("renderPreview") shouldBe Permission.TEMPLATE_RENDER
    }

    @Test
    fun `selecting an older version loads it read-only with its badge and release metadata`() {
        authenticate()
        every { templates.findLatest(any(), "test/my_template.sql") } returns sampleTemplate
        every { templates.findVersion(any(), "test/my_template.sql", 1) } returns olderVersion
        every { templates.findVersionDetail(any(), "test/my_template.sql", 1) } returns releasedDetail(1)
        every { templates.findDraftDetail(any(), any()) } returns null

        val model = ExtendedModelMap()
        controller.source("test/my_template.sql", 1, model) shouldBe "partials/template-source"

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
        every { templates.findDraftDetail(any(), "test/my_template.sql") } returns draftDetail(3)
        every { templates.findLatest(any(), "test/my_template.sql") } returns sampleTemplate
        every { templates.findWorking(any(), "test/my_template.sql") } returns sampleTemplate
        every { templates.findVersion(any(), "test/my_template.sql", 3) } returns sampleTemplate.copy(version = 3)

        val model = ExtendedModelMap()
        controller.source("test/my_template.sql", 3, model)

        model["readOnly"] shouldBe false
        model["workingVersion"] shouldBe 3
    }

    @Test
    fun `a version parameter naming no stored row falls back to the current release ON THE PARTIAL`() {
        authenticate()
        every { templates.findDraftDetail(any(), "test/my_template.sql") } returns null
        every { templates.findLatest(any(), "test/my_template.sql") } returns sampleTemplate
        every { templates.findWorking(any(), "test/my_template.sql") } returns sampleTemplate
        every { templates.findVersion(any(), "test/my_template.sql", 99) } returns null

        val model = ExtendedModelMap()
        controller.source("test/my_template.sql", 99, model)

        // The PARTIAL keeps the old column's tolerance — never an empty editable textarea
        // claiming to be v99. The WORKSPACE answers the same URL with the family's 404 (the
        // no-clamp rule is #398's); that is TemplateWorkspaceModelTest's assertion.
        (model["template"] as Template).version shouldBe 2
        model["selectedVersion"] shouldBe 2
        model["readOnly"] shouldBe false
    }

    @Test
    fun `Edit on a released version copies THAT version into a new draft and lands on it`() {
        authenticate()
        every { templates.findDraftDetail(any(), "test/my_template.sql") } returnsMany listOf(null, draftDetail(3))
        every { templates.findVersion(any(), "test/my_template.sql", 1) } returns olderVersion
        every { templates.findLatest(any(), "test/my_template.sql") } returns sampleTemplate
        every { templates.findVersionDetail(any(), "test/my_template.sql", 2) } returns releasedDetail(2)
        val written = slot<TemplateDraft>()
        val expectedHash = slot<String>()
        every { drafts.write(any(), "test/my_template.sql", capture(written), capture(expectedHash), userId, WriteSurface.SESSION) } returns
            draftDetail(3)

        val response = redirect(controller.edit("test/my_template.sql", 1))

        response.statusCode.value() shouldBe 200
        // #398: the landing is the CANONICAL workspace with the new draft EXPLICIT — a
        // version-less redirect would land on the read-only release (the default resolution),
        // not the draft the author just opened.
        response.headers.getFirst("HX-Redirect") shouldBe "/templates/test/my_template.sql?version=3&tab=source"
        // The COPY is of the selected version, not of the current release...
        written.captured.body shouldBe "SELECT old FROM t"
        // ...and the precondition is the CURRENT RELEASE's hash, which is the row the
        // create-draft guard reads. Basing it on the selected version's hash would 409.
        expectedHash.captured shouldBe "hash-v2"
    }

    @Test
    fun `Edit with a draft present opens THAT draft and asks for no second one`() {
        authenticate()
        every { templates.findDraftDetail(any(), "test/my_template.sql") } returns draftDetail(3)

        val response = redirect(controller.edit("test/my_template.sql", 1))

        response.headers.getFirst("HX-Redirect") shouldBe "/templates/test/my_template.sql?version=3&tab=source"
        // The invariant: the UI never asks for a second draft, and never overwrites the
        // author's in-progress one with the body of the version they were merely reading.
        verify(exactly = 0) { drafts.write(any(), any(), any(), any(), any(), WriteSurface.SESSION) }
    }

    @Test
    fun `Edit on a version that does not exist is refused in place, never a write`() {
        authenticate()
        every { templates.findDraftDetail(any(), "test/my_template.sql") } returns null
        every { templates.findVersion(any(), "test/my_template.sql", 9) } returns null

        // The refusal renders in place — a 200 carrying the reason, because htmx does not
        // swap 4xx bodies. It is the shared inline-refusal fragment since 097 §C.
        val refusal = controller.edit("test/my_template.sql", 9) as org.springframework.web.servlet.ModelAndView

        refusal.viewName shouldBe "partials/inline-refusal"
        (refusal.model["message"] as String) shouldContain "was not found"
        verify(exactly = 0) { drafts.write(any(), any(), any(), any(), any(), WriteSurface.SESSION) }
    }

    @Test
    fun `Edit's landing carries a multi-segment name as path segments`() {
        authenticate()
        every { templates.findDraftDetail(any(), "acme/finance/rev.sql") } returns draftDetail(2)

        val response = redirect(controller.edit("acme/finance/rev.sql", 1))

        response.headers.getFirst("HX-Redirect") shouldBe "/templates/acme/finance/rev.sql?version=2&tab=source"
    }

    // ------------------------------------------------------------------ 7d: the transform face

    /**
     * 7d — a transform template's source column IS the face: the partial answers with the
     * face view and carries the four panes and the save precondition, so the workspace's
     * Source tab and the partial agree (TemplateSourceModel's one rule).
     */
    @Test
    fun `a transform template's source partial is the four-pane face`() {
        authenticate()
        val draft = TransformFixtures.storedSkeleton(version = 1, bodyHash = "hash-transform-draft")
        every { templates.findLatest(any(), TransformFixtures.NAME) } returns null
        every { templates.findDraftDetail(any(), TransformFixtures.NAME) } returns
            draftDetail(1).copy(templateId = TransformFixtures.NAME, bodyHash = "hash-transform-draft")
        every { templates.findVersion(any(), TransformFixtures.NAME, 1) } returns draft
        every { templates.listVersions(any(), TransformFixtures.NAME) } returns emptyList()

        val partial = ExtendedModelMap()
        controller.source(TransformFixtures.NAME, null, partial) shouldBe TransformFace.VIEW
        partial["isTransform"] shouldBe true
        partial["faceEditable"] shouldBe true
        partial["faceHash"] shouldBe "hash-transform-draft"
        (partial["panes"] as TransformPanes).contract shouldContain "\"mode\": \"row\""
    }

    @Test
    fun `an sql template keeps the Freemarker source column - isTransform is false`() {
        authenticate()
        every { templates.findLatest(any(), "test/my_template.sql") } returns sampleTemplate
        every { templates.findDraftDetail(any(), any()) } returns null

        val model = ExtendedModelMap()
        controller.source("test/my_template.sql", null, model) shouldBe "partials/template-source"
        model["isTransform"] shouldBe false
    }

    /**
     * 7d — Edit on a released TRANSFORM version copies its three blocks too. Before 7d the copy
     * (`asDraft`) carried the body alone, so the draft write was refused `contract_invalid`
     * blocks_missing — Edit could never open a released transform for editing.
     */
    @Test
    fun `Edit on a released transform version copies the contract, invariants and tests with the body`() {
        authenticate()
        val released = TransformFixtures.storedSkeleton(version = 1, status = PipelineVersionStatus.RELEASED)
        every { templates.findDraftDetail(any(), TransformFixtures.NAME) } returnsMany listOf(null, draftDetail(2))
        every { templates.findVersion(any(), TransformFixtures.NAME, 1) } returns released
        every { templates.findLatest(any(), TransformFixtures.NAME) } returns released
        every { templates.findVersionDetail(any(), TransformFixtures.NAME, 1) } returns releasedDetail(1)
        val written = slot<TemplateDraft>()
        every { drafts.write(any(), TransformFixtures.NAME, capture(written), "hash-v1", userId, WriteSurface.SESSION) } returns
            draftDetail(2)

        redirect(controller.edit(TransformFixtures.NAME, 1)).statusCode.value() shouldBe 200

        written.captured.engine shouldBe Template.NONE_ENGINE
        written.captured.contract shouldBe released.contract
        written.captured.invariants shouldBe released.invariants
        written.captured.tests shouldBe released.tests
    }
}
