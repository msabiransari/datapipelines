package co.datapipelines.application.datasources

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.util.UUID

/**
 * [DatasourceCreateService] — the ONE validated registration path `POST /api/v1/datasources` and
 * the `datasources_create` MCP tool share (rest-api §9.1, mcp-server §6.2.22).
 *
 * The D8 binding is a port here, supplied by the surfaces' own `DatasourceWorkspaceRules`, so
 * this suite pins what the SERVICE owns: the payload binding, the binding call it makes, the
 * duplicate-name refusal, and the fact that a refusal writes nothing. The proof that both
 * surfaces really run this service over the real rules is `DatasourceCreateSharedPathTest` in
 * `web`, the only module that can see both.
 *
 * The registry save is a recording ANSWER, never `returns aFixedRow`: the question "did the row
 * actually reach the registry, and with what?" has to be able to fail.
 */
class DatasourceCreateServiceTest {
    private val mapper = JsonMapper.builder().build()
    private val registry = mockk<DatasourceRegistry>()
    private val saved = mutableListOf<Datasource>()

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()
    private val otherWorkspaceId = UUID.randomUUID()

    private val principal =
        AuthenticatedPrincipal(
            userId,
            "agent@example.test",
            "Agent",
            setOf(Scope.AUTHOR),
            AuthMethod.API_KEY,
            workspace = WorkspaceContext(workspaceId, "acme"),
        )

    /** Records the binding arguments the service passes, and resolves them like the D8 rules do. */
    private val bindingCalls = mutableListOf<Triple<Boolean?, String?, UUID?>>()

    private val binding =
        DatasourceCreateBinding { caller, global, workspaceName ->
            val resolved =
                when {
                    global == true -> null
                    workspaceName != null -> otherWorkspaceId
                    else -> caller.requireWorkspace().id
                }
            bindingCalls += Triple(global, workspaceName, resolved)
            resolved
        }

    private fun service(exists: Boolean = false): DatasourceCreateService {
        every { registry.exists(any()) } returns exists
        every { registry.save(any(), userId) } answers { firstArg<Datasource>().also { saved += it } }
        return DatasourceCreateService(registry, binding)
    }

    private fun body(extra: String = ""): JsonNode =
        mapper.readTree(
            """{"name":"pg_prod","dialect":"POSTGRES","jdbc_url":"jdbc:postgresql://db:5432/app",
               "username":"readonly","password":"s3cret"$extra}""",
        )

    @Test
    fun `a minimal payload binds, resolves the active workspace and reaches the registry`() {
        val created = service().create(body(), principal)

        saved shouldHaveSize 1
        assertAll(
            { created shouldBe saved.single() },
            { created.name shouldBe "pg_prod" },
            { created.dialect shouldBe Dialect.POSTGRES },
            { created.username shouldBe "readonly" },
            { created.password shouldBe "s3cret" },
            // display_name defaults to the name; description stays absent.
            { created.displayName shouldBe "pg_prod" },
            { created.description.shouldBeNull() },
            { created.isReadonly shouldBe false },
            { created.workspaceId shouldBe workspaceId },
            { created.introspectionIncludeSchemas.shouldBeEmpty() },
            { bindingCalls.single() shouldBe Triple(null, null, workspaceId) },
        )
    }

    @Test
    fun `every optional field is bound — display name, description, timeout, readonly, properties`() {
        val created =
            service().create(
                body(
                    ""","display_name":"Production","description":"OLTP","query_timeout_seconds":45,"readonly":true,
                       "properties":{"hikari":{"maximumPoolSize":8},"jdbc":{"ssl":"true"}}""",
                ),
                principal,
            )

        assertAll(
            { created.displayName shouldBe "Production" },
            { created.description shouldBe "OLTP" },
            { created.queryTimeoutSeconds shouldBe 45 },
            { created.isReadonly shouldBe true },
            { created.properties.hikari["maximumPoolSize"] shouldBe 8 },
            { created.properties.jdbc["ssl"] shouldBe "true" },
        )
    }

    @Test
    fun `the include-schemas allowlist is normalized through the shared rule`() {
        val created = service().create(body(""","introspection_include_schemas":["Apex_Reporting"," Sales "]"""), principal)

        created.introspectionIncludeSchemas shouldBe listOf("apex_reporting", "sales")
    }

    @Test
    fun `global true binds to no workspace, and the flag reaches the D8 port`() {
        val created = service().create(body(""","global":true"""), principal)

        assertAll(
            { created.workspaceId.shouldBeNull() },
            { bindingCalls.single() shouldBe Triple(true, null, null) },
        )
    }

    @Test
    fun `an explicit workspace name reaches the D8 port and wins the binding`() {
        val created = service().create(body(""","workspace":" team-etl """"), principal)

        assertAll(
            { created.workspaceId shouldBe otherWorkspaceId },
            // Trimmed on the way through, so " team-etl " and "team-etl" cannot resolve differently.
            { bindingCalls.single() shouldBe Triple(null, "team-etl", otherWorkspaceId) },
        )
    }

    @Test
    fun `a name already taken is duplicate_name, and nothing is written`() {
        val thrown = shouldThrow<DatapipelinesException> { service(exists = true).create(body(), principal) }

        assertAll(
            { thrown.code shouldBe PipelineErrorCodes.Datasource.DUPLICATE_NAME },
            { thrown.details["datasource_name"] shouldBe "pg_prod" },
            { saved.shouldBeEmpty() },
        )
    }

    @Test
    fun `a refusal from the D8 port propagates unchanged, and nothing is written`() {
        every { registry.exists(any()) } returns false
        val refusing =
            DatasourceCreateService(registry) { _, _, _ ->
                throw DatapipelinesException(PipelineErrorCodes.Datasource.WORKSPACE_FORBIDDEN, "nope")
            }

        val thrown = shouldThrow<DatapipelinesException> { refusing.create(body(), principal) }

        assertAll(
            { thrown.code shouldBe PipelineErrorCodes.Datasource.WORKSPACE_FORBIDDEN },
            { saved.shouldBeEmpty() },
        )
    }

    @Test
    fun `a missing password is password_missing — create requires one`() {
        val noPassword =
            mapper.readTree(
                """{"name":"pg_prod","dialect":"POSTGRES","jdbc_url":"jdbc:postgresql://db:5432/app","username":"readonly"}""",
            )

        val thrown = shouldThrow<DatapipelinesException> { service().create(noPassword, principal) }

        assertAll(
            { thrown.code shouldBe PipelineErrorCodes.Datasource.PASSWORD_MISSING },
            { saved.shouldBeEmpty() },
        )
    }
}
