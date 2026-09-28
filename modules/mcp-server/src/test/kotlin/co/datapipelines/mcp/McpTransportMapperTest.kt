package co.datapipelines.mcp

import co.datapipelines.pipeline.RequestLimits
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.modelcontextprotocol.spec.McpError
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test

/**
 * The MCP transport's mapper (#291), read off the artifact the transport is handed.
 *
 * The SDK's stateless transport answers an unreadable body by writing its `McpError` EXCEPTION
 * through this mapper (`HttpServletStatelessServerTransport.responseError`). Measured on `/mcp`
 * before the fix: a 400 whose body was the serialized throwable — `stackTrace` with the class,
 * file and line of every frame. The mapper keeps the JSON-RPC error and drops the internals.
 */
class McpTransportMapperTest {
    private val mapper = McpServerFactory.transportMapper()

    @Test
    fun `an sdk error reaches the wire as its json-rpc error and message - no stack, cause or suppressed`() {
        val error = McpError.builder(McpSchema.ErrorCodes.INVALID_REQUEST).message("Invalid message format").build()
        val wire = mapper.writeValueAsString(error)
        wire shouldContain "\"jsonRpcError\""
        wire shouldContain "Invalid message format"
        wire shouldNotContain "stackTrace"
        wire shouldNotContain "className"
        wire shouldNotContain "\"cause\""
        wire shouldNotContain "\"suppressed\""
    }

    @Test
    fun `the mapper reads under the stated constraints`() {
        mapper.factory.streamReadConstraints().maxNestingDepth shouldBe RequestLimits.MAX_NESTING_DEPTH
        mapper.factory.streamReadConstraints().maxStringLength shouldBe RequestLimits.MAX_STRING_LENGTH
    }
}
