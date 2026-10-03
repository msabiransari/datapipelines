package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.ArtifactPage
import co.datapipelines.visualization.VisualizationErrorCodes
import co.datapipelines.visualization.VisualizationService
import co.datapipelines.web.ui.VisualizationUiFixtures.authenticate
import co.datapipelines.web.ui.VisualizationUiFixtures.detail
import co.datapipelines.web.ui.VisualizationUiFixtures.version
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * #399 — the Visualizations page controller: the catalog is the flat list under the caller's lens; the workspace
 * resolves ONE version (the current pointer, else the working draft, else the named one), declares the ONE bundle
 * the viewed version's renderer needs, keeps the NAME in every state, and refuses a DISCARDED or lens-hidden
 * version with the family's 404. The version parse is a 400 that never echoes its input; the tab set is closed.
 */
class VisualizationUiControllerTest {
    private val workspaceId = UUID.randomUUID()
    private val vizId = UUID.randomUUID()
    private val visualizations = mockk<VisualizationService>()
    private var view: LensedView = LensedView.EVERYTHING

    private val controller =
        VisualizationUiController(
            browse = VisualizationBrowseModel(visualizations),
            workspace = VisualizationWorkspaceModel(visualizations),
            themeResolver = mockk<ThemeResolver> { every { resolve(any<HttpServletRequest>()) } returns "light" },
            lens = PromoterLens { view },
        )

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun open(
        version: String? = null,
        tab: String? = null,
    ): ExtendedModelMap {
        val model = ExtendedModelMap()
        controller.workspace(vizId, version, tab, model, MockHttpServletRequest()) shouldBe VisualizationUiController.WORKSPACE_VIEW
        return model
    }

    /** v1 released + current, v2 the working draft; [config] is v1's trace set. */
    private fun releasedWithDraft(config: String? = null) {
        val v1 =
            if (config == null) {
                version(vizId, workspaceId, 1, PipelineVersionStatus.RELEASED, currentVersion = 1)
            } else {
                version(vizId, workspaceId, 1, PipelineVersionStatus.RELEASED, currentVersion = 1, config = config)
            }
        val v2 = version(vizId, workspaceId, 2, PipelineVersionStatus.DRAFT, currentVersion = 1)
        every { visualizations.findWorking(workspaceId, any(), vizId) } returns v2
        every { visualizations.findVersion(workspaceId, any(), vizId, 1) } returns v1
        every { visualizations.findVersion(workspaceId, any(), vizId, 2) } returns v2
        every { visualizations.listVersions(workspaceId, any(), vizId) } returns
            listOf(detail(vizId, 2, PipelineVersionStatus.DRAFT), detail(vizId, 1, PipelineVersionStatus.RELEASED))
    }

    @Test
    fun `no version named views the current pointer, with its chip, its bundle and the default Preview tab`() {
        authenticate(workspaceId, WorkspaceRole.AUTHOR)
        releasedWithDraft()

        val model = open()

        model["visualizationName"] shouldBe VisualizationUiFixtures.NAME
        model["hasSelected"] shouldBe true
        model["viewedVersion"] shouldBe 1
        model["viewedLabel"] shouldBe "v1 · released · current"
        model["viewedIsDraft"] shouldBe false
        model["currentVersion"] shouldBe 1
        model["draftVersion"] shouldBe 2
        model["bundle"] shouldBe "2d"
        model["activeTab"] shouldBe "preview"
        @Suppress("UNCHECKED_CAST")
        (model["versions"] as List<VisualizationUiController.VersionRow>).map { it.version to it.isSelected } shouldBe
            listOf(2 to false, 1 to true)
    }

    @Test
    fun `a named draft is viewed as the draft, and a 3d trace declares the 3d bundle`() {
        authenticate(workspaceId, WorkspaceRole.AUTHOR)
        releasedWithDraft(config = VisualizationUiFixtures.TRACES_3D)

        open(version = "2").let {
            it["viewedLabel"] shouldBe "v2 · draft"
            it["viewedIsDraft"] shouldBe true
            it["bundle"] shouldBe "2d"
        }
        open(version = "1")["bundle"] shouldBe "3d"
    }

    @Test
    fun `the tab set is closed - an unknown tab is Preview, a known one is kept`() {
        authenticate(workspaceId, WorkspaceRole.VIEWER)
        releasedWithDraft()

        open(tab = "versions")["activeTab"] shouldBe "versions"
        open(tab = "used-by")["activeTab"] shouldBe "used-by"
        open(tab = "<script>")["activeTab"] shouldBe "preview"
    }

    @ParameterizedTest
    @ValueSource(strings = ["abc", "0", "-1", "1.5", "<b>x</b>"])
    fun `a malformed version is a 400 that never echoes the input`(raw: String) {
        authenticate(workspaceId, WorkspaceRole.AUTHOR)
        releasedWithDraft()

        val error = assertThrows<ResponseStatusException> { open(version = raw) }

        error.statusCode shouldBe HttpStatus.BAD_REQUEST
        error.reason shouldBe VisualizationWorkspaceModel.BAD_VERSION
        // The whole message is the constant reason: nothing of the input rides it.
        error.message shouldBe "400 BAD_REQUEST \"${VisualizationWorkspaceModel.BAD_VERSION}\""
    }

    @Test
    fun `a DISCARDED version is the family's 404, never served`() {
        authenticate(workspaceId, WorkspaceRole.AUTHOR)
        releasedWithDraft()
        every { visualizations.findVersion(workspaceId, any(), vizId, 1) } returns
            version(vizId, workspaceId, 1, PipelineVersionStatus.DISCARDED)

        val error = assertThrows<DatapipelinesException> { open(version = "1") }

        error.code shouldBe VisualizationErrorCodes.NOT_FOUND
    }

    @Test
    fun `an unknown or lens-hidden visualization is the 404, and the read goes through the caller's lens`() {
        authenticate(workspaceId, WorkspaceRole.PROMOTER)
        val narrowed = ReadLens.Only(setOf("acme/charts/other"))
        view = LensedView(ReadLens.Everything, ReadLens.Everything, visualizations = narrowed)
        every { visualizations.findWorking(workspaceId, narrowed, vizId) } returns null

        val error = assertThrows<DatapipelinesException> { open() }

        error.code shouldBe VisualizationErrorCodes.NOT_FOUND
        verify { visualizations.findWorking(workspaceId, narrowed, vizId) }
        verify(exactly = 0) { visualizations.findWorking(workspaceId, ReadLens.Everything, vizId) }
    }

    @Test
    fun `no current and no draft for this caller is the choose-a-version state - the NAME kept, no bundle`() {
        authenticate(workspaceId, WorkspaceRole.AUTHOR)
        // The working row is released but the caller's lens resolves no current version (a pointer the lens hides).
        val working = version(vizId, workspaceId, 3, PipelineVersionStatus.RELEASED, currentVersion = 3)
        every { visualizations.findWorking(workspaceId, any(), vizId) } returns working
        every { visualizations.findVersion(workspaceId, any(), vizId, 3) } returns null
        every { visualizations.listVersions(workspaceId, any(), vizId) } returns emptyList()

        val model = open()

        model["hasSelected"] shouldBe false
        model["visualizationName"] shouldBe VisualizationUiFixtures.NAME
        model["viewedLabel"] shouldBe VisualizationUiController.NO_VERSION_SELECTED
        model.containsAttribute("bundle") shouldBe false
    }

    @Test
    fun `the verbs' affordances are the permission checks - an author holds them, a viewer and a promoter none`() {
        releasedWithDraft()
        authenticate(workspaceId, WorkspaceRole.AUTHOR)
        open().let { m -> listOf("canRelease", "canManageVersions", "canSwitch", "canDelete").forEach { m[it] shouldBe true } }

        for (role in listOf(WorkspaceRole.VIEWER, WorkspaceRole.PROMOTER)) {
            authenticate(workspaceId, role)
            open().let { m -> listOf("canRelease", "canManageVersions", "canSwitch", "canDelete").forEach { m[it] shouldBe false } }
        }
    }

    @Test
    fun `the catalog is the flat list through the caller's lens, searched by q`() {
        authenticate(workspaceId, WorkspaceRole.PROMOTER)
        val narrowed = ReadLens.Only(setOf(VisualizationUiFixtures.NAME))
        view = LensedView(ReadLens.Everything, ReadLens.Everything, visualizations = narrowed)
        val row = version(vizId, workspaceId, 1, PipelineVersionStatus.RELEASED, currentVersion = 1)
        every { visualizations.listAll(workspaceId, narrowed, 0, VisualizationBrowseModel.PAGE_SIZE) } returns listOf(row)
        every { visualizations.countAll(workspaceId, narrowed) } returns 1
        every { visualizations.search(workspaceId, narrowed, "rev", 25, VisualizationBrowseModel.PAGE_SIZE) } returns
            ArtifactPage(listOf(row), 26)

        val all = ExtendedModelMap()
        controller.list(all, MockHttpServletRequest(), q = null, offset = null) shouldBe VisualizationUiController.LIST_VIEW
        all["scope"] shouldBe "page"
        all["rootId"] shouldBe VisualizationBrowseModel.PAGE_ROOT_ID
        all["total"] shouldBe 1

        val searched = ExtendedModelMap()
        controller.list(searched, MockHttpServletRequest(), q = "  rev  ", offset = 25)
        searched["q"] shouldBe "rev"
        searched["total"] shouldBe 26
        searched["hasMore"] shouldBe false
    }
}
