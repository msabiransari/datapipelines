package co.datapipelines.web.pipelines

import co.datapipelines.application.checks.PipelineCheckRunRepository
import co.datapipelines.application.checks.PipelineCheckRunner
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.ExclusiveDraftTemplates
import co.datapipelines.pipeline.PipelineDraftService
import co.datapipelines.pipeline.PipelineReleaseService
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineValidator
import co.datapipelines.pipeline.PipelineVersionConsumers
import co.datapipelines.pipeline.SearchNeedle
import co.datapipelines.web.EVERYTHING_LENS
import co.datapipelines.web.api.ApiExceptionHandler
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
 * #504b on the wire — the pipelines listing adopts the shared search-needle rule, so the controller
 * is driven over the REAL [PipelineService] (only its repository mocked) and the status is read
 * through the real [ApiExceptionHandler]: a `q` one over the shared bound is the catalogued
 * `400 request.query_too_long` with the two numbers, nothing read, nothing echoed — the same
 * refusal `ParameterSetsSearchBoundTest` proves for the parameter-set listing (#490's mould).
 * Before the shared rule this listing answered the over-long term with an ordinary (empty) 200.
 */
class PipelinesSearchBoundTest {
    private val repository = mockk<PipelineRepository>()
    private val workspaceId = UUID.randomUUID()

    private val service =
        PipelineService(
            repository,
            mockk<PipelineValidator>(),
            mockk<PipelineDraftService>(),
            mockk<PipelineReleaseService>(),
            mockk<AuthoringGuard>(),
            mockk<ExclusiveDraftTemplates>(),
            mockk<PipelineVersionConsumers>(),
        )

    private val mvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(
                PipelinesController(
                    service,
                    object : AuditEventSink {
                        override fun log(
                            event: String,
                            userId: UUID?,
                            keyId: String?,
                            sourceIp: String?,
                            userAgent: String?,
                            details: Map<String, Any?>,
                        ) = Unit
                    },
                    mockk<PipelineCheckRunner>(),
                    mockk<PipelineCheckRunRepository>(),
                    EVERYTHING_LENS,
                ),
            ).setControllerAdvice(ApiExceptionHandler())
            .build()

    @BeforeEach
    fun authenticate() {
        val principal =
            AuthenticatedPrincipal(UUID.randomUUID(), "a@b.c", "A", AuthMethod.OIDC, workspace = WorkspaceContext(workspaceId, "acme"))
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())
        every { repository.findAll(workspaceId, any(), any(), any()) } returns emptyList()
        // The listing also decorates its draft pointers (#D2) — a strict repo keeps the suite
        // honest about the ONE read the bound protects.
        every { repository.findDrafts(any(), any()) } returns emptyMap()
    }

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `a q at the shared bound is an ordinary listing`() {
        val bounded = "r".repeat(SearchNeedle.MAX_LENGTH)

        mvc
            .perform(get("/api/v1/pipelines").param("q", bounded))
            .andExpect(status().isOk)

        verify(exactly = 1) { repository.findAll(workspaceId, any(), any(), any()) }
    }

    @Test
    fun `a q one over the shared bound is the catalogued 400 with the two numbers, nothing read, nothing echoed`() {
        val over = "r".repeat(SearchNeedle.MAX_LENGTH + 1)

        val body =
            mvc
                .perform(get("/api/v1/pipelines").param("q", over))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error.code").value("request.query_too_long"))
                .andExpect(jsonPath("$.error.details.limit").value(SearchNeedle.MAX_LENGTH))
                .andExpect(jsonPath("$.error.details.length").value(SearchNeedle.MAX_LENGTH + 1))
                .andReturn()
                .response.contentAsString

        body shouldNotContain over
        verify(exactly = 0) { repository.findAll(any(), any(), any(), any()) }
    }

    @Test
    fun `a whitespace-only q is no search, not a refusal`() {
        mvc
            .perform(get("/api/v1/pipelines").param("q", "   "))
            .andExpect(status().isOk)

        // The service trimmed it to "no search" and listed unfiltered — the repository saw no needle.
        verify(exactly = 1) { repository.findAll(workspaceId, any(), any(), any()) }
    }
}
