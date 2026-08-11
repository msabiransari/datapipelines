package co.datapipelines.web.pipelines

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.web.api.ApiException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Instant
import java.util.UUID

/**
 * §6.1 request handling over a mocked launcher: body parsing, version default/pin, the unknown
 * (or soft-deleted) pipeline 404, and the parameters/result-TTL/idempotency plumbing. The
 * pre-stream parameter-binding 400 with no reservation is covered in ExecutionLauncherTest.
 */
class PipelineExecuteControllerTest {
    private val pipelines = mockk<PipelineRepository>()
    private val launcher = mockk<ExecutionLauncher>()
    private val controller = PipelineExecuteController(pipelines, launcher)

    private val userId = UUID.randomUUID()
    private val pipelineId = UUID.randomUUID()
    private val record =
        PipelineRecord(pipelineId, "p", "P", "d", userId, 5, false, Instant.EPOCH, Instant.EPOCH)

    private val bodyJson =
        """{"schema_version":1,"name":"p","display_name":"P","description":"d","parameters":{},""" +
            """"settings":{"tempdb":{"engine":"H2"}},"nodes":[]}"""

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate() {
        val principal = AuthenticatedPrincipal(userId, "a@b.c", "A", setOf(Scope.EXECUTE), AuthMethod.API_KEY, "dpk_x")
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private fun request(
        ttl: String? = null,
        idem: String? = null,
    ): MockHttpServletRequest {
        val req = MockHttpServletRequest("POST", "/api/v1/pipelines/$pipelineId/execute")
        ttl?.let { req.addHeader("DP-Result-TTL-Seconds", it) }
        idem?.let { req.addHeader("Idempotency-Key", it) }
        return req
    }

    @Test
    fun `an unknown pipeline is the catalogued 404 before anything launches`() {
        authenticate()
        every { pipelines.findById(pipelineId) } returns null

        shouldThrow<ApiException> { controller.execute(pipelineId, "{}", request()) }
            .code shouldBe "pipeline.execution.not_found"
        verify(exactly = 0) { launcher.launch(any()) }
    }

    @Test
    fun `no version defaults to the pipeline's current version`() {
        authenticate()
        every { pipelines.findById(pipelineId) } returns record
        every { pipelines.findVersionBody(pipelineId, 5) } returns bodyJson
        val launch = slot<ExecuteLaunch>()
        every { launcher.launch(capture(launch)) } returns SseEmitter(0L)

        controller.execute(pipelineId, "{}", request(ttl = "900", idem = "k-1"))

        launch.captured.pipelineVersion shouldBe 5
        launch.captured.resultTtlSeconds shouldBe 900L
        launch.captured.idempotencyKey shouldBe "k-1"
    }

    @Test
    fun `a pinned version is used, and a non-positive version is a 400`() {
        authenticate()
        every { pipelines.findById(pipelineId) } returns record
        every { pipelines.findVersionBody(pipelineId, 2) } returns bodyJson
        val launch = slot<ExecuteLaunch>()
        every { launcher.launch(capture(launch)) } returns SseEmitter(0L)

        controller.execute(pipelineId, """{"version":2,"parameters":{"start_date":"2026-01-01"}}""", request())
        launch.captured.pipelineVersion shouldBe 2
        launch.captured.parameters.keys shouldBe setOf("start_date")

        shouldThrow<ApiException> { controller.execute(pipelineId, """{"version":0}""", request()) }
            .code shouldBe "pipeline.execution.invalid_parameter_type"
    }

    @Test
    fun `an unknown pinned version is the version 404`() {
        authenticate()
        every { pipelines.findById(pipelineId) } returns record
        every { pipelines.findVersionBody(pipelineId, 9) } returns null

        shouldThrow<ApiException> { controller.execute(pipelineId, """{"version":9}""", request()) }
            .code shouldBe "pipeline.execution.not_found"
    }

    @Test
    fun `a non-object body or parameters is a 400, never a stream`() {
        authenticate()
        every { pipelines.findById(pipelineId) } returns record

        shouldThrow<ApiException> { controller.execute(pipelineId, "[1,2]", request()) }
            .code shouldBe "pipeline.execution.invalid_parameter_type"
        shouldThrow<ApiException> { controller.execute(pipelineId, """{"parameters":"nope"}""", request()) }
            .code shouldBe "pipeline.execution.invalid_parameter_type"
        verify(exactly = 0) { launcher.launch(any()) }
    }
}
