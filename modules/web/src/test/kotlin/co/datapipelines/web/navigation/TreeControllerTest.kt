package co.datapipelines.web.navigation

import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.pipeline.NavigationRequest
import co.datapipelines.pipeline.NavigationRow
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.RetiredFactCitation
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.TemplateReviewMarks
import co.datapipelines.templates.TemplateService
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.VisualizationService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

class TreeControllerTest {
    private val pipelines = mockk<PipelineService>()
    private val templates = mockk<TemplateService>()
    private val dashboards = mockk<DashboardService>()
    private val visualizations = mockk<VisualizationService>()
    private val sets = mockk<ParameterSetService>()

    // Strict: the pipelines and empty-page cases must never reach it, and the templates case
    // stubs the ONE call it expects, so an extra or a missing call is red.
    private val marks = mockk<TemplateReviewMarks>()
    private val workspace = UUID.randomUUID()
    private val actor = UUID.randomUUID()

    // One distinct lens per family, so a handler that read another family's lens cannot pass.
    private val lenses =
        listOf("pipeline", "template", "dashboard", "visualization", "set")
            .associateWith { ReadLens.Only(setOf("scope/$it")) }
    private var view =
        LensedView(
            pipelines = lenses.getValue("pipeline"),
            templates = lenses.getValue("template"),
            parameterSets = lenses.getValue("set"),
            visualizations = lenses.getValue("visualization"),
            dashboards = lenses.getValue("dashboard"),
        )
    private val controller = TreeController(pipelines, templates, dashboards, visualizations, sets, PromoterLens { view }, marks)

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `all ten handlers derive workspace and actual lens then return no-store JSON`() {
        authenticate()
        every { pipelines.navigation(any()) } returns emptyList()
        every { templates.navigation(any()) } returns emptyList()
        every { dashboards.navigation(any()) } returns emptyList()
        every { visualizations.navigation(any()) } returns emptyList()
        every { sets.navigation(any()) } returns emptyList()
        val answers =
            listOf(
                controller.pipelinesTree("scope", null, null),
                controller.pipelinesSearch("scope", "match", null),
                controller.templatesTree("scope", null, null),
                controller.templatesSearch("scope", "match", null),
                controller.dashboardsTree("scope", null, null),
                controller.dashboardsSearch("scope", "match", null),
                controller.visualizationsTree("scope", null, null),
                controller.visualizationsSearch("scope", "match", null),
                controller.parameterSetsTree("scope", null, null),
                controller.parameterSetsSearch("scope", "match", null),
            )
        answers.forEach { answer ->
            answer.headers.cacheControl shouldBe "no-store"
            checkNotNull(answer.body).data.workspaceId shouldBe workspace
        }

        fun browse(family: String) =
            NavigationRequest(workspace, lenses.getValue(family), "scope", "scope", null, limit = NavigationRequest.PAGE_SIZE + 1)
        verify(exactly = 1) {
            pipelines.navigation(browse("pipeline"))
            pipelines.navigation(browse("pipeline").copy(query = "match"))
        }
        verify(exactly = 1) {
            templates.navigation(browse("template"))
            templates.navigation(browse("template").copy(query = "match"))
        }
        verify(exactly = 1) {
            dashboards.navigation(browse("dashboard"))
            dashboards.navigation(browse("dashboard").copy(query = "match"))
        }
        verify(exactly = 1) {
            visualizations.navigation(browse("visualization"))
            visualizations.navigation(browse("visualization").copy(query = "match"))
        }
        verify(exactly = 1) {
            sets.navigation(browse("set"))
            sets.navigation(browse("set").copy(query = "match"))
        }
    }

    /**
     * #533 — a templates page, browse and search alike, carries each leaf's `needs_review` from ONE
     * citation read keyed by name and LISTED version: true for the pin the port names, false for
     * the clean leaf, null on the folder.
     */
    @Test
    fun `a templates page marks exactly the leaves whose listed version cites a retired fact`() {
        authenticate()
        val folder = NavigationRow("scope/sub", "sub", null, null, null)
        val drifted = NavigationRow("scope/active_orders.jsonata", "active_orders", UUID.randomUUID(), 2, null)
        val clean = NavigationRow("scope/orders.sql", "orders", UUID.randomUUID(), 1, 3)
        every { templates.navigation(any()) } returns listOf(folder, drifted, clean)
        val pins = setOf(TemplateRef(drifted.path, 2), TemplateRef(clean.path, 1))
        every { marks.retiredCitations(workspace, pins) } returns
            mapOf(TemplateRef(drifted.path, 2) to listOf(RetiredFactCitation("f1", "superseded", "f2")))

        listOf(controller.templatesTree("scope", null, null), controller.templatesSearch("scope", "o", null)).forEach { answer ->
            val nodes = checkNotNull(answer.body).data.nodes.associateBy { it.path }
            nodes.getValue(drifted.path).needsReview shouldBe true
            nodes.getValue(clean.path).needsReview shouldBe false
            checkNotNull(answer.body)
                .data.nodes
                .filter { it.kind == "folder" }
                .map { it.needsReview }
                .distinct() shouldBe listOf(null)
        }
        verify(exactly = 2) { marks.retiredCitations(workspace, pins) }
    }

    /** #533 — the flag is the templates family's alone: a pipelines page never asks the port and carries null. */
    @Test
    fun `a pipelines page never asks for review marks`() {
        authenticate()
        every { pipelines.navigation(any()) } returns listOf(NavigationRow("scope/daily", "daily", UUID.randomUUID(), 1, null))
        val nodes = checkNotNull(controller.pipelinesTree("scope", null, null).body).data.nodes
        nodes.map { it.needsReview } shouldBe listOf(null)
        verify(exactly = 0) { marks.retiredCitations(any(), any()) }
    }

    @Test
    fun `unavailable lens is a refusal rather than an empty success`() {
        authenticate()
        view = LensedView(ReadLens.NOTHING, ReadLens.NOTHING, LensedView.Unavailable("private target", "unavailable"))
        shouldThrow<ResponseStatusException> { controller.pipelinesTree("", null, null) }.statusCode.value() shouldBe 503
        verify(exactly = 0) { pipelines.navigation(any()) }
    }

    @Test
    fun `direct calls require session and selected workspace before loading`() {
        authenticate(AuthMethod.API_KEY)
        shouldThrow<DatapipelinesException> { controller.pipelinesTree("", null, null) }.code shouldBe "auth.session.required"
        authenticate(selected = false)
        shouldThrow<DatapipelinesException> { controller.pipelinesTree("", null, null) }
        verify(exactly = 0) { pipelines.navigation(any()) }
    }

    private fun authenticate(
        method: AuthMethod = AuthMethod.OIDC,
        selected: Boolean = true,
    ) {
        val principal =
            AuthenticatedPrincipal(
                actor,
                "tree@test.invalid",
                "Tree",
                method,
                workspace = if (selected) WorkspaceContext(workspace, "tree") else null,
            )
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }
}
