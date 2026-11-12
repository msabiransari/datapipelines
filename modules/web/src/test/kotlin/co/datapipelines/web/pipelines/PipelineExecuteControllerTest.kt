package co.datapipelines.web.pipelines

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.executor.ExecutorJson
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.RequestLimits
import co.datapipelines.web.api.ApiException
import com.fasterxml.jackson.core.JsonProcessingException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
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
 * pre-stream parameter-binding 400 with no reservation is covered in ExecutionStreamLauncherTest.
 */
class PipelineExecuteControllerTest {
    private val pipelines = mockk<PipelineRepository>()
    private val launcher = mockk<ExecutionStreamLauncher>()
    private val controller = PipelineExecuteController(co.datapipelines.web.pipelineServiceOver(pipelines), launcher)

    private val userId = UUID.randomUUID()
    private val pipelineId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()
    private val record =
        PipelineRecord(pipelineId, "p", "P", "d", userId, 5, Instant.EPOCH, Instant.EPOCH)

    private val bodyJson =
        """{"schema_version":1,"name":"p","display_name":"P","description":"d","parameters":{},""" +
            """"settings":{"tempdb":{"engine":"H2"}},"nodes":[]}"""

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate(
        role: co.datapipelines.auth.WorkspaceRole = co.datapipelines.auth.WorkspaceRole.VIEWER,
        authMethod: AuthMethod = AuthMethod.API_KEY,
    ) {
        val principal =
            AuthenticatedPrincipal(
                userId,
                "a@b.c",
                "A",
                authMethod,
                if (authMethod == AuthMethod.API_KEY) "dpk_x" else null,
                workspace = WorkspaceContext(workspaceId, "acme", role),
            )
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
        every { pipelines.findById(any(), pipelineId) } returns null

        shouldThrow<ApiException> { controller.execute(pipelineId, "{}", request()) }
            .code shouldBe "pipeline.execution.not_found"
        verify(exactly = 0) { launcher.launch(any()) }
    }

    @Test
    fun `no version defaults to the pipeline's current version`() {
        authenticate()
        every { pipelines.findById(any(), pipelineId) } returns record
        // D56: no version ⇒ the WORKING version, so the draft is looked up first. Released only here.
        every { pipelines.findDraftDetail(any(), pipelineId) } returns null
        every { pipelines.findExecutionSnapshot(any(), pipelineId, 5) } returns snapshot()
        val launch = slot<ExecuteLaunch>()
        every { launcher.launch(capture(launch)) } returns SseEmitter(0L)

        controller.execute(pipelineId, "{}", request(ttl = "900", idem = "k-1"))

        launch.captured.pipelineVersion shouldBe 5
        launch.captured.resultTtlSeconds shouldBe 900L
        launch.captured.idempotencyKey shouldBe "k-1"
    }

    @Test
    fun `no version runs the DRAFT when one exists, and a fresh pipeline runs its v1 draft`() {
        // D56 — "always run the LAST version": release v5 + draft v6 runs v6, and a pipeline that
        // has never been released (D55's shape: `current_version` null) runs its v1 draft rather
        // than answering that there is nothing to run.
        authenticate()
        every { pipelines.findById(any(), pipelineId) } returns record
        every { pipelines.findDraftDetail(any(), pipelineId) } returns draftDetail(version = 6)
        every { pipelines.findExecutionSnapshot(any(), pipelineId, 6) } returns
            snapshot(co.datapipelines.pipeline.PipelineVersionStatus.DRAFT)
        val launch = slot<ExecuteLaunch>()
        every { launcher.launch(capture(launch)) } returns SseEmitter(0L)

        controller.execute(pipelineId, "{}", request())
        launch.captured.pipelineVersion shouldBe 6

        every { pipelines.findById(any(), pipelineId) } returns record.copy(currentVersion = null)
        every { pipelines.findDraftDetail(any(), pipelineId) } returns draftDetail(version = 1)
        every { pipelines.findExecutionSnapshot(any(), pipelineId, 1) } returns
            snapshot(co.datapipelines.pipeline.PipelineVersionStatus.DRAFT)

        controller.execute(pipelineId, "{}", request())
        launch.captured.pipelineVersion shouldBe 1
    }

    @Test
    fun `a pipeline with no version at all is the version 404, not a 500`() {
        // The only "nothing to run" state (versioning §3.4): the sole draft of a never-released
        // pipeline was discarded. It is the same refusal an out-of-range explicit version gets.
        authenticate()
        every { pipelines.findById(any(), pipelineId) } returns record.copy(currentVersion = null)
        every { pipelines.findDraftDetail(any(), pipelineId) } returns null

        shouldThrow<ApiException> { controller.execute(pipelineId, "{}", request()) }
            .code shouldBe "pipeline.execution.not_found"
        verify(exactly = 0) { launcher.launch(any()) }
    }

    private fun draftDetail(version: Int) =
        co.datapipelines.pipeline.PipelineVersionDetail(
            pipelineId = pipelineId,
            version = version,
            status = co.datapipelines.pipeline.PipelineVersionStatus.DRAFT,
            bodyHash = "hash-v$version",
            createdAt = Instant.EPOCH,
            createdBy = userId,
        )

    private fun snapshot(
        status: co.datapipelines.pipeline.PipelineVersionStatus = co.datapipelines.pipeline.PipelineVersionStatus.RELEASED,
    ) = co.datapipelines.pipeline.PipelineExecutionSnapshot(bodyJson, "b".repeat(64), status, false)

    @Test
    fun `viewer is refused from the server write fact before launch even with a forged client grant`() {
        authenticate(authMethod = AuthMethod.OIDC)
        val backend = managedBackend()
        val subject = PipelineExecuteController(backend, launcher)
        // A missing permission check must launch successfully and fail this test's refusal assertion.
        every { launcher.launch(any()) } returns SseEmitter(0L)
        val refusal =
            shouldThrow<co.datapipelines.typesystem.DatapipelinesException> {
                subject.execute(pipelineId, """{"version":5,"lake_append_grant":{"granted_by":"$userId"}}""", request())
            }
        refusal.code shouldBe co.datapipelines.pipeline.PipelineErrorCodes.Lake.APPEND_FORBIDDEN
        verify(exactly = 0) { launcher.launch(any()) }
    }

    @Test
    fun `author launch carries the checked member identity instead of the client grant`() {
        authenticate(co.datapipelines.auth.WorkspaceRole.AUTHOR, AuthMethod.OIDC)
        val subject = PipelineExecuteController(managedBackend(), launcher)
        val captured = slot<ExecuteLaunch>()
        every { launcher.launch(capture(captured)) } returns SseEmitter(0L)
        subject.execute(pipelineId, """{"version":5,"lake_append_grant":{"granted_by":"${UUID.randomUUID()}"}}""", request())
        val grant = requireNotNull(captured.captured.lakeAppendGrant)
        grant.grantedBy shouldBe userId
        grant.principalKind shouldBe "session"
    }

    private fun managedBackend(): co.datapipelines.pipeline.PipelineService =
        mockk {
            every { findRecord(workspaceId, any(), pipelineId) } returns record
            every { findExecutable(workspaceId, any(), record, 5) } returns
                co.datapipelines.pipeline.PipelineService.ExecutablePipeline(
                    record,
                    5,
                    bodyJson,
                    co.datapipelines.pipeline
                        .PipelineDeserializer()
                        .readOrThrow(bodyJson),
                    writesManagedLake = true,
                )
        }

    @Test
    fun `a pinned version is used, and a non-positive version is a 400`() {
        authenticate()
        every { pipelines.findById(any(), pipelineId) } returns record
        every { pipelines.findExecutionSnapshot(any(), pipelineId, 2) } returns snapshot()
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
        every { pipelines.findById(any(), pipelineId) } returns record
        every { pipelines.findExecutionSnapshot(any(), pipelineId, 9) } returns null

        shouldThrow<ApiException> { controller.execute(pipelineId, """{"version":9}""", request()) }
            .code shouldBe "pipeline.execution.not_found"
    }

    @Test
    fun `a non-object body or parameters is a 400, never a stream`() {
        authenticate()
        every { pipelines.findById(any(), pipelineId) } returns record

        shouldThrow<ApiException> { controller.execute(pipelineId, "[1,2]", request()) }
            .code shouldBe "pipeline.execution.invalid_parameter_type"
        shouldThrow<ApiException> { controller.execute(pipelineId, """{"parameters":"nope"}""", request()) }
            .code shouldBe "pipeline.execution.invalid_parameter_type"
        verify(exactly = 0) { launcher.launch(any()) }
    }

    @Test
    fun `a number the plain-decimal echo cannot write is the caller's 400 naming the parameter (#291)`() {
        // Pre-fix: WRITE_BIGDECIMAL_AS_PLAIN refused scale -10000 with a JsonGenerationException
        // that no handler catches — a 500 for the caller's own value.
        authenticate()
        every { pipelines.findById(any(), pipelineId) } returns record
        val refusal =
            shouldThrow<ApiException> {
                controller.execute(pipelineId, """{"parameters":{"ok":1.5,"start":1e10000}}""", request())
            }
        refusal.code shouldBe "pipeline.execution.invalid_parameter_type"
        refusal.details["parameter"] shouldBe "start"
        refusal.details["reason"] shouldBe "too_many_digits"
        verify(exactly = 0) { launcher.launch(any()) }
    }

    @Test
    fun `a body past the stated nesting depth, or not JSON at all, is the route's named 400 (#291)`() {
        authenticate()
        every { pipelines.findById(any(), pipelineId) } returns record
        val deep = "{\"parameters\":{\"x\":" + "[".repeat(99) + "]".repeat(99) + "}}"
        listOf(deep, "{\"parameters\": ").forEach { body ->
            val refusal = shouldThrow<ApiException> { controller.execute(pipelineId, body, request()) }
            refusal.code shouldBe "pipeline.execution.invalid_parameter_type"
            refusal.details["reason"] shouldBe "malformed_json"
        }
        verify(exactly = 0) { launcher.launch(any()) }
    }

    @Test
    fun `the malformed-body 400 quotes the parser's reason cut to its 200-character bound (#304)`() {
        authenticate()
        every { pipelines.findById(any(), pipelineId) } returns record
        val body = "{\"parameters\": " + "x".repeat(LONG_TOKEN_CHARS)
        // The input must make the parser's own reason longer than the bound, or the bound is
        // never exercised: read it with the route's request mapper, untruncated.
        val unbounded =
            shouldThrow<JsonProcessingException> { RequestLimits.requestMapper(ExecutorJson.mapper).readTree(body) }
        unbounded.originalMessage.length shouldBeGreaterThan MAX_READ_ERROR_CHARS
        val refusal = shouldThrow<ApiException> { controller.execute(pipelineId, body, request()) }
        refusal.message.shouldNotBeNull().length shouldBeLessThanOrEqual
            "The execute request body could not be read: ".length + MAX_READ_ERROR_CHARS
        refusal.code shouldBe "pipeline.execution.invalid_parameter_type"
        refusal.details["reason"] shouldBe "malformed_json"
        verify(exactly = 0) { launcher.launch(any()) }
    }

    @Test
    fun `the body is read before the pipeline is looked up - a malformed one costs no query (#291)`() {
        authenticate()
        shouldThrow<ApiException> { controller.execute(UUID.randomUUID(), "{", request()) }
            .details["reason"] shouldBe "malformed_json"
        verify(exactly = 0) { pipelines.findById(any(), any()) }
    }

    private companion object {
        /** `PipelineExecuteController.MAX_READ_ERROR_CHARS` (private there): the parser reason's bound. */
        const val MAX_READ_ERROR_CHARS = 200

        /** A bad token long enough that the parser's reason outruns the bound. */
        const val LONG_TOKEN_CHARS = 1000
    }
}
