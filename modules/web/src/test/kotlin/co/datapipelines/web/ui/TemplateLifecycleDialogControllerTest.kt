package co.datapipelines.web.ui

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateVersionDetail
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.templates.TemplateReleaseService
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.UUID

/**
 * The template twin of [PipelineLifecycleDialogControllerTest] (ui-screens §4.6, 102): the
 * Shape A / Shape C / HX-Redirect delivery over the NAME-addressed routes (§9.6), the
 * `template.version.confirm_mismatch` guard that fires before the service, and the
 * sole-draft purge's entity redirect.
 */
class TemplateLifecycleDialogControllerTest {
    private val releases = mockk<TemplateReleaseService>()
    private val templates = mockk<TemplateRepository>()
    private val dialogs = mockk<TemplateLifecycleDialogModel>()
    private val audit = PipelineLifecycleDialogControllerTest.RecordingAudit()
    private val browse = mockk<TemplateBrowseModel>(relaxed = true)

    private lateinit var mvc: MockMvc

    @BeforeEach
    fun authenticate() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                AuthenticatedPrincipal(
                    userId = USER,
                    email = "probe@test",
                    displayName = "Probe",
                    scopes = setOf(Scope.AUTHOR),
                    authMethod = AuthMethod.OIDC,
                    workspace = WorkspaceContext(WORKSPACE, "probe"),
                ),
                null,
                emptyList(),
            )
        mvc =
            MockMvcBuilders
                .standaloneSetup(TemplateLifecycleDialogController(releases, templates, dialogs, browse, audit))
                .setControllerAdvice(UiExceptionHandler())
                .build()
    }

    @AfterEach
    fun clear() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `release - Shape A with the toast and the lifecycle-changed payload`() {
        every { browse.fillDetail(any(), WORKSPACE, PATH) } returns "partials/template-detail"
        // The draft exists FOR the release and is GONE after it — the payload reads post-state.
        every { templates.findDraftDetail(WORKSPACE, PATH) } returns draftDetail() andThen null
        every { releases.release(WORKSPACE, PATH, "h2", USER) } returns
            TemplateReleaseService.Released(
                draftDetail().copy(status = PipelineVersionStatus.RELEASED),
                template(version = 2),
            )
        every { templates.findLatest(WORKSPACE, PATH) } returns template(version = 2)

        mvc
            .perform(
                post("/partials/templates/lifecycle/release")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("name", PATH)
                    .header("HX-Request", "true"),
            ).andExpect(status().isOk)
            .andExpect(
                header().string(
                    "HX-Trigger",
                    "{\"lifecycle-changed\":{\"leafId\":\"$PATH\",\"workingVersion\":2,\"hasDraft\":false}}",
                ),
            ).andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                    .view()
                    .name("partials/template-lifecycle-applied"),
            ).andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                    .model()
                    .attribute("lifecycleToastTitle", "Released v2"),
            )
    }

    @Test
    fun `release - the editor surface answers HX-Redirect`() {
        every { templates.findDraftDetail(WORKSPACE, PATH) } returns draftDetail()
        every { releases.release(WORKSPACE, PATH, "h2", USER) } returns
            TemplateReleaseService.Released(draftDetail().copy(status = PipelineVersionStatus.RELEASED), template(version = 2))

        mvc
            .perform(
                post("/partials/templates/lifecycle/release")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("name", PATH)
                    .param("from", "editor")
                    .header("HX-Request", "true"),
            ).andExpect(header().string("HX-Redirect", "/templates/editor?name=nyc%2Fmobility%2Fprobe.sql&ok=released"))
    }

    @Test
    fun `purge - the sole draft takes the template, and the tree loses the leaf by redirect`() {
        every { templates.listVersions(WORKSPACE, PATH) } returns
            listOf(
                co.datapipelines.templates.TemplateVersionSummary(
                    id = PATH,
                    version = 2,
                    createdAt = Instant.now(),
                    createdBy = USER,
                    status = PipelineVersionStatus.DRAFT,
                ),
            )
        every { releases.purgeVersion(WORKSPACE, PATH, 2) } returns Unit

        mvc
            .perform(
                post("/partials/templates/lifecycle/purge")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("name", PATH)
                    .param("version", "2")
                    .param("confirm", "v2")
                    .header("HX-Request", "true"),
            ).andExpect(header().string("HX-Redirect", "/templates?ok=template_purged"))
    }

    @Test
    fun `purge - a confirm mismatch is a 400 and the service was never called`() {
        val response =
            mvc
                .perform(
                    post("/partials/templates/lifecycle/purge")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("name", PATH)
                        .param("version", "2")
                        .param("confirm", "nope")
                        .header("HX-Request", "true"),
                ).andExpect(status().isBadRequest)
                .andReturn()
                .response.contentAsString

        response shouldContain "template.version.confirm_mismatch"
        verify(exactly = 0) { releases.purgeVersion(any(), any(), any()) }
    }

    @Test
    fun `discard - the in-use refusal is Shape C with the real 409`() {
        every { releases.discardVersion(WORKSPACE, PATH, 1, USER) } throws
            DatapipelinesException(
                code = PipelineErrorCodes.Template.IN_USE,
                message = "Version of template '$PATH' is pinned by 1 live pipeline version(s): nyc/rollup",
                details = mapOf("template_id" to PATH, "pinned_by" to listOf("nyc/rollup")),
            )

        val response =
            mvc
                .perform(
                    post("/partials/templates/lifecycle/discard")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("name", PATH)
                        .param("version", "1")
                        .header("HX-Request", "true"),
                ).andExpect(status().isConflict)
                .andExpect(header().string("HX-Retarget", "#toast"))
                .andReturn()
                .response.contentAsString

        response shouldContain "template.in_use"
    }

    @Test
    fun `purge entity - the confirm names the template, and the redirect carries the toast`() {
        every { releases.purgeEntity(WORKSPACE, PATH) } returns Unit

        mvc
            .perform(
                post("/partials/templates/lifecycle/purge-entity")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("name", PATH)
                    .param("confirm", PATH)
                    .header("HX-Request", "true"),
            ).andExpect(header().string("HX-Redirect", "/templates?ok=template_purged"))
    }

    // ------------------------------------------------------------------ fixtures

    private fun template(version: Int): Template =
        Template(
            id = PATH,
            version = version,
            dialect = co.datapipelines.typesystem.Dialect.POSTGRES,
            displayName = "probe",
            description = "",
            body = "SELECT 1",
            createdAt = Instant.parse("2026-09-08T10:00:00Z"),
            createdBy = USER,
        )

    private fun draftDetail() =
        TemplateVersionDetail(
            templateId = PATH,
            version = 2,
            status = PipelineVersionStatus.DRAFT,
            bodyHash = "h2",
            createdAt = Instant.parse("2026-09-08T10:00:00Z"),
            createdBy = USER,
        )

    private companion object {
        val WORKSPACE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000010")
        const val PATH = "nyc/mobility/probe.sql"
        val USER: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
