package co.datapipelines.web.ui

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.DatasourceRegistry
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineReleaseService
import co.datapipelines.pipeline.PipelineRepository
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
 * - happy ⇒ Shape A: the re-rendered detail, the toast that names what happened, and the
 *   `HX-Trigger: lifecycle-changed` payload the tree badge reads;
 * - refusal ⇒ Shape C through [UiExceptionHandler]: the REAL 4xx the catalog assigns, the
 *   `HX-Retarget`/`HX-Reswap` pair, and the §13 code in the toast body;
 * - entity purge ⇒ `HX-Redirect` (the tree must lose the leaf);
 * - typed-confirm mismatch ⇒ 400 with `pipeline.version.confirm_mismatch` and the service
 *   NEVER CALLED — a recording verification, not a strict mock (the MISTAKES entry: a strict
 *   mock makes a missing call unobservable by inverting the double).
 */
class PipelineLifecycleDialogControllerTest {
    private val pipelines = mockk<PipelineService>()
    private val repository = mockk<PipelineRepository>()
    private val dialogs = mockk<PipelineLifecycleDialogModel>()
    private val audit = RecordingAudit()
    private val browse =
        PipelineBrowseModel(
            pipelines,
            repository,
            mockk(relaxed = true),
            mockk(relaxed = true),
            DatasourceRegistry.EMPTY,
            mockk(relaxed = true),
            mockk(relaxed = true),
            AuthoringGuard(enabled = true),
        )

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
                .standaloneSetup(PipelineLifecycleDialogController(pipelines, dialogs, browse, audit))
                .setControllerAdvice(UiExceptionHandler())
                .build()
    }

    @AfterEach
    fun clear() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `release - Shape A with the toast, the detail region and the lifecycle-changed payload`() {
        happyPathReads(record(currentVersion = 3))
        // The draft exists FOR the release and is GONE after it — the payload's hasDraft
        // reads the post-state, and a mock that keeps answering "draft" would lie about it.
        every { pipelines.findDraft(WORKSPACE, PIPELINE) } returns draftDetail() andThen null
        every { pipelines.release(WORKSPACE, PIPELINE, "h3", USER) } returns
            PipelineReleaseService.Released(
                record = record(currentVersion = 3),
                version = draftDetail().copy(status = PipelineVersionStatus.RELEASED),
                bodyJson = "{}",
            )

        // Standalone MockMvc renders no templates; the Shape A CONTRACT here is the view the
        // region re-renders, the toast riding its model, and the trigger header — the markup
        // itself is the render suite's (PipelineLifecycleDialogRenderTest + the wrapper's).
        mvc
            .perform(
                post("/partials/pipelines/$PIPELINE/lifecycle/release")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .header("HX-Request", "true"),
            ).andExpect(status().isOk)
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                    .view()
                    .name("partials/pipeline-lifecycle-applied"),
            ).andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                    .model()
                    .attribute("lifecycleToastTitle", "Released v3"),
            ).andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.model().attribute(
                    "lifecycleToastMessage",
                    "v3 is the current version now, and it is locked.",
                ),
            )
        // T187 — released on the dialog surface is audited like every other lifecycle verb.
        audit.events shouldBe listOf("pipeline.version.released")
    }

    /**
     * 114 — Shape A's re-rendered detail carries the ROLE attributes its verbs are guarded on.
     *
     * The re-render is the third caller of `PipelineBrowseModel.fillDetail`, and it was the one
     * that forgot: the pane came back from a Release with no role attributes at all, so every
     * verb on it — Release, Switch, Discard, Purge — silently vanished until the next selection
     * re-fetched it. The stamp lives in `fillDetail` now, which is why this asserts the MODEL
     * rather than the controller: whoever calls it next gets the same answer without knowing to.
     */
    @Test
    fun `release - the re-rendered detail carries the role attributes its verbs are guarded on`() {
        happyPathReads(record(currentVersion = 3))
        every { pipelines.findDraft(WORKSPACE, PIPELINE) } returns draftDetail() andThen null
        every { pipelines.release(WORKSPACE, PIPELINE, "h3", USER) } returns
            PipelineReleaseService.Released(
                record = record(currentVersion = 3),
                version = draftDetail().copy(status = PipelineVersionStatus.RELEASED),
                bodyJson = "{}",
            )

        val result =
            mvc
                .perform(
                    post("/partials/pipelines/$PIPELINE/lifecycle/release")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("HX-Request", "true"),
                ).andExpect(status().isOk)
                .andReturn()

        val model = result.modelAndView?.model.orEmpty()
        listOf("canRead", "canExecute", "canAuthor", "canPromote", "canAdminWorkspace", "isSuperAdmin", "roleLabel")
            .forEach { attribute -> model.keys shouldContain attribute }
    }

    @Test
    fun `release - the HX-Trigger payload carries the POST's own working-version facts`() {
        happyPathReads(record(currentVersion = 3))
        every { pipelines.findDraft(WORKSPACE, PIPELINE) } returns draftDetail() andThen null
        every { pipelines.release(WORKSPACE, PIPELINE, "h3", USER) } returns
            PipelineReleaseService.Released(record(currentVersion = 3), draftDetail().copy(status = PipelineVersionStatus.RELEASED), "{}")

        mvc
            .perform(
                post("/partials/pipelines/$PIPELINE/lifecycle/release")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .header("HX-Request", "true"),
            ).andExpect(
                header().string(
                    "HX-Trigger",
                    "{\"lifecycle-changed\":{\"leafId\":\"$PIPELINE\",\"workingVersion\":3,\"hasDraft\":false}}",
                ),
            )
    }

    @Test
    fun `release - the editor surface answers HX-Redirect, the reload the editor needs`() {
        happyPathReads(record(currentVersion = 3))
        every { pipelines.findDraft(WORKSPACE, PIPELINE) } returns draftDetail() andThen null
        every { pipelines.release(WORKSPACE, PIPELINE, "h3", USER) } returns
            PipelineReleaseService.Released(record(currentVersion = 3), draftDetail().copy(status = PipelineVersionStatus.RELEASED), "{}")

        mvc
            .perform(
                post("/partials/pipelines/$PIPELINE/lifecycle/release")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("from", "editor")
                    .header("HX-Request", "true"),
            ).andExpect(header().string("HX-Redirect", "/pipelines/$PIPELINE/editor?ok=released"))
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
        every { pipelines.findRecord(WORKSPACE, PIPELINE) } returns record(currentVersion = null)
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
        every { pipelines.findRecord(WORKSPACE, PIPELINE) } returns record(currentVersion = null)
        mvc
            .perform(
                post("/partials/pipelines/$PIPELINE/lifecycle/purge-entity")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("confirm", "some/other_name")
                    .header("HX-Request", "true"),
            ).andExpect(status().isBadRequest)

        verify(exactly = 0) { pipelines.purgeEntity(any(), any(), any()) }
    }

    @Test
    fun `switch - Shape A names the served version in the toast`() {
        every { pipelines.switchCurrent(WORKSPACE, PIPELINE, 1) } returns record(currentVersion = 1)
        happyPathReads(record(currentVersion = 1))

        // Standalone MockMvc renders no templates; the Shape A CONTRACT here is the view the
        // region re-renders, the toast riding its model, and the trigger header — the markup
        // itself is the render suite's (PipelineLifecycleDialogRenderTest + the wrapper's).
        mvc
            .perform(
                post("/partials/pipelines/$PIPELINE/lifecycle/switch")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("version", "1")
                    .header("HX-Request", "true"),
            ).andExpect(status().isOk)
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                    .view()
                    .name("partials/pipeline-lifecycle-applied"),
            ).andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                    .model()
                    .attribute("lifecycleToastTitle", "Switched to v1"),
            ).andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.model().attribute(
                    "lifecycleToastMessage",
                    "Endpoints published on this pipeline serve v1 after this.",
                ),
            )
    }

    // ------------------------------------------------------------------ fixtures

    /** The reads `applied` performs through the browse model and its own re-reads. */
    private fun happyPathReads(record: PipelineRecord) {
        every { repository.findById(WORKSPACE, PIPELINE) } returns record
        every { pipelines.findRecord(WORKSPACE, PIPELINE) } returns record
        every { pipelines.findWorking(WORKSPACE, PIPELINE) } returns null
        every { pipelines.listVersions(WORKSPACE, PIPELINE) } returns emptyList()
        every { repository.listVersions(WORKSPACE, PIPELINE) } returns emptyList()
        every { repository.findDraftDetail(WORKSPACE, PIPELINE) } returns null
        every { pipelines.findDraft(WORKSPACE, PIPELINE) } returns null
    }

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

        override fun log(
            event: String,
            userId: UUID?,
            keyId: String?,
            sourceIp: String?,
            userAgent: String?,
            details: Map<String, Any?>,
        ) {
            events.add(event)
        }
    }

    private companion object {
        val WORKSPACE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000010")
        val PIPELINE: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val USER: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
