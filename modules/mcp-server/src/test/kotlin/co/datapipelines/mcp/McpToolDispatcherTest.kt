package co.datapipelines.mcp

import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuditLogger
import co.datapipelines.auth.KeyRole
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
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
import java.util.UUID

/**
 * The dispatcher is where the §7.6 authorization gate, the §6.3 envelope and the §9.2 error
 * mapping live, so this is where they are proven.
 *
 * 052 proves the audit contract here too: `mcp.tool.called` for every call, plus exactly one
 * `mcp.tool.write` per invoked MUTATING call. The assertions read a REAL in-memory sink
 * ([RecordingAuditSink]) — a strict mock would make a MISSING emission unobservable
 * (MISTAKES.md), so the recorded rows are asserted, never the interaction.
 */
class McpToolDispatcherTest {
    private val auditLogger = mockk<AuditLogger>(relaxed = true)

    /**
     * A real in-memory [AuditEventSink]: appends what production would hand the sink, in
     * order. `writes()`/`calls()` are the greps an operator would run on `audit_log`.
     */
    private class RecordingAuditSink : AuditEventSink {
        data class Row(
            val event: String,
            val userId: UUID?,
            val keyId: String?,
            val details: Map<String, Any?>,
        )

        val rows = mutableListOf<Row>()

        override fun log(
            event: String,
            userId: UUID?,
            keyId: String?,
            sourceIp: String?,
            userAgent: String?,
            details: Map<String, Any?>,
        ) {
            rows += Row(event, userId, keyId, details)
        }

        fun writes() = rows.filter { it.event == "mcp.tool.write" }

        fun calls() = rows.filter { it.event == "mcp.tool.called" }
    }

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
        val result = dispatcher(tool).call(McpFixtures.request("pipelines_list"), McpFixtures.ctx())

        assertAll(
            { result.isError() shouldBe false },
            { McpFixtures.payloadOf(result)[0]["id"].asText() shouldBe "p1" },
            {
                result.meta()[McpToolResults.META_CORRELATION_ID] shouldBe McpFixtures.CORRELATION_ID.toString()
            },
        )
    }

    /**
     * #215 A.4: a KEY ROLE that lacks the tool's catalog permission is refused with
     * `auth.role_required`, `held` naming the key role — the successor of the scope refusal. (No
     * key-role credential reaches `/mcp` in production — `McpAuthFilter` refuses the kind first —
     * so this pins the matrix's answer at the dispatcher, the layer that must not depend on it.)
     */
    @Test
    fun `a key role lacking the tool's permission is refused and the tool never runs`() {
        val tool = SpyTool("pipelines_create")
        val apiCaller = McpFixtures.principal().copy(keyKind = ApiKeyKind.ENDPOINT, keyRole = KeyRole.API_CALLER)
        val result = dispatcher(tool).call(McpFixtures.request("pipelines_create"), McpToolContext(apiCaller, McpFixtures.CORRELATION_ID))

        val error = McpFixtures.payloadOf(result)["error"]
        assertAll(
            { result.isError() shouldBe true },
            { error["code"].asText() shouldBe PipelineErrorCodes.Auth.ROLE_REQUIRED },
            { error["details"]["required"].asText() shouldBe "pipeline.create" },
            { error["details"]["held"].asText() shouldBe "api_caller" },
            { error["details"]["tool"].asText() shouldBe "pipelines_create" },
            { tool.calls shouldBe 0 },
        )
    }

    /**
     * The §13 checklist item, once per tool and per role an MCP key can act with (PK4: viewer,
     * promoter, author — a workspace admin's key is capped at author): the tool runs exactly when
     * the role's column holds the permission the catalog entry declares, and is refused with the
     * member-role code otherwise. The expectation is derived from [RolePermissions] and
     * [McpToolCatalog], so a change to either re-derives it instead of silently passing.
     */
    @Test
    fun `every tool admits exactly the MCP-key roles whose column holds its permission`() {
        val roles = listOf(WorkspaceRole.VIEWER, WorkspaceRole.PROMOTER, WorkspaceRole.AUTHOR)
        assertAll(
            McpToolCatalog.ENTRIES.flatMap { entry ->
                roles.map { role ->
                    {
                        val spy = SpyTool(entry.name)
                        val context = WorkspaceContext(McpFixtures.WORKSPACE_ID, "acme", role)
                        val result = dispatcher(spy).call(McpFixtures.request(entry.name), McpFixtures.ctx(workspace = context))
                        val admitted = context.permits(entry.permission)
                        withClue("${entry.name} as ${role.wire}") {
                            result.isError() shouldBe !admitted
                            spy.calls shouldBe if (admitted) 1 else 0
                            if (!admitted) {
                                McpFixtures.payloadOf(result)["error"]["code"].asText() shouldBe
                                    PipelineErrorCodes.Auth.KEY_ISSUER_ROLE_LOST
                            }
                        }
                    }
                }
            },
        )
    }

    /** Record §3.4: no MCP tool needs more than author — so the cap costs an admin's agent nothing. */
    @Test
    fun `an author's key satisfies every tool - the PK4 cap withholds nothing an agent can call`() {
        val author = WorkspaceContext(McpFixtures.WORKSPACE_ID, "acme", WorkspaceRole.AUTHOR)
        assertAll(
            McpToolCatalog.NAMES.map { tool ->
                {
                    val spy = SpyTool(tool)
                    withClue(tool) {
                        dispatcher(spy).call(McpFixtures.request(tool), McpFixtures.ctx(workspace = author)).isError() shouldBe false
                        spy.calls shouldBe 1
                    }
                }
            },
        )
    }

    /**
     * #215 A.6, the MCP surface: a key whose issuer's ROLE lacks the tool's catalog permission is
     * refused on the role axis (the demoted-issuer code, D-R12), and the details name the
     * PERMISSION the tool declares and the ROLE it was judged as. The REST and partial twins are
     * `ScopeInterceptorTest`'s.
     */
    @Test
    fun `a role refusal names the tool's catalog permission and the issuer's role`() {
        val tool = SpyTool("pipelines_create")
        val viewer = WorkspaceContext(McpFixtures.WORKSPACE_ID, "acme", WorkspaceRole.VIEWER)
        val result = dispatcher(tool).call(McpFixtures.request("pipelines_create"), McpFixtures.ctx(workspace = viewer))

        val error = McpFixtures.payloadOf(result)["error"]
        assertAll(
            { result.isError() shouldBe true },
            { error["code"].asText() shouldBe PipelineErrorCodes.Auth.KEY_ISSUER_ROLE_LOST },
            { error["details"]["required"].asText() shouldBe "pipeline.create" },
            { error["details"]["held"].asText() shouldBe "viewer" },
            { error["details"]["tool"].asText() shouldBe "pipelines_create" },
            { tool.calls shouldBe 0 },
        )
    }

    @Test
    fun `a tool the catalog declares no permission for is refused, never executed`() {
        val tool = SpyTool("pipelines_delete")
        McpToolCatalog.permissionOf("pipelines_delete") shouldBe null

        val result = dispatcher(tool).call(McpFixtures.request("pipelines_delete"), McpFixtures.ctx())

        assertAll(
            { result.isError() shouldBe true },
            { McpFixtures.payloadOf(result)["error"]["code"].asText() shouldBe PipelineErrorCodes.Auth.PERMISSION_UNDECLARED },
            { McpFixtures.payloadOf(result)["error"]["details"]["tool"].asText() shouldBe "pipelines_delete" },
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
        val result = dispatcher(tool).call(McpFixtures.request("pipelines_get"), McpFixtures.ctx())
        val error = McpFixtures.payloadOf(result)["error"]

        assertAll(
            { result.isError() shouldBe true },
            { error["code"].asText() shouldBe PipelineErrorCodes.Execution.NOT_FOUND },
            { error["details"]["pipeline_id"].asText() shouldBe "p1" },
            { error["doc_url"].asText() shouldBe "https://datapipelines.co/docs/pipeline-contract#133-pipeline-execution-run-time" },
            { result.meta()[McpToolResults.META_CORRELATION_ID] shouldBe McpFixtures.CORRELATION_ID.toString() },
        )
    }

    @Test
    fun `an unknown tool is a protocol error`() {
        val error =
            shouldThrow<McpError> {
                dispatcher(SpyTool("pipelines_list")).call(McpFixtures.request("nope"), McpFixtures.ctx())
            }
        error.jsonRpcError.code() shouldBe McpArguments.INVALID_PARAMS
    }

    @Test
    fun `every tool call is audited with tool, outcome, target and correlation id`() {
        val details = slot<Map<String, Any?>>()
        every { auditLogger.log(any(), any(), any(), any(), any(), capture(details)) } returns Unit

        dispatcher(SpyTool("pipelines_get")).call(
            McpFixtures.request("pipelines_get", mapOf("id" to McpFixtures.PIPELINE_ID.toString())),
            McpFixtures.ctx(),
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
                dispatcher(tool).call(McpFixtures.request("pipelines_list"), McpFixtures.ctx())
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

    /**
     * 052 gate 3, case 1 — a mutating tool call produces exactly ONE `mcp.tool.write` event
     * carrying the documented fields: actor (key id and owner), tool, target, outcome,
     * elapsed, correlation id. Falsify by removing the dispatcher's write emission: this
     * goes red because the recording sink is empty, not because a mock was not called.
     */
    @Test
    fun `a mutating tool call writes exactly one mcp tool write event with the documented fields`() {
        val sink = RecordingAuditSink()
        val dispatcher = McpToolDispatcher(listOf(SpyTool("pipelines_create")), sink)

        dispatcher.call(
            McpFixtures.request("pipelines_create", mapOf("name" to "nightly_etl")),
            McpFixtures.ctx(),
        )

        sink.writes() shouldHaveSize 1
        val row = sink.writes().single()
        assertAll(
            { row.userId shouldBe McpFixtures.USER },
            { row.keyId shouldBe "dpk_ABCDEFGHIJKL" },
            { row.details["tool"] shouldBe "pipelines_create" },
            { row.details["outcome"] shouldBe "success" },
            { row.details["target"] shouldBe "nightly_etl" },
            { row.details["correlation_id"] shouldBe McpFixtures.CORRELATION_ID.toString() },
            { (row.details["elapsed_ms"] as Long) shouldBeGreaterThanOrEqual 0L },
            // The per-call event still exists — the write event is additive, not a replacement.
            { sink.calls() shouldHaveSize 1 },
        )
    }

    /** 052 gate 3, case 2 — a read tool produces no write event. */
    @Test
    fun `a read tool call writes no mcp tool write event`() {
        val sink = RecordingAuditSink()
        val dispatcher = McpToolDispatcher(listOf(SpyTool("pipelines_list")), sink)

        dispatcher.call(McpFixtures.request("pipelines_list"), McpFixtures.ctx())

        assertAll(
            { sink.writes() shouldHaveSize 0 },
            { sink.calls() shouldHaveSize 1 },
        )
    }

    /** 052 gate 3, case 3 — a failing mutating call writes one event WITH the error code, and the tool's error is unchanged. */
    @Test
    fun `a failing mutating call writes one write event with the error code and the tool's error is unchanged`() {
        val sink = RecordingAuditSink()
        val tool =
            SpyTool("pipelines_execute") {
                throw DatapipelinesException(
                    code = PipelineErrorCodes.Execution.NOT_FOUND,
                    message = "Pipeline gone.",
                )
            }
        val dispatcher = McpToolDispatcher(listOf(tool), sink)

        val result =
            dispatcher.call(
                McpFixtures.request("pipelines_execute", mapOf("id" to McpFixtures.PIPELINE_ID.toString())),
                McpFixtures.ctx(),
            )

        sink.writes() shouldHaveSize 1
        val row = sink.writes().single()
        assertAll(
            { result.isError() shouldBe true },
            { McpFixtures.payloadOf(result)["error"]["code"].asText() shouldBe PipelineErrorCodes.Execution.NOT_FOUND },
            { row.details["outcome"] shouldBe "error" },
            { row.details["code"] shouldBe PipelineErrorCodes.Execution.NOT_FOUND },
        )
    }

    /**
     * The ruling's target discipline for node runs: pipeline id, the node id, and the version
     * the call named — identifiers only, never parameters. This is the row that makes a
     * `pipelines_execute_node` DML run greppable despite §6.2.20's no-execution-row ratification.
     */
    @Test
    fun `a node run's write event carries the node id and the version it was pointed at`() {
        val sink = RecordingAuditSink()
        val dispatcher = McpToolDispatcher(listOf(SpyTool("pipelines_execute_node")), sink)

        dispatcher.call(
            McpFixtures.request(
                "pipelines_execute_node",
                mapOf(
                    "pipeline_id" to McpFixtures.PIPELINE_ID.toString(),
                    "node_id" to "fetch",
                    "version" to 3,
                ),
            ),
            McpFixtures.ctx(),
        )

        val row = sink.writes().single()
        assertAll(
            { row.details["target"] shouldBe McpFixtures.PIPELINE_ID.toString() },
            { row.details["node_id"] shouldBe "fetch" },
            { row.details["version"] shouldBe 3 },
        )
    }

    /** The §7.6 refusal never invoked the tool, so it exercised no write path — the refusal rides `mcp.tool.called` alone. */
    @Test
    fun `a permission-refused mutating call records the refusal but writes no write event`() {
        val sink = RecordingAuditSink()
        val dispatcher = McpToolDispatcher(listOf(SpyTool("pipelines_create")), sink)
        val viewer = WorkspaceContext(McpFixtures.WORKSPACE_ID, "acme", WorkspaceRole.VIEWER)

        dispatcher.call(McpFixtures.request("pipelines_create"), McpFixtures.ctx(workspace = viewer))

        assertAll(
            { sink.writes() shouldHaveSize 0 },
            { sink.calls().single().details["outcome"] shouldBe "permission_refused" },
        )
    }

    /**
     * 107 — the `sql` argument never reaches the audit log verbatim. A `sql_probe` call records
     * `sql_sha256` + `sql_length` instead: the hash pairs a row with a reported statement, and
     * the text itself — customer-authored, transcript-hazard — stays out of the append-only log.
     */
    @Test
    fun `the sql argument is audited as a sha256 and a length, never verbatim`() {
        val sink = RecordingAuditSink()
        val sql = "SELECT * FROM customers WHERE email = 'ceo@acme.test'"
        val dispatcher = McpToolDispatcher(listOf(SpyTool("sql_probe")), sink)

        dispatcher.call(
            McpFixtures.request("sql_probe", mapOf("name" to "pg-prod", "sql" to sql)),
            McpFixtures.ctx(),
        )

        val row = sink.calls().single()
        val expected =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(sql.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        assertAll(
            { row.details["sql_sha256"] shouldBe expected },
            { row.details["sql_length"] shouldBe sql.length },
            { row.details.containsKey("sql") shouldBe false },
            { row.details.values.none { it.toString().contains("ceo@acme.test") } shouldBe true },
        )
    }

    /** 052/A — bookkeeping must never change the customer's call: a throwing sink is swallowed, the result stands. */
    @Test
    fun `an audit sink failure is swallowed and the tool result is unchanged`() {
        val sink =
            object : AuditEventSink {
                override fun log(
                    event: String,
                    userId: UUID?,
                    keyId: String?,
                    sourceIp: String?,
                    userAgent: String?,
                    details: Map<String, Any?>,
                ) {
                    error("audit_log unavailable")
                }
            }
        val dispatcher = McpToolDispatcher(listOf(SpyTool("pipelines_list") { mapOf("ok" to true) }), sink)

        val result = dispatcher.call(McpFixtures.request("pipelines_list"), McpFixtures.ctx())

        assertAll(
            { result.isError() shouldBe false },
            { McpFixtures.payloadOf(result)["ok"].asText() shouldBe "true" },
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

    /**
     * 139 — the table-level identifiers the entry-point checks learn from: the schema tools'
     * rows carry `table` (+ `namespace` segments) beside the `target` datasource, and
     * `templates_render` rows carry `template`. Identifier-shaped only; the render `context`
     * is never read.
     */
    @Test
    fun `schema and render rows carry the table and template identifiers the checks learn from`() {
        val sink = RecordingAuditSink()
        val dispatcher =
            McpToolDispatcher(
                listOf(
                    SpyTool("datasources_get_columns"),
                    SpyTool("datasources_get_table_stats"),
                    SpyTool("templates_render"),
                ),
                sink,
            )
        val ctx = McpFixtures.ctx()

        dispatcher.call(
            McpFixtures.request(
                "datasources_get_columns",
                mapOf("name" to "sample-lake", "table" to "trips", "namespace" to listOf("a1", "sales")),
            ),
            ctx,
        )
        dispatcher.call(
            McpFixtures.request("templates_render", mapOf("id" to "test/t.sql", "version" to 3, "context" to mapOf("secret" to "value"))),
            ctx,
        )

        val columnsRow = sink.calls().single { it.details["tool"] == "datasources_get_columns" }
        val renderRow = sink.calls().single { it.details["tool"] == "templates_render" }
        assertAll(
            { columnsRow.details["target"] shouldBe "sample-lake" },
            { columnsRow.details["table"] shouldBe "trips" },
            { columnsRow.details["namespace"] shouldBe listOf("a1", "sales") },
            { renderRow.details["template"] shouldBe "test/t.sql" },
            { renderRow.details["version"] shouldBe 3 },
            { sink.rows.toString() shouldNotContain "secret" },
        )
    }
}
