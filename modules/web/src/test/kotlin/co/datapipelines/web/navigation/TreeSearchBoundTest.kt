package co.datapipelines.web.navigation

import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.SearchNeedle
import co.datapipelines.pipeline.TemplateReviewMarks
import co.datapipelines.templates.TemplateService
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.VisualizationService
import co.datapipelines.web.api.ApiExceptionHandler
import io.kotest.matchers.string.shouldNotContain
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID

/**
 * #504b on the wire — the tree searches adopt the shared search-needle rule INSIDE the projection
 * ([TreeProjection], real here), so the route is driven with the five services MOCKED (the
 * refusal fires before any of them is consulted) and the status is read through the real
 * [ApiExceptionHandler]: an over-long `q` is the catalogued `400 request.query_too_long` with the
 * two numbers, nothing loaded, nothing echoed. `TreeControllerTest` mocks the services for shape;
 * this suite owns the one bound that lives a layer below them.
 */
class TreeSearchBoundTest {
    private val workspaceId = UUID.randomUUID()

    private val mvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(
                TreeController(
                    // Relaxed: only the bounded case reaches the load arm, and an empty page is the
                    // answer under test there. The strict no-read proof lives in the REST suites,
                    // whose repositories are the ones the bound protects (#504b).
                    mockk<PipelineService>(relaxed = true),
                    mockk<TemplateService>(relaxed = true),
                    mockk<DashboardService>(relaxed = true),
                    mockk<VisualizationService>(relaxed = true),
                    mockk<ParameterSetService>(relaxed = true),
                    PromoterLens {
                        LensedView(
                            pipelines = ReadLens.Everything,
                            templates = ReadLens.Everything,
                            parameterSets = ReadLens.Everything,
                            visualizations = ReadLens.Everything,
                            dashboards = ReadLens.Everything,
                        )
                    },
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
    fun `an over-long q is the catalogued 400 on every tree-search route, nothing loaded, nothing echoed`() {
        val over = "r".repeat(SearchNeedle.MAX_LENGTH + 1)

        for (
        route in
        listOf(
            "/api/v1/pipelines/tree/search",
            "/api/v1/templates/tree/search",
            "/api/v1/dashboards/tree/search",
            "/api/v1/visualizations/tree/search",
            "/api/v1/parameter-sets/tree/search",
        )
        ) {
            val body =
                mvc
                    .perform(get(route).param("root", "").param("q", over))
                    .andExpect(status().isBadRequest)
                    .andExpect(jsonPath("$.error.code").value("request.query_too_long"))
                    .andExpect(jsonPath("$.error.details.limit").value(SearchNeedle.MAX_LENGTH))
                    .andExpect(jsonPath("$.error.details.length").value(SearchNeedle.MAX_LENGTH + 1))
                    .andReturn()
                    .response.contentAsString

            body shouldNotContain over
        }
    }

    @Test
    fun `a q at the shared bound reaches the loading arm as a normal search`() {
        // One over-long refusal above proves the gate; here the SAME route at the bound must get
        // PAST the gate and into the (service-mocked, relaxed) load. The mock is relaxed, so the
        // empty-page answer renders: a 200, not the 400.
        val bounded = "r".repeat(SearchNeedle.MAX_LENGTH)

        mvc
            .perform(get("/api/v1/pipelines/tree/search").param("root", "").param("q", bounded))
            .andExpect(status().isOk)
    }
}
