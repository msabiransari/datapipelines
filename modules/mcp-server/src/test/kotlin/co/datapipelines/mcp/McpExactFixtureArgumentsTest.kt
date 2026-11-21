package co.datapipelines.mcp

import co.datapipelines.application.templates.TemplateEvaluateService
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.templates.FixtureInputs
import co.datapipelines.templates.ScriptTestInput
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The transport keeps the exact text of the authored-fixture arguments (#1038, review C2): the
 * SDK's plain parse rounds a long fraction to a double and keeps a repeated member's last copy, so
 * the transport mapper copies `input` and `tests` token by token into the request's `_meta` and the
 * dispatcher hands that text to the tool, which binds the test input from it.
 */
class McpExactFixtureArgumentsTest {
    private val mapper =
        ExactRequestBodyJsonMapper(
            io.modelcontextprotocol.json.jackson2
                .JacksonMcpJsonMapper(McpServerFactory.transportMapper()),
        )

    private object NoAudit : AuditEventSink {
        override fun log(
            event: String,
            userId: UUID?,
            keyId: String?,
            sourceIp: String?,
            userAgent: String?,
            details: Map<String, Any?>,
        ) = Unit
    }

    private fun callOf(params: String): McpSchema.CallToolRequest {
        val message = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":$params}"""
        val request = McpSchema.deserializeJsonRpcMessage(mapper, message) as McpSchema.JSONRPCRequest
        return mapper.convertValue(request.params(), McpSchema.CallToolRequest::class.java)
    }

    private fun exactOf(request: McpSchema.CallToolRequest): Map<*, *>? =
        request.meta()?.get(ExactRequestBodyJsonMapper.EXACT_ARGUMENTS) as? Map<*, *>

    @Test
    fun `the input and tests arguments travel as exact text, repeated members and long fractions included`() {
        val input = """{"inputs":{"order":{"price":$LONG_FRACTION,"a":1,"a":2}}}"""
        val tests = """[{"name":"c","input":{"inputs":{"order":{"p":1.50}}},"expect":{"output":[]}}]"""
        val request = callOf("""{"name":"templates_evaluate","arguments":{"id":"t","input":$input,"tests":$tests}}""")
        exactOf(request) shouldBe mapOf("input" to input, "tests" to tests)
        // The ordinary arguments are the SDK's lenient reading, unchanged.
        val parsed = (request.arguments()["input"] as Map<*, *>)["inputs"] as Map<*, *>
        (parsed["order"] as Map<*, *>)["a"] shouldBe 2
    }

    @Test
    fun `a client-supplied copy of the exact-arguments meta key is dropped, and other meta is kept`() {
        val forged =
            """{"name":"templates_evaluate","arguments":{"id":"t"},""" +
                """"_meta":{"progressToken":"p1","${ExactRequestBodyJsonMapper.EXACT_ARGUMENTS}":{"input":"{\"inputs\":{}}"}}}"""
        val request = callOf(forged)
        request.meta()!! shouldNotContainKey ExactRequestBodyJsonMapper.EXACT_ARGUMENTS
        request.meta()!!["progressToken"] shouldBe "p1"
        val replaced =
            callOf(
                """{"name":"templates_evaluate","arguments":{"id":"t","input":{"inputs":{"x":1}}},""" +
                    """"_meta":{"${ExactRequestBodyJsonMapper.EXACT_ARGUMENTS}":{"input":"{\"inputs\":{\"x\":9}}"}}}""",
            )
        exactOf(replaced) shouldBe mapOf("input" to """{"inputs":{"x":1}}""")
    }

    @Test
    fun `templates_evaluate binds its test input from the transport's exact text`() {
        val service = mockk<TemplateEvaluateService>()
        val bound = slot<ScriptTestInput>()
        every { service.evaluate(any(), any(), any(), capture(bound), any()) } returns
            TemplateEvaluateService.Evaluation(output = emptyList<Any>(), rejects = emptyList(), invariants = emptyList())
        val dispatcher = McpToolDispatcher(listOf(TemplatesEvaluateTool(service)), NoAudit)
        val input = """{"inputs":{"order":{"price":$LONG_FRACTION},"order":{"price":1}}}"""
        val request = callOf("""{"name":"templates_evaluate","arguments":{"id":"test/x.jsonata","input":$input}}""")

        dispatcher.call(request, McpFixtures.ctx()).isError() shouldBe false
        val inputs = bound.captured.inputs.shouldBeInstanceOf<FixtureInputs>()
        inputs.isRepeated("order") shouldBe true
        inputs.exactText("order") shouldBe """{"price":1}"""

        val single =
            callOf(
                """{"name":"templates_evaluate","arguments":{"id":"test/x.jsonata",""" +
                    """"input":{"inputs":{"order":{"price":$LONG_FRACTION}}}}}""",
            )
        dispatcher.call(single, McpFixtures.ctx()).isError() shouldBe false
        bound.captured.inputs
            .shouldBeInstanceOf<FixtureInputs>()
            .exactText("order") shouldBe """{"price":$LONG_FRACTION}"""
    }

    private companion object {
        const val LONG_FRACTION = "0.123456789012345678901234567890"
    }
}
