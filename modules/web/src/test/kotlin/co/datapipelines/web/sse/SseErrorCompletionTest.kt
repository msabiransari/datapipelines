package co.datapipelines.web.sse

import co.datapipelines.auth.AuditLogger
import co.datapipelines.auth.AuthErrorWriter
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.ScopeInterceptor
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.executor.LimitScope
import co.datapipelines.executor.PipelineConcurrencyLimitException
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.api.ApiExceptionHandler
import co.datapipelines.web.pipelines.ExecutionStreamLauncher
import co.datapipelines.web.pipelines.PipelineExecuteController
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID

/**
 * #404 — an SSE stream completed with an error BEFORE its first frame answers the exception's
 * mapped §4.2 envelope on the async re-dispatch, not `401 auth.api_key.missing`.
 *
 * The execute handler returns its [SseEmitter] on the REQUEST dispatch; the launcher completes it
 * later, off the request thread — `failBeforeStart`'s concurrency refusal, or the idempotent
 * follow's never-started give-up (`SseLogStreamer`, #324). Either completion re-enters the
 * DispatcherServlet as an ASYNC dispatch, which runs the MVC interceptors again before the
 * concurrent result reaches the advice. On that dispatch the SecurityContext is EMPTY on the wire:
 * the credential filters are `OncePerRequestFilter`s that skip async dispatches and nothing saves
 * the context for it. Each test therefore clears [SecurityContextHolder] between the REQUEST
 * dispatch and [asyncDispatch] — without the clear the test thread's context leaks into the
 * dispatch and the case is green on the broken code.
 *
 * The builder carries the production interceptor ([ScopeInterceptor]) and advice
 * ([ApiExceptionHandler]); the one case without the interceptor is the control that names it
 * as the 401's writer.
 */
class SseErrorCompletionTest {
    private val pipelineId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()
    private val mapper = ObjectMapper()
    private val auditLogger = mockk<AuditLogger>(relaxed = true)
    private val emitter = SseEmitter(NEVER_TIMEOUT)

    private val pipelines =
        mockk<PipelineService> {
            every { findRecord(workspaceId, any(), pipelineId) } returns mockk(relaxed = true)
            every { workingVersion(workspaceId, any(), any()) } returns 1
            every { findExecutable(workspaceId, any(), any(), 1) } returns mockk(relaxed = true)
        }
    private val launcher = mockk<ExecutionStreamLauncher> { every { launch(any()) } returns emitter }

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    private fun mvc(withScopeInterceptor: Boolean = true): MockMvc {
        val builder =
            MockMvcBuilders
                .standaloneSetup(PipelineExecuteController(pipelines, launcher))
                .setControllerAdvice(ApiExceptionHandler())
        if (withScopeInterceptor) builder.addInterceptors(ScopeInterceptor(AuthErrorWriter(mapper), auditLogger))
        return builder.build()
    }

    private fun member() =
        AuthenticatedPrincipal(
            UUID.randomUUID(),
            "author@company.com",
            "Author",
            AuthMethod.OIDC,
            workspace = WorkspaceContext(workspaceId, "acme", WorkspaceRole.AUTHOR),
        )

    /** A super admin acting in a workspace they hold no membership in — D-R8's audited principal. */
    private fun superAdmin() =
        AuthenticatedPrincipal(
            userId = UUID.randomUUID(),
            email = "root@company.com",
            displayName = "Root",
            authMethod = AuthMethod.OIDC,
            workspace = WorkspaceContext.superAdminOver(workspaceId, "acme", explicitRole = null),
            superAdmin = true,
        )

    private fun authenticate(principal: AuthenticatedPrincipal) {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    /** The REQUEST dispatch, authenticated: the handler returns the emitter and the request goes async. */
    private fun startStream(
        mvc: MockMvc,
        principal: AuthenticatedPrincipal = member(),
    ): MvcResult {
        authenticate(principal)
        return mvc
            .perform(
                post("/api/v1/pipelines/{id}/execute", pipelineId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
                    .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE),
            ).andExpect(request().asyncStarted())
            .andReturn()
    }

    @Test
    fun `a stream refused its execution slot before the first frame answers the 429 envelope, not 401`() {
        val mvc = mvc()
        val started = startStream(mvc)

        emitter.completeWithError(PipelineConcurrencyLimitException(LimitScope.PER_USER, 1))
        SecurityContextHolder.clearContext()

        mvc
            .perform(asyncDispatch(started))
            .andExpect(status().isTooManyRequests)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error.code").value(PipelineErrorCodes.Execution.CONCURRENCY_LIMIT))
            .andExpect(jsonPath("$.error.details.scope").value("per_user"))
            .andExpect(jsonPath("$.error.details.limit").value(1))
    }

    @Test
    fun `a follow that gave up on a never-started original answers the id-free 410 envelope, not 401`() {
        val mvc = mvc()
        val started = startStream(mvc)

        // The exception SseLogStreamer's give-up completes with (#324 R2).
        emitter.completeWithError(
            ApiException(PipelineErrorCodes.Result.EXPIRED, "never started", mapOf("reason" to "original_not_started")),
        )
        SecurityContextHolder.clearContext()

        mvc
            .perform(asyncDispatch(started))
            .andExpect(status().isGone)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error.code").value(PipelineErrorCodes.Result.EXPIRED))
            .andExpect(jsonPath("$.error.details.reason").value("original_not_started"))
            .andExpect(jsonPath("$.error.details.execution_id").doesNotExist())
    }

    /**
     * The control that names the writer (A.1): the same completion, the same cleared context,
     * with NO interceptor — the advice renders the 429. Paired with the case above on the
     * unfixed interceptor (401 `auth.api_key.missing`), the only variable is [ScopeInterceptor].
     */
    @Test
    fun `without the scope interceptor the same completion renders the 429 - the control`() {
        val mvc = mvc(withScopeInterceptor = false)
        val started = startStream(mvc)

        emitter.completeWithError(PipelineConcurrencyLimitException(LimitScope.PER_USER, 1))
        SecurityContextHolder.clearContext()

        mvc
            .perform(asyncDispatch(started))
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.error.code").value(PipelineErrorCodes.Execution.CONCURRENCY_LIMIT))
    }

    /** A NORMAL completion still passes the async dispatch and carries its frames. */
    @Test
    fun `a stream that sent a frame and completed answers 200 with its frame`() {
        val mvc = mvc()
        val started = startStream(mvc)

        emitter.send(SseEmitter.event().name("execution_started").data("""{"execution_id":"x"}"""))
        emitter.complete()
        SecurityContextHolder.clearContext()

        mvc
            .perform(asyncDispatch(started))
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("event:execution_started")))
    }

    /**
     * Roles (the brief's widening argument): the ASYNC dispatch does not invoke the handler again
     * — the adapter only processes the concurrent result the authorized REQUEST dispatch produced —
     * so admitting it authorizes nothing new. Counted across both dispatches.
     */
    @Test
    fun `the handler runs once across the REQUEST and ASYNC dispatches`() {
        val mvc = mvc()
        val started = startStream(mvc)

        emitter.completeWithError(PipelineConcurrencyLimitException(LimitScope.PER_USER, 1))
        SecurityContextHolder.clearContext()
        mvc.perform(asyncDispatch(started)).andExpect(status().isTooManyRequests)

        verify(exactly = 1) { pipelines.findRecord(workspaceId, any(), pipelineId) }
        verify(exactly = 1) { launcher.launch(any()) }
    }

    /**
     * D-R8's audit row is one per REQUEST: the REQUEST dispatch judged and audited it, and an
     * ASYNC dispatch that carried the principal (a context-persisting change elsewhere would make
     * it carry one) must not audit the same request a second time.
     */
    @Test
    fun `a super admin's audit row is written once even when the async dispatch carries the principal`() {
        val mvc = mvc()
        val principal = superAdmin()
        val started = startStream(mvc, principal)

        emitter.completeWithError(PipelineConcurrencyLimitException(LimitScope.PER_USER, 1))
        // Deliberately NOT cleared: the principal reaches the ASYNC dispatch.
        authenticate(principal)
        mvc.perform(asyncDispatch(started)).andExpect(status().isTooManyRequests)

        verify(exactly = 1) {
            auditLogger.log(
                event = ScopeInterceptor.SUPER_ADMIN_ACTING,
                userId = principal.userId,
                keyId = any(),
                sourceIp = any(),
                userAgent = any(),
                details = any(),
            )
        }
    }

    private companion object {
        /** The launcher's emitters never time out; the test completes them itself. */
        const val NEVER_TIMEOUT = 0L
    }
}
