package co.datapipelines.mcp

import co.datapipelines.auth.Scope
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.TestResult
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.time.Instant

class DatasourceToolsTest {
    private val registry = mockk<DatasourceRegistry>()
    private val readCtx = McpFixtures.ctx(Scope.READ)
    private val authorCtx = McpFixtures.ctx(Scope.AUTHOR)

    @Test
    fun `list never returns a password`() {
        every { registry.listVisible(null, McpFixtures.WORKSPACE_ID) } returns listOf(McpFixtures.datasource())

        val payload = DatasourcesListTool(registry).call(McpArguments(emptyMap()), readCtx)
        val first = (payload as List<*>).first() as Map<*, *>

        assertAll(
            { first["name"] shouldBe "pg-prod" },
            { first["dialect"] shouldBe "POSTGRES" },
            { first.containsKey("password") shouldBe false },
            {
                co.datapipelines.executor.ExecutorJson
                    .write(payload) shouldNotContain "super-secret-password"
            },
        )
    }

    @Test
    fun `list pushes the dialect filter down to the registry`() {
        every { registry.listVisible(Dialect.MYSQL, McpFixtures.WORKSPACE_ID) } returns emptyList()

        (DatasourcesListTool(registry).call(McpArguments(mapOf("dialect" to "MYSQL")), readCtx) as List<*>).size shouldBe 0
    }

    @Test
    fun `an unrecognized dialect filter matches nothing rather than failing`() {
        // §6.2.10 pins `dialect` as a bare string (no enum) — unlike §6.2.6/§6.2.8, deliberately.
        val hits = DatasourcesListTool(registry).call(McpArguments(mapOf("dialect" to "SNOWFLAKE")), readCtx) as List<*>

        assertAll(
            { hits.size shouldBe 0 },
            { verify(exactly = 0) { registry.listVisible(any(), any()) } },
            { DatasourcesListTool(registry).definition.inputSchema().toString() shouldNotContain "enum" },
        )
    }

    @Test
    fun `get returns connection metadata without credentials`() {
        every { registry.getVisible("pg-prod", McpFixtures.WORKSPACE_ID) } returns McpFixtures.datasource()

        @Suppress("UNCHECKED_CAST")
        val payload = DatasourcesGetTool(registry).call(McpArguments(mapOf("name" to "pg-prod")), readCtx) as Map<String, Any?>

        assertAll(
            { payload["jdbc_url"] shouldBe "jdbc:postgresql://db:5432/app" },
            { payload["username"] shouldBe "reporting" },
            { payload.containsKey("password") shouldBe false },
        )
    }

    @Test
    fun `get projects the introspection allowlist when one is active - non-empty only, like REST`() {
        // R4 F4: MCP agents (the primary introspection consumers) could not see that an
        // allowlist was active when debugging why a schema is or isn't visible — REST
        // returned the field, MCP didn't. Same omitted-when-empty envelope semantics as
        // REST §3.2, on both surfaces that share toMcpMetadata.
        every { registry.getVisible("pg-prod", McpFixtures.WORKSPACE_ID) } returns
            McpFixtures.datasource().copy(introspectionIncludeSchemas = listOf("apex_reporting"))
        every { registry.listVisible(null, McpFixtures.WORKSPACE_ID) } returns
            listOf(McpFixtures.datasource().copy(introspectionIncludeSchemas = listOf("apex_reporting")))

        @Suppress("UNCHECKED_CAST")
        val single = DatasourcesGetTool(registry).call(McpArguments(mapOf("name" to "pg-prod")), readCtx) as Map<String, Any?>
        val listed = DatasourcesListTool(registry).call(McpArguments(emptyMap()), readCtx) as List<*>

        assertAll(
            { single["introspection_include_schemas"] shouldBe listOf("apex_reporting") },
            { (listed.first() as Map<*, *>)["introspection_include_schemas"] shouldBe listOf("apex_reporting") },
        )
    }

    @Test
    fun `get omits the introspection allowlist when it is empty - the envelope convention`() {
        every { registry.getVisible("pg-prod", McpFixtures.WORKSPACE_ID) } returns McpFixtures.datasource()

        @Suppress("UNCHECKED_CAST")
        val payload = DatasourcesGetTool(registry).call(McpArguments(mapOf("name" to "pg-prod")), readCtx) as Map<String, Any?>

        payload.containsKey("introspection_include_schemas") shouldBe false
    }

    @Test
    fun `an unknown datasource is a catalogued not-found`() {
        every { registry.getVisible("nope", McpFixtures.WORKSPACE_ID) } returns null

        shouldThrow<DatapipelinesException> {
            DatasourcesGetTool(registry).call(McpArguments(mapOf("name" to "nope")), readCtx)
        }.code shouldBe PipelineErrorCodes.Datasource.NOT_FOUND
    }

    @Test
    fun `test returns exactly connected, server_version and error`() {
        every { registry.testConnection("pg-prod") } returns
            TestResult(connected = true, testedAt = Instant.parse("2026-08-09T12:00:00Z"), serverVersion = "PostgreSQL 16.2")

        @Suppress("UNCHECKED_CAST")
        val payload = DatasourcesTestTool(registry).call(McpArguments(mapOf("name" to "pg-prod")), authorCtx) as Map<String, Any?>

        assertAll(
            { payload.keys shouldBe setOf("connected", "server_version", "error") },
            { payload["connected"] shouldBe true },
            { payload["server_version"] shouldBe "PostgreSQL 16.2" },
        )
    }

    @Test
    fun `a failed test reports the registry's scrubbed message and nothing else`() {
        every { registry.testConnection("pg-prod") } returns
            TestResult(
                connected = false,
                testedAt = Instant.parse("2026-08-09T12:00:00Z"),
                error = "Connection refused",
                errorClass = "SQLTransientConnectionException",
            )

        @Suppress("UNCHECKED_CAST")
        val payload = DatasourcesTestTool(registry).call(McpArguments(mapOf("name" to "pg-prod")), authorCtx) as Map<String, Any?>

        assertAll(
            { payload["connected"] shouldBe false },
            { payload["error"] shouldBe "Connection refused" },
            {
                co.datapipelines.executor.ExecutorJson
                    .write(payload) shouldNotContain "jdbc:"
            },
        )
    }

    @Test
    fun `testing an unknown datasource is a catalogued not-found`() {
        every { registry.testConnection("nope") } returns null

        shouldThrow<DatapipelinesException> {
            DatasourcesTestTool(registry).call(McpArguments(mapOf("name" to "nope")), authorCtx)
        }.code shouldBe PipelineErrorCodes.Datasource.NOT_FOUND
    }
}
