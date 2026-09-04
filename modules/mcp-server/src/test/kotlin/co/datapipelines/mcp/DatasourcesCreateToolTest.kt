package co.datapipelines.mcp

import co.datapipelines.application.datasources.DatasourceCreateBinding
import co.datapipelines.application.datasources.DatasourceCreateService
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.Scope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.util.UUID

/**
 * `datasources_create` (mcp-server.md §6.2.22) — the write tool 068 added to the datasource
 * family.
 *
 * The tool owns almost nothing: registration is [DatasourceCreateService], the same call
 * `POST /api/v1/datasources` makes. So what is proven here is the tool's own half — the
 * arguments reach the shared path intact, the password never comes back, the D8 refusal
 * surfaces as the REST code, and the dispatcher audits the call as a WRITE.
 *
 * `DatasourceCreateSharedPathTest` in `web` proves the other half: that the REST surface and
 * this tool run the same service AND the same real `DatasourceWorkspaceRules` instance — which
 * cannot be asserted from here, because `web` sits above this module.
 */
class DatasourcesCreateToolTest {
    private val registry = FakeDatasourceRegistry(emptyList())

    /**
     * A real in-memory [AuditEventSink] — never a strict mock: a mock would make a MISSING
     * emission the passing state (MISTAKES.md, "a strict mock makes a missing call
     * unobservable"), and "the write was audited" is exactly the fact under test.
     */
    private class RecordingAuditSink : AuditEventSink {
        val rows = mutableListOf<Pair<String, Map<String, Any?>>>()

        override fun log(
            event: String,
            userId: UUID?,
            keyId: String?,
            sourceIp: String?,
            userAgent: String?,
            details: Map<String, Any?>,
        ) {
            rows += event to details
        }
    }

    /**
     * Stands in for `DatasourceWorkspaceRules.resolveCreateBinding`, which lives in `web`.
     * It reproduces only the branch this suite asserts on — `global` is admin-only, refused
     * with the catalogued REST code — so the tool's propagation of a binding refusal is
     * observable here; the REAL rule is exercised through the shared service in `web`.
     */
    private fun binding(admin: Boolean) =
        DatasourceCreateBinding { principal, global, workspaceName ->
            when {
                global == true && !admin -> {
                    throw DatapipelinesException(
                        PipelineErrorCodes.Datasource.WORKSPACE_FORBIDDEN,
                        "Datasource workspace binding refused: creating a global datasource requires admin.",
                    )
                }

                global == true -> {
                    null
                }

                workspaceName != null -> {
                    UUID.nameUUIDFromBytes(workspaceName.toByteArray())
                }

                else -> {
                    principal.requireWorkspace().id
                }
            }
        }

    private fun tool(admin: Boolean = false) = DatasourcesCreateTool(DatasourceCreateService(registry, binding(admin)))

    private fun args(extra: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        mapOf(
            "name" to "pg_prod",
            "dialect" to "POSTGRES",
            "jdbc_url" to "jdbc:postgresql://db:5432/app",
            "username" to "readonly",
            "password" to "s3cret-from-the-agent",
        ) + extra

    @Test
    fun `the tool is catalogued, mutating, and sits on the author floor`() {
        assertAll(
            { tool().name shouldBe "datasources_create" },
            { McpToolCatalog.NAMES shouldContain "datasources_create" },
            { McpToolCatalog.isMutating("datasources_create") shouldBe true },
            { ScopeMatrix.requiredScopeForTool("datasources_create") shouldBe Scope.AUTHOR },
        )
    }

    @Test
    fun `the description states the transcript trade-off an agent's user is accepting`() {
        // §B: a documented trade-off the owner accepted, not a bug to "fix" by refusing. It has
        // to be visible where the decision is made — in the description the agent reads.
        val description = tool().definition.description()

        assertAll(
            { description shouldContain "transits the agent's context" },
            { description shouldContain "short-lived" },
            { description shouldContain "datasources_test" },
        )
    }

    @Test
    fun `it registers the datasource through the shared path and reports password_set without the password`() {
        val result = tool().call(McpArguments(args()), McpFixtures.ctx(Scope.AUTHOR))

        // The registration actually happened, with the password bound for encryption at save.
        registry.saved shouldHaveSize 1
        assertAll(
            { registry.saved.single().name shouldBe "pg_prod" },
            { registry.saved.single().dialect shouldBe Dialect.POSTGRES },
            { registry.saved.single().password shouldBe "s3cret-from-the-agent" },
            { registry.saved.single().workspaceId shouldBe McpFixtures.WORKSPACE_ID },
        )

        // The redaction guard, asserted on the SERIALIZED payload — not on the Kotlin map, which
        // is how a mapper-introduced field would slip past (the §9.1 gate-C falsification target).
        val json =
            co.datapipelines.executor.ExecutorJson
                .write(result)
        assertAll(
            { json shouldNotContain "s3cret-from-the-agent" },
            { json shouldNotContain "\"password\"" },
            { json shouldContain "\"password_set\":true" },
        )
    }

    @Test
    fun `optional arguments reach the shared binder — readonly, workspace and the schema allowlist`() {
        tool().call(
            McpArguments(
                args(
                    mapOf(
                        "readonly" to true,
                        "workspace" to "team-etl",
                        "display_name" to "Production Postgres",
                        "introspection_include_schemas" to listOf("Apex_Reporting"),
                        "query_timeout_seconds" to 45,
                    ),
                ),
            ),
            McpFixtures.ctx(Scope.AUTHOR),
        )

        val stored = registry.saved.single()
        assertAll(
            { stored.isReadonly shouldBe true },
            { stored.displayName shouldBe "Production Postgres" },
            { stored.queryTimeoutSeconds shouldBe 45 },
            // §3.3's normalization is the shared binder's, not a second copy in this tool.
            { stored.introspectionIncludeSchemas shouldBe listOf("apex_reporting") },
            { stored.workspaceId shouldBe UUID.nameUUIDFromBytes("team-etl".toByteArray()) },
        )
    }

    @Test
    fun `a duplicate name is the REST duplicate_name code, and nothing is written`() {
        tool().call(McpArguments(args()), McpFixtures.ctx(Scope.AUTHOR))

        val sink = RecordingAuditSink()
        val result =
            McpToolDispatcher(listOf(tool()), sink).call(
                McpFixtures.request("datasources_create", args()),
                McpFixtures.ctx(Scope.AUTHOR),
            )

        assertAll(
            { result.isError() shouldBe true },
            { McpFixtures.payloadOf(result)["error"]["code"].asText() shouldBe PipelineErrorCodes.Datasource.DUPLICATE_NAME },
            { registry.saved shouldHaveSize 1 },
        )
    }

    @Test
    fun `global without admin is refused with the code REST uses, and nothing is written`() {
        val sink = RecordingAuditSink()

        val result =
            McpToolDispatcher(listOf(tool(admin = false)), sink).call(
                McpFixtures.request("datasources_create", args(mapOf("global" to true))),
                McpFixtures.ctx(Scope.AUTHOR),
            )

        assertAll(
            { result.isError() shouldBe true },
            {
                McpFixtures.payloadOf(result)["error"]["code"].asText() shouldBe
                    PipelineErrorCodes.Datasource.WORKSPACE_FORBIDDEN
            },
            { registry.saved shouldHaveSize 0 },
        )
    }

    @Test
    fun `an admin may register a global datasource, bound to no workspace`() {
        tool(admin = true).call(McpArguments(args(mapOf("global" to true))), McpFixtures.ctx(Scope.AUTHOR))

        registry.saved.single().workspaceId shouldBe null
    }

    @Test
    fun `a successful call is audited as a WRITE, and the audit row carries no credential`() {
        val sink = RecordingAuditSink()

        McpToolDispatcher(listOf(tool()), sink).call(
            McpFixtures.request("datasources_create", args()),
            McpFixtures.ctx(Scope.AUTHOR),
        )

        val events = sink.rows.map { it.first }
        assertAll(
            { events shouldBe listOf("mcp.tool.called", "mcp.tool.write") },
            { sink.rows.first().second["tool"] shouldBe "datasources_create" },
            { sink.rows.first().second["outcome"] shouldBe "success" },
            // The dispatcher records identifier-shaped arguments only (052) — `name`, never the
            // password or the JDBC URL.
            { sink.rows.first().second["target"] shouldBe "pg_prod" },
            {
                co.datapipelines.executor.ExecutorJson
                    .write(sink.rows)
                    .shouldNotContain("s3cret-from-the-agent")
            },
        )
    }

    @Test
    fun `a read scope cannot call it — the dispatcher refuses before the tool runs`() {
        val sink = RecordingAuditSink()

        val result =
            McpToolDispatcher(listOf(tool()), sink).call(
                McpFixtures.request("datasources_create", args()),
                McpFixtures.ctx(Scope.READ),
            )

        assertAll(
            { result.isError() shouldBe true },
            { registry.saved shouldHaveSize 0 },
            { sink.rows.map { it.first } shouldBe listOf("mcp.tool.called") },
            { sink.rows.single().second["outcome"] shouldBe "scope_refused" },
        )
    }
}
