package co.datapipelines.web.templates

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.SearchNeedle
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateService
import co.datapipelines.web.EVERYTHING_LENS
import co.datapipelines.web.api.ApiExceptionHandler
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID

/**
 * #504b on the wire — the templates listing adopts the shared search-needle rule, so the controller
 * is driven over the REAL [TemplateService] (only its repository mocked, everything-lens path) and
 * the status is read through the real [ApiExceptionHandler]: a `q` one over the shared bound is the
 * catalogued `400 request.query_too_long`, nothing read, nothing echoed — the same refusal
 * `ParameterSetsSearchBoundTest` (#490) and `PipelinesSearchBoundTest` prove for their listings.
 * Before the shared rule this listing answered the over-long term with an ordinary (empty) 200.
 */
class TemplatesSearchBoundTest {
    // The needle rule for templates lives in the REPOSITORY (searchPattern), so unlike the
    // parameter-set and pipeline suites the repository must be REAL — only its JDBC template is
    // mocked — or this suite would prove a rule that never runs.
    private val jdbc = mockk<NamedParameterJdbcTemplate>()
    private val repository = TemplateRepository(jdbc)
    private val workspaceId = UUID.randomUUID()

    private val mvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(
                TemplatesController(
                    repository,
                    TemplateService(repository),
                    EVERYTHING_LENS,
                    mockk(),
                    mockk(),
                    mockk(),
                    mockk(),
                    mockk(),
                    mockk(),
                    mockk(),
                    mockk<co.datapipelines.application.templates.TemplateEvaluateService>(),
                ),
            ).setControllerAdvice(ApiExceptionHandler())
            .build()

    @BeforeEach
    fun authenticate() {
        val principal =
            AuthenticatedPrincipal(UUID.randomUUID(), "a@b.c", "A", AuthMethod.OIDC, workspace = WorkspaceContext(workspaceId, "acme"))
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())
        every {
            jdbc.query(any<String>(), any<Map<String, Any?>>(), any<RowMapper<co.datapipelines.templates.Template>>())
        } returns emptyList()
    }

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `a q at the shared bound is an ordinary listing`() {
        val bounded = "r".repeat(SearchNeedle.MAX_LENGTH)

        mvc
            .perform(get("/api/v1/templates").param("q", bounded))
            .andExpect(status().isOk)

        verify(exactly = 1) {
            jdbc.query(any<String>(), any<Map<String, Any?>>(), any<RowMapper<co.datapipelines.templates.Template>>())
        }
    }

    @Test
    fun `a q one over the shared bound is the catalogued 400 with the two numbers, nothing read, nothing echoed`() {
        val over = "r".repeat(SearchNeedle.MAX_LENGTH + 1)

        val body =
            mvc
                .perform(get("/api/v1/templates").param("q", over))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error.code").value("request.query_too_long"))
                .andExpect(jsonPath("$.error.details.limit").value(SearchNeedle.MAX_LENGTH))
                .andExpect(jsonPath("$.error.details.length").value(SearchNeedle.MAX_LENGTH + 1))
                .andReturn()
                .response.contentAsString

        body shouldNotContain over
        verify(exactly = 0) {
            jdbc.query(any<String>(), any<Map<String, Any?>>(), any<RowMapper<co.datapipelines.templates.Template>>())
        }
    }

    @Test
    fun `a whitespace-only q is no search, not a refusal`() {
        mvc
            .perform(get("/api/v1/templates").param("q", "   "))
            .andExpect(status().isOk)

        // The service trimmed it to "no search" and listed unfiltered — the repository saw no needle.
        verify(exactly = 1) {
            jdbc.query(any<String>(), any<Map<String, Any?>>(), any<RowMapper<co.datapipelines.templates.Template>>())
        }
    }
}
