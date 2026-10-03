package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.TemplateVersionStatuses
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.ArtifactFolder
import co.datapipelines.visualization.ArtifactJson
import co.datapipelines.visualization.ArtifactPage
import co.datapipelines.visualization.ArtifactRef
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.DashboardBody
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.PreviewCase
import co.datapipelines.visualization.PreviewCaseEvaluator
import co.datapipelines.visualization.ScreenshotView
import co.datapipelines.visualization.TestRunStatus
import co.datapipelines.visualization.TestRunView
import co.datapipelines.visualization.VisualizationErrorCodes
import co.datapipelines.visualization.VisualizationOccurrence
import co.datapipelines.visualization.VisualizationService
import co.datapipelines.visualization.VisualizationTestSessionService
import co.datapipelines.web.ui.VisualizationUiFixtures.AT
import co.datapipelines.web.ui.VisualizationUiFixtures.authenticate
import co.datapipelines.web.ui.VisualizationUiFixtures.detail
import co.datapipelines.web.ui.VisualizationUiFixtures.version
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * #399 — the Visualizations partials: the sidebar tree and search (the nav stamp, the level/list dispatch), and
 * the workspace's five lazy tabs. Every read rides the caller's lens; a version tab needs its version NAMED (a
 * missing or malformed one is the non-echoing 400) and re-admits it (a DISCARDED or lens-hidden version is the
 * family's 404, never another body); Evidence lists only the runs of versions the caller may see, capped at
 * 100, and probes the screenshot the route would serve; Used-by reads `pinnedBy` through the DASHBOARD lens.
 */
class VisualizationPartialControllerTest {
    private val workspaceId = UUID.randomUUID()
    private val vizId = UUID.randomUUID()
    private val visualizations = mockk<VisualizationService>()
    private val previewCases = mockk<PreviewCaseEvaluator>()
    private val sessions = mockk<VisualizationTestSessionService>()
    private val dashboards = mockk<DashboardService>()
    private val templateStatuses = TemplateVersionStatuses { _, _, _ -> PipelineVersionStatus.RELEASED }
    private var view: LensedView = LensedView.EVERYTHING

    private val workspaceModel = VisualizationWorkspaceModel(visualizations)
    private val controller =
        VisualizationPartialController(
            browse = VisualizationBrowseModel(visualizations),
            tabs = VisualizationTabModel(visualizations, workspaceModel, previewCases, sessions, dashboards, templateStatuses),
            lens = PromoterLens { view },
        )

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun twoVersions() {
        val v1 = version(vizId, workspaceId, 1, PipelineVersionStatus.RELEASED, currentVersion = 1)
        val v2 = version(vizId, workspaceId, 2, PipelineVersionStatus.DRAFT, currentVersion = 1)
        every { visualizations.findWorking(workspaceId, any(), vizId) } returns v2
        every { visualizations.findVersion(workspaceId, any(), vizId, 1) } returns v1
        every { visualizations.findVersion(workspaceId, any(), vizId, 2) } returns v2
        every { visualizations.listVersions(workspaceId, any(), vizId) } returns
            listOf(detail(vizId, 2, PipelineVersionStatus.DRAFT), detail(vizId, 1, PipelineVersionStatus.RELEASED))
    }

    // ------------------------------------------------------------------ the tree

    @Test
    fun `the tree stamps the nav header, renders a level for a prefix and the nav list for q`() {
        authenticate(workspaceId, WorkspaceRole.VIEWER)
        every { visualizations.listChildFolders(workspaceId, ReadLens.Everything, null) } returns
            listOf(ArtifactFolder("acme", "acme", 1))

        val response = MockHttpServletResponse()
        val root = ExtendedModelMap()
        controller.tree(root, response, prefix = null, q = null, offset = null) shouldBe
            VisualizationBrowseModel.WRAPPER_VIEW
        response.getHeader(VisualizationBrowseModel.NAV_STAMP_HEADER) shouldBe "acme|all"
        root["levelId"] shouldBe VisualizationBrowseModel.NAV_ROOT_ID
        root["visualizations"] shouldBe emptyList<Any>()

        every { visualizations.search(workspaceId, ReadLens.Everything, "rev", 0, VisualizationBrowseModel.PAGE_SIZE) } returns
            ArtifactPage(emptyList(), 0)
        val searched = ExtendedModelMap()
        controller.tree(searched, MockHttpServletResponse(), prefix = null, q = "rev", offset = null)
        searched["scope"] shouldBe "nav"
        searched["rootId"] shouldBe VisualizationBrowseModel.NAV_ROOT_ID
    }

    @Test
    fun `a lensed caller's tree is stamped lens and read through its visualization lens`() {
        authenticate(workspaceId, WorkspaceRole.PROMOTER)
        val narrowed = ReadLens.Only(setOf(VisualizationUiFixtures.NAME))
        view = LensedView(ReadLens.Everything, ReadLens.Everything, visualizations = narrowed)
        every { visualizations.listChildFolders(workspaceId, narrowed, "acme/charts") } returns emptyList()
        every { visualizations.listChildren(workspaceId, narrowed, "acme/charts", 0, VisualizationBrowseModel.PAGE_SIZE) } returns
            emptyList()
        every { visualizations.countChildren(workspaceId, narrowed, "acme/charts") } returns 0

        val response = MockHttpServletResponse()
        controller.tree(ExtendedModelMap(), response, prefix = "acme/charts", q = null, offset = null) shouldBe
            VisualizationBrowseModel.LEVEL_VIEW

        response.getHeader(VisualizationBrowseModel.NAV_STAMP_HEADER) shouldBe "acme|lens"
        verify(exactly = 0) { visualizations.listChildren(any(), ReadLens.Everything, any(), any(), any()) }
    }

    @Test
    fun `an illegal prefix is an ordinary empty level, never a pattern match`() {
        authenticate(workspaceId, WorkspaceRole.VIEWER)

        val model = ExtendedModelMap()
        controller.tree(model, MockHttpServletResponse(), prefix = "%_/../x", q = null, offset = null)

        model["folders"] shouldBe emptyList<Any>()
        model["visualizations"] shouldBe emptyList<Any>()
        verify(exactly = 0) { visualizations.listChildFolders(any(), any(), any()) }
    }

    // ------------------------------------------------------------------ the version tabs

    @Test
    fun `a version tab without a version, or with a malformed one, is the non-echoing 400`() {
        authenticate(workspaceId, WorkspaceRole.VIEWER)
        twoVersions()

        for (raw in listOf(null, "", "x<script>", "0")) {
            val error = assertThrows<ResponseStatusException> { controller.preview(ExtendedModelMap(), vizId, raw) }
            error.statusCode shouldBe HttpStatus.BAD_REQUEST
            error.reason shouldBe VisualizationWorkspaceModel.BAD_VERSION
            assertThrows<ResponseStatusException> { controller.overview(ExtendedModelMap(), vizId, raw) }
        }
        val echoed = assertThrows<ResponseStatusException> { controller.versions(ExtendedModelMap(), vizId, "x<script>") }
        echoed.message shouldNotContain "script"
    }

    @Test
    fun `a DISCARDED or lens-hidden version is the family's 404 on every version tab`() {
        authenticate(workspaceId, WorkspaceRole.VIEWER)
        twoVersions()
        every { visualizations.findVersion(workspaceId, any(), vizId, 1) } returns
            version(vizId, workspaceId, 1, PipelineVersionStatus.DISCARDED)
        every { visualizations.findVersion(workspaceId, any(), vizId, 9) } returns null

        for (v in listOf("1", "9")) {
            assertThrows<DatapipelinesException> { controller.preview(ExtendedModelMap(), vizId, v) }.code shouldBe
                VisualizationErrorCodes.NOT_FOUND
            assertThrows<DatapipelinesException> { controller.overview(ExtendedModelMap(), vizId, v) }.code shouldBe
                VisualizationErrorCodes.NOT_FOUND
        }
    }

    @Test
    fun `the preview pane carries the cases as text labels and the capability page's JSON block`() {
        authenticate(workspaceId, WorkspaceRole.VIEWER)
        twoVersions()
        val case = PreviewCase("twelve months", emptyMap(), listOf(mapOf("x" to 1)), null, emptyList())
        every { previewCases.cases(workspaceId, any()) } returns listOf(case)

        val model = ExtendedModelMap()
        controller.preview(model, vizId, "2") shouldBe VisualizationTabModel.PREVIEW_VIEW

        model["version"] shouldBe 2
        @Suppress("UNCHECKED_CAST")
        (model["cases"] as List<VisualizationTabModel.CaseView>).single().name shouldBe "twelve months"
        val json = model["previewJson"] as String
        json shouldContain "\"twelve months\""
        // The script-safe encoding: no raw angle bracket can reach the script element.
        json shouldNotContain "<"
    }

    @Test
    fun `the overview reads the transform pin's status through the release cascade's port`() {
        authenticate(workspaceId, WorkspaceRole.VIEWER)
        twoVersions()

        val model = ExtendedModelMap()
        controller.overview(model, vizId, "1") shouldBe VisualizationTabModel.OVERVIEW_VIEW

        model["name"] shouldBe VisualizationUiFixtures.NAME
        model["bodyHash"] shouldBe "hash-1"
        model["bundle"] shouldBe "2d"
        model["transform"] shouldBe null
        model["caseCount"] shouldBe 0
    }

    // ------------------------------------------------------------------ evidence

    private fun run(
        version: Int,
        status: TestRunStatus = TestRunStatus.GREEN,
    ) = TestRunView(
        runId = UUID.randomUUID(),
        sessionId = UUID.randomUUID(),
        version = version,
        bodyHash = "hash-$version",
        status = status,
        startedAt = AT,
        completedAt = AT,
        expiresAt = AT,
        cases = ArtifactJson.mapper.readTree("""[{"name":"c"}]"""),
        environment = null,
        mechanical = ArtifactJson.mapper.readTree("""{"ok":true}"""),
        previewRevoked = false,
        uploadCapability = null,
    )

    @Test
    fun `evidence lists only the runs of versions the caller may see, capped at 100`() {
        authenticate(workspaceId, WorkspaceRole.PROMOTER)
        val narrowed = ReadLens.Only(setOf(VisualizationUiFixtures.NAME))
        view = LensedView(ReadLens.Everything, ReadLens.Everything, visualizations = narrowed)
        every { visualizations.listVersions(workspaceId, narrowed, vizId) } returns listOf(detail(vizId, 1, PipelineVersionStatus.RELEASED))
        every { sessions.runs(workspaceId, vizId) } returns List(120) { run(1) } + run(2)

        val model = ExtendedModelMap()
        controller.evidence(model, vizId) shouldBe VisualizationTabModel.EVIDENCE_VIEW

        @Suppress("UNCHECKED_CAST")
        val rows = model["runs"] as List<VisualizationTabModel.RunRow>
        rows shouldHaveSize VisualizationTabModel.RUNS_CAP
        rows.all { it.version == 1 } shouldBe true
        rows.first().mechanical shouldBe "passed"
        model["runsCap"] shouldBe 100
    }

    @Test
    fun `a visualization the lens hides has no evidence - the 404`() {
        authenticate(workspaceId, WorkspaceRole.PROMOTER)
        every { visualizations.listVersions(workspaceId, any(), vizId) } returns emptyList()

        assertThrows<DatapipelinesException> { controller.evidence(ExtendedModelMap(), vizId) }.code shouldBe
            VisualizationErrorCodes.NOT_FOUND
        verify(exactly = 0) { sessions.runs(any(), any()) }
    }

    @Test
    fun `a run detail probes the screenshot the route would serve - present, absent, or the run hidden`() {
        authenticate(workspaceId, WorkspaceRole.VIEWER)
        every { visualizations.listVersions(workspaceId, any(), vizId) } returns listOf(detail(vizId, 1, PipelineVersionStatus.RELEASED))
        val withShot = run(1)
        val without = run(1)
        val hidden = run(7)
        every { sessions.runById(workspaceId, vizId, withShot.runId) } returns withShot
        every { sessions.runById(workspaceId, vizId, without.runId) } returns without
        every { sessions.runById(workspaceId, vizId, hidden.runId) } returns hidden
        every { sessions.screenshot(workspaceId, vizId, withShot.sessionId) } returns mockk<ScreenshotView>()
        every { sessions.screenshot(workspaceId, vizId, without.sessionId) } throws
            DatapipelinesException(VisualizationErrorCodes.TEST_SCREENSHOT_INVALID, "none", mapOf("reason" to "no_screenshot"))

        ExtendedModelMap().also { controller.evidenceRun(it, vizId, withShot.runId) }["hasScreenshot"] shouldBe true
        ExtendedModelMap().also {
            controller.evidenceRun(it, vizId, without.runId)
            it["hasScreenshot"] shouldBe false
            (it["casesJson"] as String) shouldContain "\"name\""
        }
        assertThrows<DatapipelinesException> { controller.evidenceRun(ExtendedModelMap(), vizId, hidden.runId) }.code shouldBe
            VisualizationErrorCodes.TEST_SESSION_NOT_FOUND
    }

    // ------------------------------------------------------------------ used by + versions

    @Test
    fun `used-by reads pinnedBy through the DASHBOARD lens and lists this visualization's pinned versions`() {
        authenticate(workspaceId, WorkspaceRole.PROMOTER)
        val dashLens = ReadLens.Only(setOf("acme/dashboards/revenue"))
        view = LensedView(ReadLens.Everything, ReadLens.Everything, dashboards = dashLens)
        twoVersions()
        val dashboardId = UUID.randomUUID()
        val pinner = mockk<ArtifactVersion<DashboardBody>>()
        every { pinner.record.id } returns dashboardId
        every { pinner.body.visualizations } returns
            listOf(
                occurrence(VisualizationUiFixtures.NAME, 2),
                occurrence(VisualizationUiFixtures.NAME, 1),
                occurrence("acme/charts/other", 5),
            )
        every { dashboards.pinnedBy(workspaceId, dashLens, VisualizationUiFixtures.NAME) } returns listOf("acme/dashboards/revenue@4")
        every { dashboards.findVersionByName(workspaceId, dashLens, "acme/dashboards/revenue", 4) } returns pinner

        val model = ExtendedModelMap()
        controller.usedBy(model, vizId) shouldBe VisualizationTabModel.USED_BY_VIEW

        @Suppress("UNCHECKED_CAST")
        val row = (model["usedBy"] as List<VisualizationTabModel.UsedByRow>).single()
        row.dashboardId shouldBe dashboardId
        row.pinnedVersions shouldBe listOf(1, 2)
        row.pinnedLabel shouldBe "v1, v2"
    }

    private fun occurrence(
        name: String,
        version: Int,
    ): VisualizationOccurrence = mockk { every { visualization } returns ArtifactRef(name, version) }

    @Test
    fun `the versions pane marks current, draft and viewed, and stamps the affordances on its own model`() {
        authenticate(workspaceId, WorkspaceRole.AUTHOR)
        twoVersions()

        val model = ExtendedModelMap()
        controller.versions(model, vizId, "2") shouldBe VisualizationTabModel.VERSIONS_VIEW

        @Suppress("UNCHECKED_CAST")
        val rows = model["versions"] as List<VisualizationTabModel.VersionRow>
        rows.map { Triple(it.version, it.isCurrent, it.isViewed) } shouldBe listOf(Triple(2, false, true), Triple(1, true, false))
        rows.first().isDraft shouldBe true
        model["canRelease"] shouldBe true
        model["soleDraft"] shouldBe false

        authenticate(workspaceId, WorkspaceRole.VIEWER)
        val viewer = ExtendedModelMap()
        controller.versions(viewer, vizId, null)
        viewer["canManageVersions"] shouldBe false
    }
}
