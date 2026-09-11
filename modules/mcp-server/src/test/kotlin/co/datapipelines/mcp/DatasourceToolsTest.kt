package co.datapipelines.mcp

import co.datapipelines.auth.Scope
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.TestResult
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.time.Instant
import java.util.UUID

class DatasourceToolsTest {
    private val registry = mockk<DatasourceRegistry>()
    private val readCtx = McpFixtures.ctx(Scope.READ)

    /**
     * `datasources_test` is TEST_DATASOURCE — `ws_admin` on the role axis (design §1): a probe
     * opens a real connection with the stored credential, which is not an authoring act.
     */
    private val adminCtx = McpFixtures.ctx(Scope.AUTHOR, workspace = McpFixtures.WORKSPACE_ADMIN)

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

    /**
     * 114 §D — the shape 112 made false, corrected.
     *
     * The projection used to emit `workspace: <name> | null = global`, and "global" meant
     * "visible everywhere". D-R7 deleted the concept: visibility is the GRANT, so a null
     * `workspace` no longer says anything about who can see the row — and an agent that
     * learned the old contract would read it as the retired meaning. The field now carries
     * only what it can honestly carry (which workspace REGISTERED it) and is OMITTED, never
     * null, when the answer is "none — a super admin registered it at the instance level".
     */
    @Test
    fun `114 - a row states the registering workspace and granted, and never a null workspace`() {
        every { registry.listVisible(null, McpFixtures.WORKSPACE_ID) } returns
            listOf(McpFixtures.datasource().copy(ownerWorkspaceId = McpFixtures.WORKSPACE_ID, workspaceName = "acme"))

        val row = (DatasourcesListTool(registry).call(McpArguments(emptyMap()), readCtx) as List<*>).first() as Map<*, *>

        assertAll(
            { row["workspace"] shouldBe "acme" },
            // You are seeing this row BECAUSE your workspace holds a grant on it — implicit
            // before D-R7, load-bearing after it.
            { row["granted"] shouldBe true },
        )
    }

    @Test
    fun `114 - an instance datasource omits workspace rather than emitting the retired null`() {
        every { registry.listVisible(null, McpFixtures.WORKSPACE_ID) } returns
            listOf(McpFixtures.datasource().copy(ownerWorkspaceId = null, workspaceName = null))

        val row = (DatasourcesListTool(registry).call(McpArguments(emptyMap()), readCtx) as List<*>).first() as Map<*, *>

        assertAll(
            // ABSENT, not null: a null is the one value the old contract gave a meaning to.
            { row.containsKey("workspace") shouldBe false },
            { row["granted"] shouldBe true },
        )
    }

    /**
     * There is deliberately no `granted_workspaces`. Listing every workspace a datasource is
     * granted to names workspaces the caller may not know exist — the cross-workspace
     * disclosure D-R5 withholds from everyone below super admin, and the reason
     * `DatasourceGrantsController` is `MANAGE_DATASOURCE_GRANTS` on its READ as well as its
     * writes. No key may hold `admin` scope (O-2), so the field would have no correct
     * audience on this surface at all.
     */
    @Test
    fun `114 - no tool leaks the grant list to a key`() {
        every { registry.getVisible("pg-prod", McpFixtures.WORKSPACE_ID) } returns McpFixtures.datasource()

        val row = DatasourcesGetTool(registry).call(McpArguments(mapOf("name" to "pg-prod")), adminCtx) as Map<*, *>

        row.containsKey("granted_workspaces") shouldBe false
    }

    /** The DESCRIPTIONS are the contract an agent reads before it calls anything. */
    @Test
    fun `114 - the descriptions say grant, and never global`() {
        val list = DatasourcesListTool(registry).definition.description()
        val get = DatasourcesGetTool(registry).definition.description()

        assertAll(
            { list shouldContain "GRANTED" },
            { list shouldContain "no such thing as a global datasource" },
            { get shouldContain "GRANTED" },
            { list shouldNotContain "plus every global one" },
            { get shouldNotContain "bound to another workspace" },
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
        every { registry.getVisible("pg-prod", McpFixtures.WORKSPACE_ID) } returns McpFixtures.datasource()
        every { registry.testConnection(match<co.datapipelines.datasources.Datasource> { it.name == "pg-prod" }) } returns
            TestResult(connected = true, testedAt = Instant.parse("2026-08-09T12:00:00Z"), serverVersion = "PostgreSQL 16.2")

        @Suppress("UNCHECKED_CAST")
        val payload = DatasourcesTestTool(registry).call(McpArguments(mapOf("name" to "pg-prod")), adminCtx) as Map<String, Any?>

        assertAll(
            { payload.keys shouldBe setOf("connected", "server_version", "error") },
            { payload["connected"] shouldBe true },
            { payload["server_version"] shouldBe "PostgreSQL 16.2" },
        )
    }

    @Test
    fun `a failed test reports the registry's scrubbed message and nothing else`() {
        every { registry.getVisible("pg-prod", McpFixtures.WORKSPACE_ID) } returns McpFixtures.datasource()
        every { registry.testConnection(match<co.datapipelines.datasources.Datasource> { it.name == "pg-prod" }) } returns
            TestResult(
                connected = false,
                testedAt = Instant.parse("2026-08-09T12:00:00Z"),
                error = "Connection refused",
                errorClass = "SQLTransientConnectionException",
            )

        @Suppress("UNCHECKED_CAST")
        val payload = DatasourcesTestTool(registry).call(McpArguments(mapOf("name" to "pg-prod")), adminCtx) as Map<String, Any?>

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
        every { registry.getVisible("nope", McpFixtures.WORKSPACE_ID) } returns null

        shouldThrow<DatapipelinesException> {
            DatasourcesTestTool(registry).call(McpArguments(mapOf("name" to "nope")), adminCtx)
        }.code shouldBe PipelineErrorCodes.Datasource.NOT_FOUND
    }

    @Test
    fun `testing a datasource bound to another workspace is not-found - and the probe never runs`() {
        // F3 (022 review): datasources_test skipped the §5.3 visibility gate its siblings
        // got — a live connectivity probe plus server_version of another workspace's
        // datasource, and an existence oracle. Through a REAL visibility lookup (never a
        // stubbed testConnection) the bound row must resolve as not-found BEFORE any probe.
        val boundElsewhere =
            McpFixtures.datasource().copy(ownerWorkspaceId = UUID.randomUUID(), workspaceName = "other")
        val registry = FakeDatasourceRegistry(listOf(boundElsewhere))

        shouldThrow<DatapipelinesException> {
            DatasourcesTestTool(registry).call(McpArguments(mapOf("name" to "pg-prod")), adminCtx)
        }.code shouldBe PipelineErrorCodes.Datasource.NOT_FOUND
        registry.testedNames shouldBe emptyList<String>()
    }

    @Test
    fun `testing a global or own-workspace datasource passes the visibility gate`() {
        val registry =
            FakeDatasourceRegistry(
                listOf(
                    McpFixtures.datasource(name = "global-pg"),
                    McpFixtures
                        .datasource(name = "own-pg")
                        .copy(ownerWorkspaceId = McpFixtures.WORKSPACE_ID, workspaceName = "acme"),
                ),
            )

        @Suppress("UNCHECKED_CAST")
        val global = DatasourcesTestTool(registry).call(McpArguments(mapOf("name" to "global-pg")), adminCtx) as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val own = DatasourcesTestTool(registry).call(McpArguments(mapOf("name" to "own-pg")), adminCtx) as Map<String, Any?>

        assertAll(
            { global["connected"] shouldBe true },
            { own["connected"] shouldBe true },
        )
    }
}
