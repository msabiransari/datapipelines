package co.datapipelines.mcp

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import java.io.IOException
import java.math.BigDecimal

/**
 * The transport mapper's `request_body` guard (#1038, review R1), read at the seam the SDK uses:
 * the raw JSON-RPC text in, the `CallToolRequest` the tool receives out. Duplicate members and
 * long fractions are decided HERE, before the plain parse discards them.
 */
class ExactRequestBodyJsonMapperTest {
    private val mapper =
        ExactRequestBodyJsonMapper(
            io.modelcontextprotocol.json.jackson2
                .JacksonMcpJsonMapper(McpServerFactory.transportMapper()),
        )

    private fun message(arguments: String) =
        """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"pipelines_execute","arguments":$arguments}}"""

    private fun callOf(arguments: String): McpSchema.CallToolRequest {
        val request =
            McpSchema.deserializeJsonRpcMessage(mapper, message(arguments)) as McpSchema.JSONRPCRequest
        return mapper.convertValue(request.params(), McpSchema.CallToolRequest::class.java)
    }

    private fun refusedFor(arguments: String): Throwable = shouldThrow<IOException> { callOf(arguments) }

    @Test
    fun `a fraction beyond double precision reaches the tool exactly`() {
        val fraction = "0.1234567890123456789012345678901234567890"
        val body = callOf("""{"id":"x","parameters":{},"request_body":{"price":$fraction,"n":[1.50,2]}}""").arguments()["request_body"]
        val price = (body as Map<*, *>)["price"]
        price.shouldBeInstanceOf<BigDecimal>() shouldBe BigDecimal(fraction)
        ((body["n"] as List<*>)[0] as BigDecimal).toPlainString() shouldBe "1.50"
        (body["n"] as List<*>)[1] shouldBe 2
    }

    @Test
    fun `a repeated member at the top, nested, in an array element or in a repeated request_body is refused naming nothing`() {
        listOf(
            """{"id":"x","parameters":{},"request_body":{"sku":"A","sku":"B"}}""",
            """{"id":"x","parameters":{},"request_body":{"o":{"deep":{"k":1,"k":2}}}}""",
            """{"id":"x","parameters":{},"request_body":[{"a":1},{"b":1,"b":2}]}""",
            """{"id":"x","parameters":{},"request_body":{"a":1},"request_body":{"a":2}}""",
            """{"id":"x","parameters":{},"request_body":{"a":1},"request_body":{"a":2}}""",
        ).forEach { arguments ->
            val refusal = refusedFor(arguments)
            refusal.message.orEmpty() shouldNotContain "sku"
            refusal.message.orEmpty() shouldNotContain "deep"
        }
    }

    @Test
    fun `a body made ambiguous by a repeated arguments or params member is refused`() {
        val twiceArguments =
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"pipelines_execute",""" +
                """"arguments":{"id":"x","parameters":{},"request_body":{"a":1}},"arguments":{"id":"x","parameters":{}}}}"""
        shouldThrow<IOException> { McpSchema.deserializeJsonRpcMessage(mapper, twiceArguments) }
        val twiceParams =
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"a","arguments":{"request_body":{"a":1}}},""" +
                """"params":{"name":"b","arguments":{}}}"""
        shouldThrow<IOException> { McpSchema.deserializeJsonRpcMessage(mapper, twiceParams) }
    }

    @Test
    fun `objects, heterogeneous arrays, singletons, empty containers and null all arrive as sent`() {
        callOf("""{"id":"x","parameters":{},"request_body":{}}""").arguments()["request_body"] shouldBe emptyMap<String, Any?>()
        callOf("""{"id":"x","parameters":{},"request_body":[]}""").arguments()["request_body"] shouldBe emptyList<Any?>()
        callOf("""{"id":"x","parameters":{},"request_body":[{"a":1}]}""").arguments()["request_body"] shouldBe listOf(mapOf("a" to 1))
        callOf("""{"id":"x","parameters":{},"request_body":[{"a":1},{"b":{"c":null}}]}""").arguments()["request_body"] shouldBe
            listOf(mapOf("a" to 1), mapOf("b" to mapOf("c" to null)))
        val sentNull = callOf("""{"id":"x","parameters":{},"request_body":null}""").arguments()
        sentNull shouldContainKey "request_body"
        sentNull["request_body"] shouldBe null
    }

    @Test
    fun `nothing outside request_body changes - other arguments keep their lenient double reading`() {
        val arguments = callOf("""{"id":"x","parameters":{"p":0.5,"p":0.25},"limit":1.5}""").arguments()
        arguments shouldBe mapOf("id" to "x", "parameters" to mapOf("p" to 0.25), "limit" to 1.5)
        arguments.containsKey("request_body") shouldBe false
    }

    @Test
    fun `a message that is not a tool call, or is not an object, passes through to the delegate unchanged`() {
        val ping = """{"jsonrpc":"2.0","id":2,"method":"ping"}"""
        McpSchema.deserializeJsonRpcMessage(mapper, ping).shouldBeInstanceOf<McpSchema.JSONRPCRequest>()
        shouldThrow<IOException> { McpSchema.deserializeJsonRpcMessage(mapper, "[1,") }
    }
}
