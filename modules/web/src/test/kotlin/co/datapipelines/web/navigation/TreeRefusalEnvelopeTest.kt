package co.datapipelines.web.navigation

import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.pipeline.NavigationRow
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.TemplateReviewMarks
import co.datapipelines.templates.TemplateService
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.VisualizationService
import co.datapipelines.web.api.ApiExceptionHandler
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID

/**
 * #464 on the wire — the tree's three refusals through the real [ApiExceptionHandler], the
 * [TreeSearchBoundTest] setup: each answers its catalogued §13.24 code in the §4.2 envelope
 * (snake_case `correlation_id`, `user_message`, `doc_url`), never the stand-in
 * `pipeline.execution.aborted` with `details.reason = response_status`; only the view 503 carries
 * `Retry-After`, and no response echoes the cursor or the view's target.
 */
class TreeRefusalEnvelopeTest {
    private val workspaceId = UUID.randomUUID()
    private val pipelines = mockk<PipelineService>()
    private var view =
        LensedView(
            pipelines = ReadLens.Everything,
            templates = ReadLens.Everything,
            parameterSets = ReadLens.Everything,
            visualizations = ReadLens.Everything,
            dashboards = ReadLens.Everything,
        )

    private val mvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(
                TreeController(
                    pipelines,
                    mockk<TemplateService>(),
                    mockk<DashboardService>(),
                    mockk<VisualizationService>(),
                    mockk<ParameterSetService>(),
                    PromoterLens { view },
                    TemplateReviewMarks.NONE,
                ),
            ).setControllerAdvice(ApiExceptionHandler())
            .build()

    @BeforeEach
    fun authenticate() {
        val principal =
            AuthenticatedPrincipal(UUID.randomUUID(), "a@b.c", "A", AuthMethod.OIDC, workspace = WorkspaceContext(workspaceId, "acme"))
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `a tampered continuation is 400 navigation request invalid, nothing echoed, no Retry-After`() {
        val body =
            mvc
                .perform(get("/api/v1/pipelines/tree").param("root", "scope").param("cursor", TAMPERED))
                .andExpect(status().isBadRequest)
                .catalogued(PipelineErrorCodes.Navigation.REQUEST_INVALID)
                .andExpect(header().doesNotExist("Retry-After"))
                .andReturn()
                .response.contentAsString

        body shouldNotContain TAMPERED
    }

    @Test
    fun `an unavailable promotion view is 503 navigation view unavailable with Retry-After and no details`() {
        view = LensedView(ReadLens.NOTHING, ReadLens.NOTHING, LensedView.Unavailable(TARGET, "connect timed out"))

        val body =
            mvc
                .perform(get("/api/v1/pipelines/tree").param("root", ""))
                .andExpect(status().isServiceUnavailable)
                .catalogued(PipelineErrorCodes.Navigation.VIEW_UNAVAILABLE)
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.error.details").isEmpty)
                .andReturn()
                .response.contentAsString

        body shouldNotContain TARGET
        body shouldNotContain "connect timed out"
    }

    @Test
    fun `one node over the page budget is 503 navigation metadata too large, no Retry-After`() {
        every { pipelines.navigation(any()) } returns
            listOf(NavigationRow("scope/huge", "x".repeat(TreeProjection.MAX_RESPONSE_BYTES), UUID.randomUUID(), 1, null))

        mvc
            .perform(get("/api/v1/pipelines/tree").param("root", "scope"))
            .andExpect(status().isServiceUnavailable)
            .catalogued(PipelineErrorCodes.Navigation.METADATA_TOO_LARGE)
            .andExpect(header().doesNotExist("Retry-After"))
    }

    /** The §4.2 envelope with [code], snake_case keys present, and none of the stand-in's markers. */
    private fun ResultActions.catalogued(code: String): ResultActions =
        andExpect(jsonPath("$.error.code").value(code))
            .andExpect(jsonPath("$.correlation_id").exists())
            .andExpect(jsonPath("$.error.user_message").isNotEmpty)
            .andExpect(jsonPath("$.error.doc_url").isNotEmpty)
            .andExpect(jsonPath("$.error.details.reason").doesNotExist())

    private companion object {
        const val TAMPERED = "dGFtcGVyZWQ.c2lnbmF0dXJl"
        const val TARGET = "https://promotion-target.internal:8443"
    }
}
