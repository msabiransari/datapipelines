package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.UserService
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.SearchNeedle
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.visualization.ArtifactPage
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.DashboardBody
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.VisualizationBody
import co.datapipelines.visualization.VisualizationService
import co.datapipelines.web.EVERYTHING_LENS
import co.datapipelines.web.pipelineBrowseModelOver
import co.datapipelines.web.pipelineServiceOver
import co.datapipelines.web.templateBrowseModelOver
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.ui.ExtendedModelMap
import java.util.UUID

/**
 * The box-side half of the shared search-needle rule (#504b) for the seven search boxes whose
 * suites predate it (the admin users box joined at the #504b merge review) —
 * [ParameterSetsBrowseModelTest] owns the eighth (and the real-service variant of the same case).
 * The contract: a typed overflow is TRUNCATED to the shared bound and passed on — the box renders,
 * it never refuses, and it never passes the over-long term below itself. Each fixture proves both
 * halves: the rendered `q` (or rows) is the truncation's, and the one downstream call the model
 * makes went out carrying the TRUNCATED term (a strict mock stubbed only for the truncated term
 * throws on the over-long one).
 */
class UiSearchTruncationTest {
    private val workspaceId = UUID.randomUUID()
    private val everything = LensedView.EVERYTHING
    private val bounded = "r".repeat(SearchNeedle.MAX_LENGTH)
    private val over = "r".repeat(SearchNeedle.MAX_LENGTH + 1)

    private val principal =
        AuthenticatedPrincipal(UUID.randomUUID(), "a@b.c", "A", AuthMethod.OIDC, workspace = WorkspaceContext(workspaceId, "acme"))

    @Test
    fun `datasource box truncates and passes the truncated term on`() {
        val registry = mockk<DatasourceRegistry>()
        every { registry.listVisible(any(), any()) } returns emptyList()
        val model = ExtendedModelMap()

        DatasourceBrowseModel(registry).fillList(model, principal, over, dialect = null, offset = 0)

        model.getAttribute("q") shouldBe bounded
    }

    @Test
    fun `dashboard box truncates and passes the truncated term on`() {
        val dashboards = mockk<DashboardService>()
        // Strict and needle-pinned (#512): a call carrying the over-long term finds no answer and
        // throws, and so does the old model's 200-row `listAll` read — the model must page the search.
        every { dashboards.search(any(), any(), eq(bounded), any(), any()) } returns
            ArtifactPage<DashboardBody>(emptyList<ArtifactVersion<DashboardBody>>(), 0)
        val model = ExtendedModelMap()

        DashboardBrowseModel(
            dashboards,
            mockk(),
            mockk(),
            mockk(),
            mockk(),
            mockk(),
            mockk(),
        ).fillList(model, workspaceId, everything, over, 0, DashboardListScope.NAV)

        model.getAttribute("q") shouldBe bounded
    }

    @Test
    fun `visualization box truncates and passes the truncated term on`() {
        val visualizations = mockk<VisualizationService>()
        every { visualizations.search(any(), any(), any(), any(), any()) } returns
            ArtifactPage<VisualizationBody>(emptyList<ArtifactVersion<VisualizationBody>>(), 0)
        val model = ExtendedModelMap()

        VisualizationBrowseModel(visualizations).fillList(model, workspaceId, everything, over, 0, VisualizationListScope.NAV)

        model.getAttribute("q") shouldBe bounded
    }

    @Test
    fun `pipeline box truncates and passes the truncated term on`() {
        val repository = mockk<PipelineRepository>()
        every { repository.findAll(workspaceId, any(), any(), any()) } returns emptyList()
        every { repository.findDrafts(any(), any()) } returns emptyMap()
        val model = ExtendedModelMap()

        pipelineBrowseModelOver(repository).fillSearch(model, workspaceId, everything, over, 0, PipelineListScope.NAV)

        model.getAttribute("q") shouldBe bounded
    }

    @Test
    fun `template box truncates - the service call only answers the truncated term`() {
        val repository = mockk<TemplateRepository>()
        // Strict and q-pinned: a call carrying the over-long term finds no answer and throws,
        // which IS the failure this suite exists to catch.
        every { repository.list(workspaceId, any(), any(), eq(bounded), any(), any(), any()) } returns emptyList()
        every { repository.findDrafts(any(), any()) } returns emptyMap()
        every { repository.count(workspaceId, any(), any(), eq(bounded), any()) } returns 0
        val model = ExtendedModelMap()

        templateBrowseModelOver(repository).fillSearch(model, workspaceId, everything, over, null, null, 0, TemplateListScope.NAV)
    }

    @Test
    fun `admin users box truncates - the service call only answers the truncated term`() {
        val users = mockk<UserService>()
        // Strict and q-pinned, like the template case: the over-long term finds no answer and
        // throws — before the merge review the box passed it raw and the admin got a 400 toast.
        every { users.search(eq(bounded), 0, any()) } returns emptyList()
        val model = ExtendedModelMap()

        AdminUsersBrowseModel(users).fillList(model, "  $over  ", offset = 0, limit = 50)

        model.getAttribute("userRows") shouldBe emptyList<AdminUserRow>()
    }

    @Test
    fun `search palette truncates - the groups are asked for the truncated term only`() {
        val pipelineRepository = mockk<PipelineRepository>()
        every { pipelineRepository.findAll(workspaceId, any(), any(), any()) } returns emptyList()
        every { pipelineRepository.findDrafts(any(), any()) } returns emptyMap()
        val templates = mockk<TemplateRepository>()
        every { templates.list(workspaceId, any(), any(), eq(bounded), any(), any(), any()) } returns emptyList()
        every { templates.findDrafts(any(), any()) } returns emptyMap()
        val names = mockk<PipelineNames>()
        every { names.lookup(any(), any()) } returns emptyMap()
        val model = ExtendedModelMap()

        SearchBrowseModel(
            pipelineServiceOver(pipelineRepository),
            co.datapipelines.templates.TemplateService(templates),
            mockk<ExecutionRepository>(),
            names,
            EVERYTHING_LENS,
        ).fill(model, principal, "  $over  ")

        model.getAttribute("q") shouldBe bounded
    }
}
