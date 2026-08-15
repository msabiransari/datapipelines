package co.datapipelines.web.datasources

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceProperties
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.DeleteResult
import co.datapipelines.datasources.TestResult
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.Dialect
import co.datapipelines.web.api.ApiException
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.util.UUID

/**
 * §9 over a mocked registry — including the redaction guard (gate C falsification target): no
 * response body may ever contain the password, which this test asserts against the **serialized**
 * JSON, not the Kotlin map.
 */
class DatasourcesControllerTest {
    private val registry = mockk<DatasourceRegistry>()
    private val controller = DatasourcesController(registry)
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    private val userId = UUID.randomUUID()

    private fun datasource() =
        Datasource(
            name = "pg-prod",
            displayName = "Production Postgres",
            dialect = Dialect.POSTGRES,
            jdbcUrl = "jdbc:postgresql://db:5432/app",
            username = "readonly",
            password = null,
            properties = DatasourceProperties(hikari = mapOf("maximumPoolSize" to 10), jdbc = mapOf("sslmode" to "verify-full")),
        )

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate(scopes: Set<Scope> = setOf(Scope.ADMIN)) {
        val principal = AuthenticatedPrincipal(userId, "a@b.c", "A", scopes, AuthMethod.OIDC)
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private val createBody =
        mapper.readTree(
            """{"name":"pg-prod","display_name":"Production Postgres","dialect":"POSTGRES",
               "jdbc_url":"jdbc:postgresql://db:5432/app","username":"readonly","password":"s3cret",
               "properties":{"hikari":{"maximumPoolSize":10},"jdbc":{"sslmode":"verify-full"}}}""",
        )

    @Test
    fun `create persists through the registry and never echoes the password`() {
        authenticate()
        every { registry.exists("pg-prod") } returns false
        every { registry.save(any(), userId) } returns datasource()

        val json = mapper.writeValueAsString(controller.create(createBody))

        json shouldNotContain "s3cret"
        json shouldNotContain "\"password\""
        mapper
            .readTree(json)
            .get("data")
            .get("password_set")
            .asBoolean() shouldBe true
    }

    @Test
    fun `a duplicate name on create is 409 duplicate_name`() {
        authenticate()
        every { registry.exists("pg-prod") } returns true
        shouldThrow<ApiException> { controller.create(createBody) }
            .code shouldBe PipelineErrorCodes.Datasource.DUPLICATE_NAME
    }

    @Test
    fun `create binds the include-schemas allowlist lowercase-normalized and projects it`() {
        // §3.3/§7A: the allowlist is optional, exact-name, stored lowercase — the bind
        // normalizes `Apex_Reporting` to `apex_reporting` so the case-insensitive exemption
        // and the stored row agree. The response carries the field when non-empty.
        authenticate()
        every { registry.exists("pg-prod") } returns false
        every {
            registry.save(match { it.introspectionIncludeSchemas == listOf("apex_reporting") }, userId)
        } returns datasource().copy(introspectionIncludeSchemas = listOf("apex_reporting"))
        val body =
            mapper.readTree(
                """{"name":"pg-prod","display_name":"Production Postgres","dialect":"POSTGRES",
                   "jdbc_url":"jdbc:postgresql://db:5432/app","username":"readonly","password":"s3cret",
                   "introspection_include_schemas":["Apex_Reporting"]}""",
            )

        val data = controller.create(body).data

        data["introspection_include_schemas"] shouldBe listOf("apex_reporting")
    }

    @Test
    fun `a non-string include-schemas entry is properties_invalid`() {
        authenticate()
        every { registry.exists("pg-prod") } returns false
        val body =
            mapper.readTree(
                """{"name":"pg-prod","dialect":"POSTGRES","jdbc_url":"jdbc:postgresql://db:5432/app",
                   "username":"readonly","password":"s3cret","introspection_include_schemas":[42]}""",
            )

        shouldThrow<ApiException> { controller.create(body) }
            .code shouldBe PipelineErrorCodes.Datasource.PROPERTIES_INVALID
    }

    @Test
    fun `list paginates with an exact total and projects the two property namespaces`() {
        every { registry.list(null) } returns (1..3).map { datasource().copy(name = "ds-$it") }

        val firstPage = controller.list(dialect = null, offset = 0, limit = 2).data
        firstPage.items.size shouldBe 2
        firstPage.pagination.total shouldBe 3L
        firstPage.pagination.hasMore shouldBe true

        val json = mapper.writeValueAsString(firstPage.items[0])
        val node = mapper.readTree(json)
        node
            .get("properties")
            .get("hikari")
            .get("maximumPoolSize")
            .asInt() shouldBe 10
        node
            .get("properties")
            .get("jdbc")
            .get("sslmode")
            .asText() shouldBe "verify-full"
        json shouldNotContain "\"password\":"
    }

    @Test
    fun `get returns the redacted entity, and an unknown name is datasource-not_found`() {
        every { registry.get("pg-prod") } returns datasource()
        val data = controller.get("pg-prod").data
        data["jdbc_url"] shouldBe "jdbc:postgresql://db:5432/app"
        data.containsKey("password") shouldBe false

        every { registry.get("nope") } returns null
        shouldThrow<ApiException> { controller.get("nope") }.code shouldBe "datasource.not_found"
    }

    @Test
    fun `update without a password keeps the stored credential, and an unknown name 404s`() {
        authenticate()
        every { registry.exists("pg-prod") } returns true
        val updateBody = mapper.readTree("""{"dialect":"POSTGRES","jdbc_url":"jdbc:postgresql://db2:5432/app","username":"ro"}""")
        every { registry.save(match { it.password == null && it.jdbcUrl == "jdbc:postgresql://db2:5432/app" }, userId) } returns
            datasource().copy(jdbcUrl = "jdbc:postgresql://db2:5432/app")

        controller.update("pg-prod", updateBody).data["jdbc_url"] shouldBe "jdbc:postgresql://db2:5432/app"

        every { registry.exists("nope") } returns false
        shouldThrow<ApiException> { controller.update("nope", updateBody) }.code shouldBe "datasource.not_found"
    }

    @Test
    fun `delete is 204, in_use is 409 with the referencing pipelines, unknown is 404`() {
        authenticate()
        every { registry.delete("pg-prod") } returns DeleteResult(deleted = true, name = "pg-prod")
        controller.delete("pg-prod")

        every { registry.delete("busy") } returns
            DeleteResult(false, "busy", PipelineErrorCodes.Datasource.IN_USE, listOf("monthly_revenue"))
        val inUse = shouldThrow<ApiException> { controller.delete("busy") }
        inUse.code shouldBe "datasource.in_use"
        inUse.details["referencing_pipelines"] shouldBe listOf("monthly_revenue")

        every { registry.delete("nope") } returns DeleteResult(deleted = false, name = "nope")
        shouldThrow<ApiException> { controller.delete("nope") }.code shouldBe "datasource.not_found"
    }

    @Test
    fun `test-connection failure is 200 with connected false, never an HTTP error`() {
        every { registry.testConnection("pg-prod") } returns
            TestResult(connected = false, testedAt = Instant.EPOCH, error = "Connection refused")
        val data = controller.test("pg-prod").data
        data["connected"] shouldBe false
        data["error"] shouldBe "Connection refused"

        every { registry.testConnection("pg-prod") } returns
            TestResult(connected = true, testedAt = Instant.EPOCH, serverVersion = "PostgreSQL 16.3")
        controller.test("pg-prod").data["server_version"] shouldBe "PostgreSQL 16.3"

        every { registry.testConnection("nope") } returns null
        shouldThrow<ApiException> { controller.test("nope") }.code shouldBe "datasource.not_found"
    }
}
