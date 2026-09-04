package co.datapipelines.web.datasources

import co.datapipelines.application.datasources.DatasourceCreateService
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceService
import co.datapipelines.auth.WorkspacesProperties
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.mcp.DatasourcesCreateTool
import co.datapipelines.mcp.McpArguments
import co.datapipelines.mcp.McpToolContext
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import com.fasterxml.jackson.databind.json.JsonMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

/**
 * **One validated path** — `POST /api/v1/datasources` and the `datasources_create` MCP tool
 * (mcp-server.md §6.2.22) reach the SAME [DatasourceCreateService] over the SAME real
 * [DatasourceWorkspaceRules] instance (049's principle, applied to datasources by 068).
 *
 * This suite exists because neither module can prove it alone: `mcp-server` sits below `web` and
 * cannot see the D8 rules, and `DatasourcesControllerTest` sees only the REST half. `web`
 * depends on both, so this is the only place where the claim "the tool enforces exactly the REST
 * rules" is an assertion rather than a comment.
 *
 * ## How it can fail
 *
 * Delete `registrations.create(...)` from [DatasourcesController.create] and there is no other
 * implementation of registration to fall back on — the REST half of every case below goes red.
 * Give the tool its own binding rule and the `global`/gate cases diverge here immediately.
 */
class DatasourceCreateSharedPathTest {
    private val registry = mockk<DatasourceRegistry>()
    private val workspaceService = mockk<WorkspaceService>(relaxed = true)
    private val mapper = JsonMapper.builder().build()

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()
    private val workspace = WorkspaceContext(workspaceId, "acme")

    /** Every row the shared service handed the registry — a recording answer, never a bare mock. */
    private val saved = mutableListOf<Datasource>()

    private fun surfaces(memberGate: Boolean = true): Pair<DatasourcesController, DatasourcesCreateTool> {
        val rules = DatasourceWorkspaceRules(workspaceService, WorkspacesProperties(memberDatasourcesEnabled = memberGate))
        val service = DatasourceCreateService(registry, rules::resolveCreateBinding)
        return DatasourcesController(registry, rules, service) to DatasourcesCreateTool(service)
    }

    private fun principal(admin: Boolean) =
        AuthenticatedPrincipal(
            userId,
            "a@b.c",
            "A",
            if (admin) setOf(Scope.ADMIN) else setOf(Scope.AUTHOR),
            AuthMethod.API_KEY,
            workspace = workspace,
        )

    private fun authenticate(admin: Boolean) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal(admin), null, emptyList())
    }

    private fun ctx(admin: Boolean) = McpToolContext(principal(admin), UUID.randomUUID())

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun stubRegistry() {
        every { registry.exists(any()) } returns false
        every { registry.save(any(), userId) } answers {
            firstArg<Datasource>().also { saved += it }
        }
    }

    private fun restBody(extra: String = "") =
        mapper.readTree(
            """{"name":"pg_rest","display_name":"Production Postgres","dialect":"POSTGRES","jdbc_url":"jdbc:postgresql://db:5432/app",
               "username":"readonly","password":"s3cret","readonly":true,
               "introspection_include_schemas":["Apex_Reporting"]$extra}""",
        )

    private fun toolArgs(extra: Map<String, Any?> = emptyMap()) =
        McpArguments(
            mapOf(
                "name" to "pg_mcp",
                "display_name" to "Production Postgres",
                "dialect" to "POSTGRES",
                "jdbc_url" to "jdbc:postgresql://db:5432/app",
                "username" to "readonly",
                "password" to "s3cret",
                "readonly" to true,
                "introspection_include_schemas" to listOf("Apex_Reporting"),
            ) + extra,
        )

    @Test
    fun `both surfaces bind the same payload to the same stored row`() {
        stubRegistry()
        authenticate(admin = false)
        val (controller, tool) = surfaces()

        controller.create(restBody())
        tool.call(toolArgs(), ctx(admin = false))

        saved shouldHaveSize 2
        val (rest, mcp) = saved
        assertAll(
            // Identical in everything but the name: same dialect binding, same D8 workspace
            // binding, same readonly flag, same §3.3 allowlist normalization.
            { rest.copy(name = "x") shouldBe mcp.copy(name = "x") },
            { rest.workspaceId shouldBe workspaceId },
            { rest.isReadonly shouldBe true },
            { rest.introspectionIncludeSchemas shouldBe listOf("apex_reporting") },
        )
    }

    @Test
    fun `global without admin is refused on BOTH surfaces with the same catalogued code`() {
        stubRegistry()
        val (controller, tool) = surfaces()

        authenticate(admin = false)
        val fromRest = shouldThrow<DatapipelinesException> { controller.create(restBody(""","global":true""")) }
        val fromMcp = shouldThrow<DatapipelinesException> { tool.call(toolArgs(mapOf("global" to true)), ctx(admin = false)) }

        assertAll(
            { fromRest.code shouldBe PipelineErrorCodes.Datasource.WORKSPACE_FORBIDDEN },
            { fromMcp.code shouldBe PipelineErrorCodes.Datasource.WORKSPACE_FORBIDDEN },
            { saved shouldHaveSize 0 },
        )
    }

    @Test
    fun `an admin may register a global datasource on either surface`() {
        stubRegistry()
        authenticate(admin = true)
        val (controller, tool) = surfaces()

        controller.create(restBody(""","global":true"""))
        tool.call(toolArgs(mapOf("global" to true)), ctx(admin = true))

        saved.map { it.workspaceId } shouldBe listOf(null, null)
    }

    @Test
    fun `the member-datasources gate closes BOTH surfaces to a non-admin`() {
        stubRegistry()
        authenticate(admin = false)
        val (controller, tool) = surfaces(memberGate = false)

        val fromRest = shouldThrow<DatapipelinesException> { controller.create(restBody()) }
        val fromMcp = shouldThrow<DatapipelinesException> { tool.call(toolArgs(), ctx(admin = false)) }

        assertAll(
            { fromRest.code shouldBe PipelineErrorCodes.Datasource.WORKSPACE_FORBIDDEN },
            { fromMcp.code shouldBe PipelineErrorCodes.Datasource.WORKSPACE_FORBIDDEN },
            { saved shouldHaveSize 0 },
        )
    }

    @Test
    fun `a duplicate name is refused identically on both surfaces`() {
        every { registry.exists(any()) } returns true
        authenticate(admin = false)
        val (controller, tool) = surfaces()

        assertAll(
            {
                shouldThrow<DatapipelinesException> { controller.create(restBody()) }.code shouldBe
                    PipelineErrorCodes.Datasource.DUPLICATE_NAME
            },
            {
                shouldThrow<DatapipelinesException> { tool.call(toolArgs(), ctx(admin = false)) }.code shouldBe
                    PipelineErrorCodes.Datasource.DUPLICATE_NAME
            },
        )
    }

    @Test
    fun `the REST response is the row the shared service saved, with the password gone`() {
        stubRegistry()
        authenticate(admin = false)
        val (controller, _) = surfaces()

        val body = mapper.writeValueAsString(controller.create(restBody()))

        assertAll(
            { saved shouldHaveSize 1 },
            {
                mapper
                    .readTree(body)
                    .get("data")
                    .get("name")
                    .asText() shouldBe saved.single().name
            },
            {
                mapper
                    .readTree(body)
                    .get("data")
                    .get("password_set")
                    .asBoolean() shouldBe true
            },
            { body.contains("s3cret") shouldBe false },
        )
    }
}
