package co.datapipelines.web.authapi

import co.datapipelines.auth.AuditLogger
import co.datapipelines.auth.AuthCache
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthProperties
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.UserRepository
import co.datapipelines.auth.UserService
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.SearchNeedle
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
 * #504b on the wire — `/auth/users` adopts the shared search-needle rule, so the controller is
 * driven over the REAL [UserService] (only its repository mocked) and the status is read through
 * the real [ApiExceptionHandler]: a `q` one over the shared bound is the catalogued
 * `400 request.query_too_long`, nothing read, nothing echoed — the same refusal
 * `ParameterSetsSearchBoundTest` (#490) proves for the parameter-set listing. Before the shared
 * rule this route answered the over-long term with an ordinary (empty) 200.
 */
class UsersSearchBoundTest {
    private val repository = mockk<UserRepository>()
    private val workspaceId = UUID.randomUUID()

    private val mvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(
                AuthController(
                    mockk(),
                    mockk(),
                    UserService(repository, mockk<AuthCache>(), mockk<AuthProperties>(), mockk<AuditLogger>()),
                    mockk(),
                ),
            ).setControllerAdvice(ApiExceptionHandler())
            .build()

    @BeforeEach
    fun authenticate() {
        val principal =
            AuthenticatedPrincipal(UUID.randomUUID(), "a@b.c", "A", AuthMethod.OIDC, workspace = WorkspaceContext(workspaceId, "acme"))
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())
        every { repository.search(any(), any(), any()) } returns emptyList()
    }

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `a q at the shared bound is an ordinary listing`() {
        val bounded = "r".repeat(SearchNeedle.MAX_LENGTH)

        mvc
            .perform(get("/api/v1/auth/users").param("q", bounded))
            .andExpect(status().isOk)

        verify(exactly = 1) { repository.search(any(), any(), any()) }
    }

    @Test
    fun `a q one over the shared bound is the catalogued 400 with the two numbers, nothing read, nothing echoed`() {
        val over = "r".repeat(SearchNeedle.MAX_LENGTH + 1)

        val body =
            mvc
                .perform(get("/api/v1/auth/users").param("q", over))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error.code").value("request.query_too_long"))
                .andExpect(jsonPath("$.error.details.limit").value(SearchNeedle.MAX_LENGTH))
                .andExpect(jsonPath("$.error.details.length").value(SearchNeedle.MAX_LENGTH + 1))
                .andReturn()
                .response.contentAsString

        body shouldNotContain over
        verify(exactly = 0) { repository.search(any(), any(), any()) }
    }

    @Test
    fun `a whitespace-only q is no search, not a refusal`() {
        mvc
            .perform(get("/api/v1/auth/users").param("q", "   "))
            .andExpect(status().isOk)

        // The service trimmed it to the all-people arm the repository already owns.
        verify(exactly = 1) { repository.search(any(), any(), any()) }
    }
}
