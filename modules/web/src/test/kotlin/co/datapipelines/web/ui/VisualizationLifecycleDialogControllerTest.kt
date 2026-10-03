package co.datapipelines.web.ui

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.PointerMove
import co.datapipelines.visualization.Purged
import co.datapipelines.visualization.Switched
import co.datapipelines.visualization.VersionMoved
import co.datapipelines.visualization.VisualizationErrorCodes
import co.datapipelines.visualization.VisualizationReleased
import co.datapipelines.visualization.VisualizationService
import co.datapipelines.web.ui.VisualizationUiFixtures.detail
import co.datapipelines.web.ui.VisualizationUiFixtures.version
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import java.util.UUID

/**
 * #399 — the visualization lifecycle dialogs' POSTs: each calls the SAME [VisualizationService] verb the REST route
 * wires and answers `HX-Redirect` onto the workspace tab the dialog came from — the URL built from the path id, the
 * CLOSED tab enum and a constant flash code, so a hostile `from` lands on Versions and is never echoed. A purge that
 * took the entity lands on the catalog. The typed confirm is checked BEFORE the service (a mismatch purges nothing);
 * an API key is refused `auth.session.required` before anything is read; every audit row is written AFTER the verb
 * returned, the release's cascaded template rows first (#332) — read off a RECORDING sink, so a missing row is red.
 */
class VisualizationLifecycleDialogControllerTest {
    private val workspaceId = UUID.randomUUID()
    private val vizId = UUID.randomUUID()
    private val visualizations = mockk<VisualizationService>()
    private val audit = RecordingAudit()
    private val dialogs = mockk<VisualizationLifecycleDialogModel>()
    private val controller = VisualizationLifecycleDialogController(visualizations, dialogs, audit)

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate(method: AuthMethod = AuthMethod.OIDC) {
        val principal =
            AuthenticatedPrincipal(
                UUID.randomUUID(),
                "u@d.p",
                "User",
                method,
                workspace = WorkspaceContext(workspaceId, "acme", WorkspaceRole.AUTHOR),
            )
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private fun draftWorking(version: Int = 2) {
        every { visualizations.findWorking(workspaceId, ReadLens.Everything, vizId) } returns
            version(vizId, workspaceId, version, PipelineVersionStatus.DRAFT, currentVersion = 1)
    }

    private fun location(answer: org.springframework.http.ResponseEntity<String>): String? = answer.headers.getFirst("HX-Redirect")

    // ------------------------------------------------------------------ release

    @Test
    fun `release passes the draft's hash and the consent, audits the cascaded template first, and returns to the tab`() {
        authenticate()
        draftWorking()
        val released = version(vizId, workspaceId, 2, PipelineVersionStatus.RELEASED, currentVersion = 2)
        every { visualizations.release(workspaceId, vizId, "hash-2", any(), true) } returns
            VisualizationReleased(released, listOf(TemplateRef("acme/templates/t", 3)))

        val answer = controller.release(vizId, releasePinnedTemplates = true, from = "preview")

        location(answer) shouldBe "/visualizations/$vizId?tab=preview&ok=released_with_templates"
        audit.events.map { it.first } shouldBe listOf("template.version.released", "visualization.version.released")
        audit.events.last().second["visualization_name"] shouldBe VisualizationUiFixtures.NAME
    }

    @Test
    fun `a release without a cascade says released, and no consent is sent unless posted`() {
        authenticate()
        draftWorking()
        every { visualizations.release(workspaceId, vizId, "hash-2", any(), false) } returns
            VisualizationReleased(version(vizId, workspaceId, 2, PipelineVersionStatus.RELEASED), emptyList())

        location(controller.release(vizId, releasePinnedTemplates = false, from = null)) shouldBe
            "/visualizations/$vizId?tab=versions&ok=released"
        audit.events.map { it.first } shouldBe listOf("visualization.version.released")
    }

    @Test
    fun `a release with no draft is refused before the service, and a service refusal audits nothing`() {
        authenticate()
        every { visualizations.findWorking(workspaceId, ReadLens.Everything, vizId) } returns
            version(vizId, workspaceId, 1, PipelineVersionStatus.RELEASED, currentVersion = 1)
        assertThrows<DatapipelinesException> { controller.release(vizId, false, null) }.code shouldBe
            VisualizationErrorCodes.VERSION_NOT_DRAFT

        draftWorking()
        every { visualizations.release(workspaceId, vizId, "hash-2", any(), false) } throws
            DatapipelinesException(VisualizationErrorCodes.RELEASE_TESTS_MISSING, "no cases")
        assertThrows<DatapipelinesException> { controller.release(vizId, false, null) }
        audit.events.shouldBeEmpty()
    }

    // ------------------------------------------------------------------ the redirect whitelist

    @Test
    fun `the redirect's tab comes from the closed enum - a hostile from lands on Versions and is never echoed`() {
        for (hostile in listOf("//evil.example", "https://evil.example", "versions&ok=x", "<script>", "", null)) {
            VisualizationLifecycleDialogController.workspaceTab(vizId, hostile, "discarded") shouldBe
                "/visualizations/$vizId?tab=versions&ok=discarded"
        }
        for (tab in VisualizationWorkspaceModel.Tab.entries) {
            VisualizationLifecycleDialogController.workspaceTab(vizId, tab.wire, "restored") shouldBe
                "/visualizations/$vizId?tab=${tab.wire}&ok=restored"
        }
    }

    // ------------------------------------------------------------------ purges

    @Test
    fun `a mismatched typed confirm refuses BEFORE the service runs, on every purge`() {
        authenticate()
        draftWorking()

        for (typed in listOf(null, "", "v3", "V2", " v2")) {
            assertThrows<DatapipelinesException> { controller.purgeDraft(vizId, 2, typed, null) }.code shouldBe
                VisualizationErrorCodes.VERSION_CONFLICT
            assertThrows<DatapipelinesException> { controller.purgeVersion(vizId, 2, typed, null) }.code shouldBe
                VisualizationErrorCodes.VERSION_CONFLICT
        }
        assertThrows<DatapipelinesException> { controller.purgeEntity(vizId, "acme/charts/other") }.code shouldBe
            VisualizationErrorCodes.VERSION_CONFLICT

        verify(exactly = 0) { visualizations.purgeDraft(any(), any(), any()) }
        verify(exactly = 0) { visualizations.purgeVersion(any(), any(), any(), any()) }
        verify(exactly = 0) { visualizations.purgeEntity(any(), any()) }
        audit.events.shouldBeEmpty()
    }

    @Test
    fun `purge draft purges at the fresh hash and returns to the tab, or to the catalog when it took the entity`() {
        authenticate()
        draftWorking()
        every { visualizations.purgeDraft(workspaceId, vizId, "hash-2") } returns Purged.Version

        location(controller.purgeDraft(vizId, 2, "v2", "versions")) shouldBe
            "/visualizations/$vizId?tab=versions&ok=visualization_draft_purged"
        audit.events.single().second["scope"] shouldBe "version"

        every { visualizations.purgeDraft(workspaceId, vizId, "hash-2") } returns Purged.Entity
        location(controller.purgeDraft(vizId, 2, "v2", "versions")) shouldBe "/visualizations?ok=visualization_purged"
    }

    @Test
    fun `purge draft refuses a version that is not the working draft`() {
        authenticate()
        draftWorking(version = 3)

        assertThrows<DatapipelinesException> { controller.purgeDraft(vizId, 2, "v2", null) }.code shouldBe
            VisualizationErrorCodes.VERSION_NOT_DRAFT
        verify(exactly = 0) { visualizations.purgeDraft(any(), any(), any()) }
    }

    @Test
    fun `purge version and purge entity call their verbs and audit the scope`() {
        authenticate()
        draftWorking()
        every { visualizations.purgeVersion(workspaceId, vizId, 2) } returns Purged.Version
        every { visualizations.purgeEntity(workspaceId, vizId) } returns Purged.Entity

        location(controller.purgeVersion(vizId, 2, "v2", "overview")) shouldBe
            "/visualizations/$vizId?tab=overview&ok=visualization_draft_purged"
        location(controller.purgeEntity(vizId, VisualizationUiFixtures.NAME)) shouldBe "/visualizations?ok=visualization_purged"
        audit.events.map { it.first } shouldBe listOf("visualization.version.purged", "visualization.purged")
        audit.events.last().second["visualization_name"] shouldBe VisualizationUiFixtures.NAME
    }

    // ------------------------------------------------------------------ discard / restore / switch

    @Test
    fun `discard, restore and switch answer the tab with their own flash codes and audit the pointer move`() {
        authenticate()
        draftWorking()
        every { visualizations.discardVersion(workspaceId, vizId, 1, any()) } returns
            VersionMoved(detail(vizId, 1, PipelineVersionStatus.DISCARDED), PointerMove(1, null))
        every { visualizations.restoreVersion(workspaceId, vizId, 1) } returns
            VersionMoved(detail(vizId, 1, PipelineVersionStatus.RELEASED), PointerMove(null, 1))
        every { visualizations.switchCurrent(workspaceId, vizId, 1) } returns Switched(VisualizationUiFixtures.NAME, PointerMove(2, 1))

        location(controller.discard(vizId, 1, "versions")) shouldBe "/visualizations/$vizId?tab=versions&ok=discarded"
        location(controller.restore(vizId, 1, "versions")) shouldBe "/visualizations/$vizId?tab=versions&ok=restored"
        location(controller.switchCurrent(vizId, 1, "versions")) shouldBe "/visualizations/$vizId?tab=versions&ok=visualization_switched"

        audit.events.map { it.first } shouldBe
            listOf("visualization.version.discarded", "visualization.version.restored", "visualization.current_switched")
        audit.events[0].second["current_version_after"] shouldBe null
        audit.events[1].second["current_version_after"] shouldBe 1
        audit.events[2].second["to"] shouldBe 1
    }

    // ------------------------------------------------------------------ session only

    @Test
    fun `an API key is refused session-required on every verb, before anything is read`() {
        authenticate(AuthMethod.API_KEY)

        val calls: List<() -> Any> =
            listOf(
                { controller.release(vizId, false, null) },
                { controller.purgeDraft(vizId, 2, "v2", null) },
                { controller.purgeVersion(vizId, 2, "v2", null) },
                { controller.discard(vizId, 1, null) },
                { controller.restore(vizId, 1, null) },
                { controller.switchCurrent(vizId, 1, null) },
                { controller.purgeEntity(vizId, VisualizationUiFixtures.NAME) },
                { controller.releaseDialog(ExtendedModelMap(), vizId, null) },
                { controller.switchDialog(ExtendedModelMap(), vizId, null, null) },
            )
        for (call in calls) {
            assertThrows<DatapipelinesException> { call() }.code shouldBe PipelineErrorCodes.Auth.SESSION_REQUIRED
        }
        verify(exactly = 0) { visualizations.findWorking(any(), any(), any()) }
        audit.events.shouldBeEmpty()
    }

    @Test
    fun `a dialog GET carries the whitelisted from and the role stamp`() {
        authenticate()
        every { dialogs.switch(workspaceId, vizId) } returns
            VisualizationLifecycleDialogModel.SwitchDialog(vizId, VisualizationUiFixtures.NAME, 1, emptyList())

        val model = ExtendedModelMap()
        controller.switchDialog(model, vizId, 2, "<script>") shouldBe "partials/visualization-lifecycle-switch"

        model["from"] shouldBe "versions"
        model["preselect"] shouldBe 2
        model["canAuthor"] shouldBe true
    }

    private class RecordingAudit : AuditEventSink {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()

        override fun log(
            event: String,
            userId: UUID?,
            keyId: String?,
            sourceIp: String?,
            userAgent: String?,
            details: Map<String, Any?>,
        ) {
            events += event to details
        }
    }
}
