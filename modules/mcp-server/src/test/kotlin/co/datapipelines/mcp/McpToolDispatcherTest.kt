package co.datapipelines.mcp

import co.datapipelines.auth.AuditLogger
import co.datapipelines.auth.Scope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.modelcontextprotocol.spec.McpError
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/**
 * The dispatcher is where the §7.6 authorization gate, the §6.3 envelope and the §9.2 error
 * mapping live, so this is where they are proven.
 */
class McpToolDispatcherTest {
    private val auditLogger = mockk<AuditLogger>(relaxed = true)

    /** A tool that records whether it ran — the only way to prove a refusal did not execute. */
    private class SpyTool(
        name: String,
        private val body: () -> Any = { mapOf("ok" to true) },
    ) : McpTool {
        var calls: Int = 0

        override val definition: McpSchema.Tool =
            McpTools.tool(name, "spy", """{"type": "object", "properties": {}}""")

        override fun call(
            args: McpArguments,
            ctx: McpToolContext,
        ): Any {
            calls++
            return body()
        }
    }

    @Test
    fun `success carries the payload, isError false and the correlation id in _meta`() {
        val tool = SpyTool("pipelines_list") { listOf(mapOf("id" to "p1")) }
        val result = dispatcher(tool).call(McpFixtures.request("pipelines_list"), McpFixtures.ctx(Scope.READ))

        assertAll(
            { result.isError() shouldBe false },
            { McpFixtures.payloadOf(result)[0]["id"].asText() shouldBe "p1" },
            {
                result.meta()[McpToolResults.META_CORRELATION_ID] shouldBe McpFixtures.CORRELATION_ID.toString()
            },
        )
    }

    @Test
    fun `a principal without the tool's scope is refused and the tool never runs`() {
        val tool = SpyTool("pipelines_create")
        val result = dispatcher(tool).call(McpFixtures.request("pipelines_create"), McpFixtures.ctx(Scope.EXECUTE))

        val error = McpFixtures.payloadOf(result)["error"]
        assertAll(
            { result.isError() shouldBe true },
            { error["code"].asText() shouldBe PipelineErrorCodes.Auth.SCOPE_INSUFFICIENT },
            { error["details"]["required"].asText() shouldBe "author" },
            { error["details"]["tool"].asText() shouldBe "pipelines_create" },
            { tool.calls shouldBe 0 },
        )
    }

    /**
     * The §13 checklist item, once per tool: "one test per tool asserting the next-lower scope is
     * refused with `auth.scope.insufficient`". The lower scope is derived from the matrix itself,
     * so a matrix change re-derives the expectation instead of silently passing.
     */
    @Test
    fun `every tool refuses the next-lower scope`() {
        assertAll(
            ScopeMatrix.MCP_TOOL_MIN_SCOPE.map { (tool, required) ->
                {
                    val spy = SpyTool(tool)
                    val held = Scope.entries.filter { it.ordinal < required.ordinal }.toTypedArray()
                    val result = dispatcher(spy).call(McpFixtures.request(tool), McpFixtures.ctx(*held))
                    McpFixtures.payloadOf(result)["error"]["code"].asText() shouldBe PipelineErrorCodes.Auth.SCOPE_INSUFFICIENT
                    result.isError() shouldBe true
                    spy.calls shouldBe 0
                }
            },
        )
    }

    @Test
    fun `admin satisfies every tool because scopes are hierarchical`() {
        assertAll(
            ScopeMatrix.MCP_TOOL_MIN_SCOPE.keys.map { tool ->
                {
                    val spy = SpyTool(tool)
                    dispatcher(spy).call(McpFixtures.request(tool), McpFixtures.ctx(Scope.ADMIN)).isError() shouldBe false
                    spy.calls shouldBe 1
                }
            },
        )
    }

    @Test
    fun `a tool with no row in the auth matrix is refused, never executed`() {
        val tool = SpyTool("pipelines_delete")
        ScopeMatrix.requiredScopeForTool("pipelines_delete") shouldBe null

        val result = dispatcher(tool).call(McpFixtures.request("pipelines_delete"), McpFixtures.ctx(Scope.ADMIN))

        assertAll(
            { result.isError() shouldBe true },
            { McpFixtures.payloadOf(result)["error"]["code"].asText() shouldBe PipelineErrorCodes.Auth.SCOPE_INSUFFICIENT },
            { tool.calls shouldBe 0 },
        )
    }

    @Test
    fun `a catalogued domain failure becomes an isError result, not a protocol error`() {
        val tool =
            SpyTool("pipelines_get") {
                throw DatapipelinesException(
                    code = PipelineErrorCodes.Execution.NOT_FOUND,
                    message = "Pipeline gone.",
                    details = mapOf("pipeline_id" to "p1"),
                )
            }
        val result = dispatcher(tool).call(McpFixtures.request("pipelines_get"), McpFixtures.ctx(Scope.READ))
        val error = McpFixtures.payloadOf(result)["error"]

        assertAll(
            { result.isError() shouldBe true },
            { error["code"].asText() shouldBe PipelineErrorCodes.Execution.NOT_FOUND },
            { error["details"]["pipeline_id"].asText() shouldBe "p1" },
            { error["doc_url"].asText() shouldBe "https://docs.datapipelines.co/errors/pipeline-execution-not-found" },
            { result.meta()[McpToolResults.META_CORRELATION_ID] shouldBe McpFixtures.CORRELATION_ID.toString() },
        )
    }

    @Test
    fun `an unknown tool is a protocol error`() {
        val error =
            shouldThrow<McpError> {
                dispatcher(SpyTool("pipelines_list")).call(McpFixtures.request("nope"), McpFixtures.ctx(Scope.ADMIN))
            }
        error.jsonRpcError.code() shouldBe McpArguments.INVALID_PARAMS
    }

    @Test
    fun `every tool call is audited with tool, outcome, target and correlation id`() {
        val details = slot<Map<String, Any?>>()
        every { auditLogger.log(any(), any(), any(), any(), any(), capture(details)) } returns Unit

        dispatcher(SpyTool("pipelines_get")).call(
            McpFixtures.request("pipelines_get", mapOf("id" to McpFixtures.PIPELINE_ID.toString())),
            McpFixtures.ctx(Scope.READ),
        )

        verify { auditLogger.log(event = "mcp.tool.called", userId = McpFixtures.USER, keyId = any(), details = any()) }
        assertAll(
            { details.captured["tool"] shouldBe "pipelines_get" },
            { details.captured["outcome"] shouldBe "success" },
            { details.captured["target"] shouldBe McpFixtures.PIPELINE_ID.toString() },
            { details.captured["correlation_id"] shouldBe McpFixtures.CORRELATION_ID.toString() },
        )
    }

    /**
     * B2: without the catch-all, mcp-core maps any other throwable to `-32603` carrying
     * `getMessage()` verbatim — so a Redis outage would put an internal hostname into the agent's
     * LLM context (§13 forbids it) and write no audit row.
     */
    @Test
    fun `an uncatalogued fault is sanitized and audited, never echoed to the agent`() {
        val details = slot<Map<String, Any?>>()
        every { auditLogger.log(any(), any(), any(), any(), any(), capture(details)) } returns Unit
        val leak = "Unable to connect to Redis at redis-master.internal:6379"
        val tool = SpyTool("pipelines_list") { throw IllegalStateException(leak) }

        val error =
            shouldThrow<McpError> {
                dispatcher(tool).call(McpFixtures.request("pipelines_list"), McpFixtures.ctx(Scope.READ))
            }

        assertAll(
            { error.jsonRpcError.code() shouldBe McpArguments.INTERNAL_ERROR },
            { error.jsonRpcError.message() shouldNotContain "redis-master.internal" },
            { error.jsonRpcError.message() shouldNotContain "6379" },
            { error.jsonRpcError.message() shouldContain McpFixtures.CORRELATION_ID.toString() },
            // The audit row the old path never wrote.
            { details.captured["outcome"] shouldBe "internal_error" },
            { details.captured["tool"] shouldBe "pipelines_list" },
            { details.captured["correlation_id"] shouldBe McpFixtures.CORRELATION_ID.toString() },
        )
    }

    @Test
    fun `duplicate tool names are a construction error`() {
        shouldThrow<IllegalArgumentException> {
            McpToolDispatcher(listOf(SpyTool("pipelines_list"), SpyTool("pipelines_list")), auditLogger)
        }
    }

    @Test
    fun `the definitions it advertises are the tools it dispatches`() {
        val tools = listOf(SpyTool("pipelines_list"), SpyTool("pipelines_get"))
        val dispatcher = McpToolDispatcher(tools, auditLogger)

        dispatcher.definitions().map { it.name() } shouldContainExactly listOf("pipelines_list", "pipelines_get")
        dispatcher.definitions().all { it.inputSchema().isNotEmpty() }.shouldBeTrue()
        dispatcher
            .definitions()
            .first()
            .description()
            .shouldNotBeNull()
    }

    private fun dispatcher(vararg tools: McpTool) = McpToolDispatcher(tools.toList(), auditLogger)
}
