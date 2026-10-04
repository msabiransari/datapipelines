package co.datapipelines.web.ui

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineReleaseService
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineVersionDetail
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
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
 * The lifecycle dialog POSTs' HTTP contract (ui-screens §4.3d, 102), through MockMvc with the
 * session principal the house partial tests use — the delivery shapes are the whole subject:
 *
 * - happy ⇒ `HX-Redirect` (the workspace reloads; the layout's flash bin renders the toast —
 *   #401 removed the explorer legs, and with them the Shape A re-render and its
 *   `HX-Trigger: lifecycle-changed` payload; #407's cascade names are held ONCE in the
 *   actor's session and asserted here on the session, the markup's render suite);
 * - refusal ⇒ Shape C through [UiExceptionHandler]: the REAL 4xx the catalog assigns, the
 *   `HX-Retarget`/`HX-Reswap` pair, and the §13 code in the toast body;
 * - entity purge ⇒ `HX-Redirect` (the tree must lose the leaf);
 * - typed-confirm mismatch ⇒ 400 with `pipeline.version.confirm_mismatch` and the service
 *   NEVER CALLED — a recording verification, not a strict mock (the MISTAKES entry: a strict
 *   mock makes a missing call unobservable by inverting the double).
 */
class PipelineLifecycleDialogControllerTest {
    private val pipelines = mockk<PipelineService>()
    private val dialogs = mockk<PipelineLifecycleDialogModel>()
    private val audit = RecordingAudit()
    private val releaseFlash = ReleaseFlash()

    private lateinit var mvc: MockMvc

    @BeforeEach
    fun authenticate() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                AuthenticatedPrincipal(
                    userId = USER,
                    email = "probe@test",
                    displayName = "Probe",
                    authMethod = AuthMethod.OIDC,
                    workspace = WorkspaceContext(WORKSPACE, "probe"),
                ),
                null,
                emptyList(),
            )
        mvc =
            MockMvcBuilders
                .standaloneSetup(PipelineLifecycleDialogController(pipelines, dialogs, audit, releaseFlash))
                .setControllerAdvice(UiExceptionHandler())
                .build()
    }

    @AfterEach
    fun clear() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `release - the editor surface answers HX-Redirect, and a release that cascaded nothing holds no names`() {
        every { pipelines.findDraft(WORKSPACE, any(), PIPELINE) } returns draftDetail() andThen null
        every { pipelines.release(WORKSPACE, PIPELINE, "h3", USER) } returns
            PipelineReleaseService.Released(record(currentVersion = 3), draftDetail().copy(status = PipelineVersionStatus.RELEASED), "{}")
        val session = MockHttpSession()

        mvc
            .perform(
                post("/partials/pipelines/$PIPELINE/lifecycle/release")
                    .session(session)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("bodyHash", "h3")
                    .header("HX-Request", "true"),
            ).andExpect(header().string("HX-Redirect", "/pipelines/$PIPELINE?ok=released"))

        // #407: no cascade — the flash stays the layout's generic sentence, nothing is held.
        session.getAttribute(ReleaseFlash.SESSION_KEY) shouldBe null
    }

    @Test
    fun `release - 142 - the editor surface names the cascade in its flash code`() {
        every { pipelines.findDraft(WORKSPACE, any(), PIPELINE) } returns draftDetail() andThen null
        every { pipelines.release(WORKSPACE, PIPELINE, "h3", USER, null, true) } returns
            PipelineReleaseService.Released(
                record(currentVersion = 3),
                draftDetail().copy(status = PipelineVersionStatus.RELEASED),
                "{}",
                templatesReleased = listOf(co.datapipelines.pipeline.TemplateRef("test/a.sql", 2)),
            )
        val session = MockHttpSession()

        mvc
            .perform(
                post("/partials/pipelines/$PIPELINE/lifecycle/release")
                    .session(session)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("bodyHash", "h3")
                    .param("releasePinnedTemplates", "true")
                    .header("HX-Request", "true"),
            ).andExpect(header().string("HX-Redirect", "/pipelines/$PIPELINE?ok=released_with_templates"))

        // #407 — the names are held ONCE for this actor's session, derived from the release's
        // own list; ReleaseFlashAdvice renders them on the next GET of THIS pipeline that
        // carries ok=released_with_templates (ReleaseFlashTest covers the consume, the
        // browser walk the rendered toast). Nothing travels in the redirect URL.
        val held = session.getAttribute(ReleaseFlash.SESSION_KEY) as ReleaseFlash.Held
        held.pipelineId shouldBe PIPELINE
        held.actorId shouldBe USER
        held.templates shouldBe listOf("test/a.sql@2")
    }

    @Test
    fun `release - 416 - a stale dialog hash is passed to the service, never replaced by the fresh draft's, and answers the 409 conflict`() {
        // The draft is at h3 NOW; the dialog that was submitted read h2 earlier.
        every { pipelines.findDraft(WORKSPACE, any(), PIPELINE) } returns draftDetail()
        every { pipelines.release(WORKSPACE, PIPELINE, "h2-stale", USER, null, false) } throws
            DatapipelinesException(
                code = PipelineErrorCodes.Versioning.VERSION_CONFLICT,
                message = "The draft changed since you loaded it.",
                details = mapOf("current_body_hash" to "h3"),
            )

        val response =
            mvc
                .perform(
                    post("/partials/pipelines/$PIPELINE/lifecycle/release")
                        .session(MockHttpSession())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("bodyHash", "h2-stale")
                        .header("HX-Request", "true"),
                ).andExpect(status().isConflict)
                .andExpect(header().string("HX-Retarget", "#toast"))
                .andReturn()
                .response.contentAsString

        response shouldContain "pipeline.version.conflict"
        verify(exactly = 1) { pipelines.release(WORKSPACE, PIPELINE, "h2-stale", USER, null, false) }
        verify(exactly = 0) { pipelines.release(any(), any(), "h3", any(), any(), any()) }
        audit.events shouldBe emptyList()
    }

    @Test
    fun `release - 416 - a missing hash is a 400 at binding and neither the draft read nor the service runs`() {
        mvc
            .perform(
                post("/partials/pipelines/$PIPELINE/lifecycle/release")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .header("HX-Request", "true"),
            ).andExpect(status().isBadRequest)

        verify(exactly = 0) { pipelines.findDraft(any(), any(), any()) }
        verify(exactly = 0) { pipelines.release(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `release - 416 - a hostile hash value reaches the service verbatim and is the conflict, never a 500`() {
        val hostile = "'\"; DROP TABLE pipeline_version; --<script>" + "x".repeat(5000)
        every { pipelines.findDraft(WORKSPACE, any(), PIPELINE) } returns draftDetail()
        every { pipelines.release(WORKSPACE, PIPELINE, hostile, USER, null, false) } throws
            DatapipelinesException(
                code = PipelineErrorCodes.Versioning.VERSION_CONFLICT,
                message = "The draft changed since you loaded it.",
            )

        val response =
            mvc
                .perform(
                    post("/partials/pipelines/$PIPELINE/lifecycle/release")
                        .session(MockHttpSession())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("bodyHash", hostile)
                        .header("HX-Request", "true"),
                ).andExpect(status().isConflict)
                .andReturn()
                .response.contentAsString

        response shouldContain "pipeline.version.conflict"
    }

    @Test
    fun `release - 416 - the no-draft refusal is kept and the posted hash releases nothing`() {
        every { pipelines.findDraft(WORKSPACE, any(), PIPELINE) } returns null

        val response =
            mvc
                .perform(
                    post("/partials/pipelines/$PIPELINE/lifecycle/release")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("bodyHash", "h3")
                        .header("HX-Request", "true"),
                ).andExpect(status().is4xxClientError)
                .andReturn()
                .response.contentAsString

        response shouldContain PipelineErrorCodes.Versioning.NOT_DRAFT
        verify(exactly = 0) { pipelines.release(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `discard - a refusal is Shape C with the REAL status and the §13 code in the toast`() {
        every {
            pipelines.discardVersion(WORKSPACE, PIPELINE, 1, USER)
        } throws
            DatapipelinesException(
                code = PipelineErrorCodes.Versioning.PINNED,
                message = "Version 1 of 'probe' is pinned by 1 live pipeline version(s).",
                details = mapOf("pinned_by" to listOf(mapOf("pipeline" to "nyc/rollup"))),
            )

        val response =
            mvc
                .perform(
                    post("/partials/pipelines/$PIPELINE/lifecycle/discard")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("version", "1")
                        .header("HX-Request", "true"),
                ).andExpect(status().isConflict)
                .andExpect(header().string("HX-Retarget", "#toast"))
                .andExpect(header().string("HX-Reswap", "beforeend"))
                .andReturn()
                .response.contentAsString

        response shouldContain "pipeline.version.pinned"
        // The toast body carries the code + correlation line, and never the button.
        response shouldContain "correlation"
    }

    @Test
    fun `purge - a typed-confirm mismatch is a 400 confirm_mismatch and the service was never called`() {
        val response =
            mvc
                .perform(
                    post("/partials/pipelines/$PIPELINE/lifecycle/purge")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("version", "4")
                        .param("confirm", "v3")
                        .header("HX-Request", "true"),
                ).andExpect(status().isBadRequest)
                .andExpect(header().string("HX-Retarget", "#toast"))
                .andReturn()
                .response.contentAsString

        response shouldContain "pipeline.version.confirm_mismatch"
        // The recording check the MISTAKES entry demands: the guard ran BEFORE the service,
        // so a mismatch must leave the service untouched.
        verify(exactly = 0) { pipelines.purgeVersion(any(), any(), any()) }
    }

    @Test
    fun `purge from the editor - the draft outcome redirects to the canonical workspace with its flash`() {
        // #348 merge follow-up: `/pipelines/{id}/editor` is a compatibility redirect that forwards only
        // `version` and `tab`, so an `ok` sent there was dropped and the toast never rendered.
        every { pipelines.purgeVersion(WORKSPACE, PIPELINE, 4) } returns
            PipelineReleaseService.Purged.Version(executionsDeleted = 0, record = record(currentVersion = 3))

        mvc
            .perform(
                post("/partials/pipelines/$PIPELINE/lifecycle/purge")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("version", "4")
                    .param("confirm", "v4")
                    .header("HX-Request", "true"),
            ).andExpect(header().string("HX-Redirect", "/pipelines/$PIPELINE?ok=draft_purged"))
    }

    @Test
    fun `purge - the entity outcome answers HX-Redirect so the tree loses the leaf`() {
        every { pipelines.purgeVersion(WORKSPACE, PIPELINE, 4) } returns
            PipelineReleaseService.Purged.Entity(executionsDeleted = 2)

        mvc
            .perform(
                post("/partials/pipelines/$PIPELINE/lifecycle/purge")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("version", "4")
                    .param("confirm", "v4")
                    .header("HX-Request", "true"),
            ).andExpect(header().string("HX-Redirect", "/pipelines?ok=entity_purged"))

        audit.events.shouldBe(listOf("pipeline.version.purged"))
    }

    @Test
    fun `purge entity - the offer flag rides the form, the confirm names the pipeline`() {
        every { pipelines.findRecord(WORKSPACE, any(), PIPELINE) } returns record(currentVersion = null)
        every { pipelines.purgeEntity(WORKSPACE, PIPELINE, includeExclusiveDraftTemplates = true) } returns
            PipelineService.EntityPurgeResult(1, listOf("test/only_here.sql"), true)

        mvc
            .perform(
                post("/partials/pipelines/$PIPELINE/lifecycle/purge-entity")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("include_exclusive", "true")
                    .param("confirm", "nyc/mobility/probe")
                    .header("HX-Request", "true"),
            ).andExpect(header().string("HX-Redirect", "/pipelines?ok=entity_purged"))

        audit.events.shouldBe(listOf("pipeline.purged"))
    }

    @Test
    fun `purge entity - a confirm that names anything else never reaches the service`() {
        every { pipelines.findRecord(WORKSPACE, any(), PIPELINE) } returns record(currentVersion = null)
        mvc
            .perform(
                post("/partials/pipelines/$PIPELINE/lifecycle/purge-entity")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("confirm", "some/other_name")
                    .header("HX-Request", "true"),
            ).andExpect(status().isBadRequest)

        verify(exactly = 0) { pipelines.purgeEntity(any(), any(), any()) }
    }

    // ------------------------------------------------------------------ #395: the workspace's legs

    @Test
    fun `#395 - discard from the workspace answers HX-Redirect onto its Versions tab, after the same service call and audit`() {
        every { pipelines.discardVersion(WORKSPACE, PIPELINE, 1, USER) } returns
            PipelineService.DiscardResult(record(currentVersion = 1), record(currentVersion = 2), draftDetail())

        mvc
            .perform(
                post("/partials/pipelines/$PIPELINE/lifecycle/discard")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("version", "1")
                    .header("HX-Request", "true"),
            ).andExpect(status().isOk)
            .andExpect(header().string("HX-Redirect", "/pipelines/$PIPELINE?tab=versions&ok=discarded"))
        verify(exactly = 1) { pipelines.discardVersion(WORKSPACE, PIPELINE, 1, USER) }
        audit.events shouldBe listOf("pipeline.version.discarded")
    }

    @Test
    fun `#395 - restore from the workspace answers HX-Redirect onto its Versions tab`() {
        every { pipelines.restoreVersion(WORKSPACE, PIPELINE, 1) } returns record(currentVersion = 2)

        mvc
            .perform(
                post("/partials/pipelines/$PIPELINE/lifecycle/restore")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("version", "1")
                    .header("HX-Request", "true"),
            ).andExpect(status().isOk)
            .andExpect(header().string("HX-Redirect", "/pipelines/$PIPELINE?tab=versions&ok=restored"))
        verify(exactly = 1) { pipelines.restoreVersion(WORKSPACE, PIPELINE, 1) }
    }

    @Test
    fun `#395 - switch from the workspace answers HX-Redirect onto its Versions tab`() {
        every { pipelines.switchCurrent(WORKSPACE, PIPELINE, 1) } returns record(currentVersion = 1)

        mvc
            .perform(
                post("/partials/pipelines/$PIPELINE/lifecycle/switch")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("version", "1")
                    .header("HX-Request", "true"),
            ).andExpect(header().string("HX-Redirect", "/pipelines/$PIPELINE?tab=versions&ok=switched"))
        verify(exactly = 1) { pipelines.switchCurrent(WORKSPACE, PIPELINE, 1) }
        // #401: the no-`from` POST is the SAME redirect now — the explorer Shape A leg is gone.
    }

    @Test
    fun `#395 - the switch dialog honours from=editor - the Versions tab's link always sent it`() {
        every { dialogs.switch(WORKSPACE, PIPELINE) } returns
            PipelineLifecycleDialogModel.SwitchDialog(
                id = PIPELINE,
                name = "test/pipeline",
                currentVersion = 2,
                options =
                    listOf(
                        PipelineLifecycleDialogModel.SwitchOption(1, PipelineVersionStatus.RELEASED, isCurrent = false, eligible = true),
                    ),
            )

        mvc
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get("/partials/pipelines/$PIPELINE/lifecycle/switch")
                    .param("version", "1")
                    .param("from", "editor")
                    .header("HX-Request", "true"),
            ).andExpect(status().isOk)
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                    .model()
                    .attribute("from", "editor"),
            )

        // #401 — no `from` at all is the editor too: the explorer default is gone, and an
        // unknown `from` lands the editor shape rather than a 500.
        mvc
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get("/partials/pipelines/$PIPELINE/lifecycle/switch")
                    .param("version", "1")
                    .param("from", "explorer")
                    .header("HX-Request", "true"),
            ).andExpect(status().isOk)
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                    .model()
                    .attribute("from", "editor"),
            )
    }

    // ------------------------------------------------------------------ fixtures

    private fun record(currentVersion: Int?) =
        PipelineRecord(
            id = PIPELINE,
            name = "nyc/mobility/probe",
            displayName = "probe",
            description = "",
            ownerId = USER,
            currentVersion = currentVersion,
            createdAt = Instant.parse("2026-09-08T10:00:00Z"),
            updatedAt = Instant.parse("2026-09-09T10:00:00Z"),
        )

    private fun draftDetail() =
        PipelineVersionDetail(
            pipelineId = PIPELINE,
            version = 3,
            status = PipelineVersionStatus.DRAFT,
            bodyHash = "h3",
            createdAt = Instant.parse("2026-09-08T10:00:00Z"),
            createdBy = USER,
        )

    /** The recording fake the MISTAKES entry asks for: effects asserted, absence observable. */
    internal class RecordingAudit : AuditEventSink {
        val events = mutableListOf<String>()
        val details = mutableListOf<Map<String, Any?>>()

        override fun log(
            event: String,
            userId: UUID?,
            keyId: String?,
            sourceIp: String?,
            userAgent: String?,
            details: Map<String, Any?>,
        ) {
            events.add(event)
            this.details.add(details)
        }
    }

    private companion object {
        val WORKSPACE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000010")
        val PIPELINE: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val USER: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
