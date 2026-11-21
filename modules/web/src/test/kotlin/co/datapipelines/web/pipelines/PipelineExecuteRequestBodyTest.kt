package co.datapipelines.web.pipelines

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.body.StructuredBody
import co.datapipelines.typesystem.body.StructuredBodyDecoder
import co.datapipelines.typesystem.body.StructuredBodyLimits
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
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
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Framework execute's `request_body` fixture (#1038; rest-api §6.1.4): read from the raw text by the shared bounded
 * decoder, passed to the launch separately from `parameters`, never echoed into `parameters_json`, and refused —
 * before any pipeline lookup — with the execution code, a reason and no value.
 */
class PipelineExecuteRequestBodyTest {
    private val pipelines = mockk<PipelineRepository>()
    private val launcher = mockk<ExecutionStreamLauncher>()
    private val controller =
        PipelineExecuteController(
            co.datapipelines.web.pipelineServiceOver(pipelines),
            launcher,
            StructuredBodyDecoder(StructuredBodyLimits(maxBytes = 256, maxDepth = 4)),
        )

    private val userId = UUID.randomUUID()
    private val pipelineId = UUID.randomUUID()
    private val record = PipelineRecord(pipelineId, "p", "P", "d", userId, 5, Instant.EPOCH, Instant.EPOCH)
    private val bodyJson =
        """{"schema_version":1,"name":"p","display_name":"P","description":"d","parameters":{},""" +
            """"settings":{"tempdb":{"engine":"H2"}},"nodes":[]}"""

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate() {
        val principal =
            AuthenticatedPrincipal(
                userId,
                "a@b.c",
                "A",
                AuthMethod.API_KEY,
                "dpk_x",
                workspace = WorkspaceContext(UUID.randomUUID(), "acme", co.datapipelines.auth.WorkspaceRole.AUTHOR),
            )
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private fun launched(body: String): ExecuteLaunch {
        authenticate()
        every { pipelines.findById(any(), pipelineId) } returns record
        every { pipelines.findExecutionSnapshot(any(), pipelineId, 5) } returns
            co.datapipelines.pipeline.PipelineExecutionSnapshot(
                bodyJson,
                "b".repeat(64),
                co.datapipelines.pipeline.PipelineVersionStatus.RELEASED,
                false,
            )
        val launch = slot<ExecuteLaunch>()
        every { launcher.launch(capture(launch)) } returns SseEmitter(0L)
        controller.execute(pipelineId, body, MockHttpServletRequest("POST", "/api/v1/pipelines/$pipelineId/execute"))
        return launch.captured
    }

    @Test
    fun `the fixture is decoded exactly and passed beside the parameters, never inside them`() {
        val launch =
            launched(
                """{"version":5,"parameters":{"channel":"web"},"request_body":[{"id":"9007199254740993","price":1.10},{"other":{}}]}""",
            )
        val items = launch.requestBody.shouldBeInstanceOf<StructuredBody.ArrayRoot>().items
        items[0] shouldBe mapOf("id" to "9007199254740993", "price" to BigDecimal("1.10"))
        items[1] shouldBe mapOf("other" to emptyMap<String, Any?>())
        launch.parameters.keys shouldBe setOf("channel")
        launch.parametersJson shouldNotContain "request_body"
        launch.parametersJson shouldNotContain "9007199254740993"
    }

    @Test
    fun `no fixture is absent, and an empty object stays an empty object`() {
        launched("""{"version":5,"parameters":{}}""").requestBody shouldBe StructuredBody.Absent
        launched("""{"version":5,"request_body":{}}""").requestBody shouldBe StructuredBody.ObjectRoot(emptyMap())
    }

    @Test
    fun `a duplicate, an invalid root and an over-bound fixture are refused before the pipeline is looked up, naming no value`() {
        authenticate()
        val cases =
            mapOf(
                """{"request_body":{"sku":"secret-a","sku":"secret-b"}}""" to "duplicate_member",
                """{"request_body":{},"request_body":{}}""" to "duplicate_member",
                """{"request_body":"secret-text"}""" to "invalid_root",
                """{"request_body":null}""" to "invalid_root",
                """{"request_body":[{"a":1},"secret-text"]}""" to "invalid_root",
                """{"request_body":{"a":{"b":{"c":{"d":{"e":1}}}}}}""" to "too_deep",
                """{"request_body":{"note":"secret-${"x".repeat(300)}"}}""" to "too_many_bytes",
            )
        cases.forEach { (body, reason) ->
            withClue(body.take(60)) {
                val refused = shouldThrow<DatapipelinesException> { controller.execute(pipelineId, body, MockHttpServletRequest()) }
                refused.code shouldBe PipelineErrorCodes.Execution.REQUEST_BODY_INVALID
                refused.details["reason"] shouldBe reason
                refused.message.orEmpty() shouldNotContain "secret"
                refused.details.toString() shouldNotContain "secret"
            }
        }
        verify(exactly = 0) { pipelines.findById(any(), any()) }
        verify(exactly = 0) { launcher.launch(any()) }
    }
}
