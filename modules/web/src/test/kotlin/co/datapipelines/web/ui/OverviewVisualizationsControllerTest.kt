package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.pipeline.LifecycleBucket
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.visualization.ArtifactPage
import co.datapipelines.visualization.VisualizationService
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/** #581 — both catalog entry points parse before reading, compose search/page and pass the caller's lens. */
class OverviewVisualizationsControllerTest {
    private val workspace = UUID.randomUUID()
    private val lens = ReadLens.Only(setOf("acme/charts/revenue"))
    private val service = mockk<VisualizationService>()
    private val view = PromoterLens { LensedView(ReadLens.NOTHING, ReadLens.NOTHING, visualizations = lens) }
    private val browse = VisualizationBrowseModel(service)
    private val page = VisualizationUiController(browse, mockk(), mockk { every { resolve(any()) } returns "light" }, view)
    private val partial = VisualizationPartialController(browse, mockk(), view)

    @AfterEach
    fun clearContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `both routes preserve the bucket search paging and narrowed lens`() {
        VisualizationUiFixtures.authenticate(workspace, WorkspaceRole.PROMOTER)
        every { service.listIn(workspace, lens, LifecycleBucket.WITH_DRAFTS, "rev", 25, 25) } returns ArtifactPage(emptyList(), 26)
        val full = ExtendedModelMap()
        page.list(full, MockHttpServletRequest(), " rev ", 25, "with-drafts") shouldBe VisualizationUiController.LIST_VIEW
        val fragment = ExtendedModelMap()
        val response = MockHttpServletResponse()
        partial.catalog(fragment, response, " rev ", 25, "with-drafts") shouldBe VisualizationBrowseModel.WRAPPER_VIEW
        // #591: the address bar follows the list — built from the bound values, q trimmed.
        response.getHeader(CatalogUrl.HEADER) shouldBe "/visualizations?q=rev&lifecycle=with-drafts&offset=25"
        listOf(full, fragment).forEach { model ->
            model["lifecycle"] shouldBe "with-drafts"
            model["lifecycleLabel"] shouldBe "With drafts"
            model["q"] shouldBe "rev"
            model["offset"] shouldBe 25
            model["total"] shouldBe 26
        }
    }

    @Test
    fun `unknown and unoffered filters are a constant 400 on both routes`() {
        listOf("discarded", "DRAFT", "unknown", "x' OR true --").forEach { value ->
            val full = assertThrows<ResponseStatusException> { page.list(ExtendedModelMap(), MockHttpServletRequest(), null, null, value) }
            val response = MockHttpServletResponse()
            val fragment = assertThrows<ResponseStatusException> { partial.catalog(ExtendedModelMap(), response, null, null, value) }
            // #591: a refused value never reaches the URL helper — the 400 fires first.
            response.getHeader(CatalogUrl.HEADER) shouldBe null
            listOf(full, fragment).forEach { failure ->
                failure.statusCode shouldBe HttpStatus.BAD_REQUEST
                failure.reason shouldBe CatalogLifecycle.BAD_LIFECYCLE
            }
        }
    }
}
