package co.datapipelines.web.datasources

import co.datapipelines.application.datasources.LakeImportResult
import co.datapipelines.application.datasources.LakeTable
import co.datapipelines.application.datasources.LakeTableFormat
import co.datapipelines.application.datasources.LakeTableRegistryService
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.util.UUID

/**
 * [LakeTablesController] (rest-api.md §9.8, 089 §A) over a mocked registry and service — the
 * [DatasourcesControllerTest] pattern.
 *
 * The controller is a thin shell: what is pinned here is the HTTP half — the §5.3 visibility
 * gate (an invisible datasource is `datasource.not_found`, identical to unknown), the wire
 * shape of each response, the dotted-namespace path binding on DELETE, and the fact that the
 * service's catalogued refusals propagate untouched (the `ApiExceptionHandler` maps the base
 * type by CODE, so the HTTP status is the catalog row's). The validation itself is the
 * service's suite's business, in `application`.
 */
class LakeTablesControllerTest {
    private val registry = mockk<DatasourceRegistry>()
    private val service = mockk<LakeTableRegistryService>()
    private val controller = LakeTablesController(registry, service)
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    private val lake =
        Datasource(name = "sample-lake", displayName = "Sample lake", dialect = Dialect.LAKE, jdbcUrl = "jdbc:duckdb:")

    private val table =
        LakeTable(
            id = UUID.randomUUID(),
            datasourceId = "sample-lake",
            namespace = listOf("nyc", "mobility"),
            name = "hvfhv_zone_day",
            format = LakeTableFormat.PARQUET,
            location = "s3://datapipelines-co/sample-data/lake/v1/hvfhv_zone_day/part-0.parquet",
            partitionColumn = null,
            registeredBy = userId,
            registeredAt = Instant.parse("2026-09-07T12:00:00Z"),
        )

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate() {
        val principal =
            AuthenticatedPrincipal(
                userId,
                "a@b.c",
                "A",
                setOf(Scope.AUTHOR),
                AuthMethod.OIDC,
                workspace = WorkspaceContext(workspaceId, "acme"),
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    @Test
    fun `register delegates to the shared service and renders the stored row`() {
        authenticate()
        every { registry.getVisible("sample-lake", workspaceId) } returns lake
        val body = slot<com.fasterxml.jackson.databind.JsonNode>()
        every { service.register(lake, capture(body), any()) } returns table

        val json =
            mapper.writeValueAsString(
                controller.register(
                    "sample-lake",
                    mapper.readTree(
                        """{"namespace": ["nyc", "mobility"], "name": "hvfhv_zone_day", "format": "parquet",
                           "location": "s3://datapipelines-co/sample-data/lake/v1/hvfhv_zone_day/part-0.parquet"}""",
                    ),
                ),
            )

        assertAll(
            // The body reached the service intact — the ONE validated path gets exactly what was sent.
            { body.captured.get("name").asText() shouldBe "hvfhv_zone_day" },
            { body.captured.get("namespace").size() shouldBe 2 },
            { json shouldContain "\"qualified_name\":\"nyc.mobility.hvfhv_zone_day\"" },
            { json shouldContain "\"format\":\"parquet\"" },
            // The actor's UUID stays off the wire (the toWireMap contract).
            { json.contains(userId.toString()) shouldBe false },
        )
    }

    @Test
    fun `unregister binds the dotted namespace path into segments`() {
        authenticate()
        every { registry.getVisible("sample-lake", workspaceId) } returns lake
        val namespace = slot<List<String>>()
        every { service.unregister(lake, capture(namespace), any(), any()) } returns Unit

        controller.unregister("sample-lake", "nyc.mobility", "hvfhv_zone_day")

        namespace.captured shouldContainExactly listOf("nyc", "mobility")
    }

    @Test
    fun `import renders the idempotent result shape`() {
        authenticate()
        every { registry.getVisible("sample-lake", workspaceId) } returns lake
        every { service.importTables(lake, any(), any()) } returns
            LakeImportResult(registered = listOf(table), alreadyRegistered = listOf("nyc.mobility.hvfhv_trips"))

        val json = mapper.writeValueAsString(controller.import("sample-lake", mapper.readTree("""{"tables": []}""")))

        assertAll(
            { json shouldContain "\"registered_count\":1" },
            { json shouldContain "\"already_registered\":[\"nyc.mobility.hvfhv_trips\"]" },
        )
    }

    @Test
    fun `the registry listing is read-scope and renders the catalog rows`() {
        authenticate()
        every { registry.getVisible("sample-lake", workspaceId) } returns lake
        every { service.list(lake) } returns listOf(table)

        val json = mapper.writeValueAsString(controller.listRegistered("sample-lake"))

        assertAll(
            { json shouldContain "\"count\":1" },
            { json shouldContain "\"location\":\"s3://datapipelines-co" },
        )
    }

    @Test
    fun `an invisible datasource is not-found on every route`() {
        authenticate()
        every { registry.getVisible(any(), any()) } returns null

        assertAll(
            {
                shouldThrow<DatapipelinesException> {
                    controller.register(
                        "ghost",
                        mapper.readTree("""{"namespace":"nyc","name":"t","format":"parquet","location":"s3://b/x"}"""),
                    )
                }.code shouldBe PipelineErrorCodes.Datasource.NOT_FOUND
            },
            {
                shouldThrow<DatapipelinesException> { controller.unregister("ghost", "nyc", "t") }
                    .code shouldBe PipelineErrorCodes.Datasource.NOT_FOUND
            },
            {
                shouldThrow<DatapipelinesException> { controller.import("ghost", mapper.readTree("""{"tables": []}""")) }
                    .code shouldBe PipelineErrorCodes.Datasource.NOT_FOUND
            },
            {
                shouldThrow<DatapipelinesException> { controller.listRegistered("ghost") }
                    .code shouldBe PipelineErrorCodes.Datasource.NOT_FOUND
            },
        )
    }

    @Test
    fun `the service's catalogued refusals propagate untouched`() {
        authenticate()
        every { registry.getVisible("sample-lake", workspaceId) } returns lake
        every { service.register(lake, any(), any()) } throws
            DatapipelinesException(
                PipelineErrorCodes.Datasource.LAKE_TABLE_DUPLICATE,
                "Lake table 'nyc.mobility.hvfhv_zone_day' is already registered on datasource 'sample-lake'.",
                mapOf("datasource_name" to "sample-lake"),
            )

        shouldThrow<DatapipelinesException> {
            controller.register(
                "sample-lake",
                mapper.readTree("""{"namespace":"nyc.mobility","name":"hvfhv_zone_day","format":"parquet","location":"s3://b/x"}"""),
            )
        }.code shouldBe PipelineErrorCodes.Datasource.LAKE_TABLE_DUPLICATE
    }
}
