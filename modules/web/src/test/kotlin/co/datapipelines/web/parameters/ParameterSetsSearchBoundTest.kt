package co.datapipelines.web.parameters

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.parameters.ParameterErrorCodes
import co.datapipelines.parameters.ParameterEvaluator
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.parameters.ParametersConfig
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
 * #490 on the wire — the flat listing's `q` is bounded by the SERVICE's rule, so the controller is
 * driven over the REAL [ParameterSetService] (only its repository mocked), and the status is read
 * through the real [ApiExceptionHandler] (`ParameterSetsControllerTest` mocks the service, which is
 * exactly the layer this bound lives in). The repository stub answers ANY needle, so before the bound
 * existed the over-long term was an ordinary empty listing — the 200 this suite now refuses.
 */
class ParameterSetsSearchBoundTest {
    private val repository = mockk<ParameterSetRepository>()
    private val workspaceId = UUID.randomUUID()

    private val mvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(
                ParameterSetsController(
                    ParameterSetService(repository, mockk(), mockk(), mockk(), mockk()),
                    repository,
                    mockk<ParameterEvaluator>(),
                    ParameterSetTransferService(mockk(), mockk(), mockk(), mockk()),
                    ParametersConfig(),
                    EVERYTHING_LENS,
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
                ),
            ).setControllerAdvice(ApiExceptionHandler())
            .build()

    @BeforeEach
    fun authenticate() {
        val principal =
            AuthenticatedPrincipal(UUID.randomUUID(), "a@b.c", "A", AuthMethod.OIDC, workspace = WorkspaceContext(workspaceId, "acme"))
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())
        every { repository.searchAll(workspaceId, any(), any(), any()) } returns emptyList()
        every { repository.countSearchAll(workspaceId, any()) } returns 0
    }

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `a q of MAX_QUERY_LENGTH characters is an ordinary listing`() {
        val bounded = "r".repeat(ParameterSetService.MAX_QUERY_LENGTH)

        mvc
            .perform(get(ROOT).param("q", bounded))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items.length()").value(0))

        verify(exactly = 1) { repository.searchAll(workspaceId, bounded, 0, any()) }
    }

    @Test
    fun `a q one character longer is the catalogued 400 with the two numbers, the term never echoed, nothing read`() {
        val over = "r".repeat(ParameterSetService.MAX_QUERY_LENGTH + 1)

        val body =
            mvc
                .perform(get(ROOT).param("q", over))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error.code").value(ParameterErrorCodes.QUERY_TOO_LONG))
                .andExpect(jsonPath("$.error.details.limit").value(ParameterSetService.MAX_QUERY_LENGTH))
                .andExpect(jsonPath("$.error.details.length").value(ParameterSetService.MAX_QUERY_LENGTH + 1))
                .andReturn()
                .response.contentAsString

        body shouldNotContain over
        verify(exactly = 0) { repository.searchAll(any(), any(), any(), any()) }
        verify(exactly = 0) { repository.countSearchAll(any(), any()) }
    }

    @Test
    fun `the bound is measured after the trim - surrounding blanks never push a term over it`() {
        val bounded = "r".repeat(ParameterSetService.MAX_QUERY_LENGTH)

        mvc.perform(get(ROOT).param("q", "   $bounded   ")).andExpect(status().isOk)

        // The surface still sends `q` VERBATIM; the service trims, so the repository sees the bare term.
        verify(exactly = 1) { repository.countSearchAll(workspaceId, bounded) }
    }

    private companion object {
        const val ROOT = "/api/v1/parameter-sets"
    }
}
