package co.datapipelines.web.sse

import co.datapipelines.executor.ExecutionCancellationService
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ResultStore
import co.datapipelines.executor.ResultUrlFactory
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.web.api.ApiExceptionHandler
import co.datapipelines.web.executions.ExecutionsController
import co.datapipelines.web.executions.ResultCursor
import co.datapipelines.web.pipelines.ExecutionLauncher
import co.datapipelines.web.pipelines.PipelineExecuteController
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID

/**
 * Gate C, B6: a **pre-stream** error on an SSE endpoint (Accept: text/event-stream) must render
 * the §4.2 JSON error envelope — not a 406 from the producible-types check. A browser that opens
 * an execution stream against a deleted pipeline, or with an invalid version, is an SSE client:
 * its only acceptable error shape is the envelope.
 */
class SseContentNegotiationTest {
    private val pipelineId = UUID.randomUUID()
    private val executionId = UUID.randomUUID()

    private fun executeMvc() =
        MockMvcBuilders
            .standaloneSetup(
                PipelineExecuteController(
                    pipelines = mockk<PipelineRepository> { every { findById(any(), pipelineId) } returns null },
                    launcher = mockk<ExecutionLauncher>(),
                ),
            ).setControllerAdvice(ApiExceptionHandler())
            .build()

    private fun eventsMvc() =
        MockMvcBuilders
            .standaloneSetup(
                ExecutionsController(
                    executions = mockk<ExecutionRepository> { every { findById(any(), executionId) } returns null },
                    cancellation = mockk<ExecutionCancellationService>(),
                    cursor = mockk<ResultCursor>(),
                    resultStore = mockk<ResultStore>(),
                    resultUrls = mockk<ResultUrlFactory>(),
                    streamer = mockk<SseLogStreamer>(),
                ),
            ).setControllerAdvice(ApiExceptionHandler())
            .build()

    /**
     * The workspace-scoped controllers resolve the principal's workspace before any repository
     * read, so both endpoints need an authenticated principal carrying one.
     */
    private fun authenticate() {
        val principal =
            co.datapipelines.auth.AuthenticatedPrincipal(
                UUID.randomUUID(),
                "a@b.c",
                "A",
                setOf(co.datapipelines.auth.Scope.EXECUTE),
                co.datapipelines.auth.AuthMethod.API_KEY,
                "dpk_x",
                workspace =
                    co.datapipelines.auth
                        .WorkspaceContext(UUID.randomUUID(), "acme"),
            )
        org.springframework.security.core.context.SecurityContextHolder
            .getContext()
            .authentication =
            org.springframework.security.authentication
                .UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    @Test
    fun `execute with an unknown pipeline and an SSE Accept header returns the 404 envelope`() {
        authenticate()
        try {
            executeMvc()
                .perform(
                    post("/api/v1/pipelines/{id}/execute", pipelineId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE),
                ).andExpect(status().isNotFound)
                .andExpect(jsonPath("$.error.code").value("pipeline.execution.not_found"))
                .andExpect(jsonPath("$.schema_version").value(1))
        } finally {
            org.springframework.security.core.context.SecurityContextHolder
                .clearContext()
        }
    }

    @Test
    fun `events replay with an unknown execution and an SSE Accept header returns the 404 envelope`() {
        authenticate()
        try {
            eventsMvc()
                .perform(
                    get("/api/v1/executions/{id}/events", executionId)
                        .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE),
                ).andExpect(status().isNotFound)
                .andExpect(jsonPath("$.error.code").value("result.execution_not_found"))
        } finally {
            org.springframework.security.core.context.SecurityContextHolder
                .clearContext()
        }
    }
}
