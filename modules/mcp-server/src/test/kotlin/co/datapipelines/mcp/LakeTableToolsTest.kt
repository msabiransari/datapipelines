package co.datapipelines.mcp

import co.datapipelines.application.datasources.LakeTable
import co.datapipelines.application.datasources.LakeTableFormat
import co.datapipelines.application.datasources.LakeTableRegistryService
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.Scope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.datasources.Datasource
import co.datapipelines.executor.ExecutorJson
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.time.Instant
import java.util.UUID

/**
 * The three dp-lake catalog tools (mcp-server.md §6.2.29–31, 089 §A) — the [DatasourcesCreateToolTest]
 * shape.
 *
 * The tools own almost nothing: registration is [LakeTableRegistryService], the same call the
 * REST `/tables` endpoints make. So what is proven here is the tools' own half — the catalog
 * entries (names, mutating flags, scope floor), the §5.3 visibility gate BEFORE the service
 * runs, the argument-to-body assembly, and the dispatcher auditing each call as a WRITE. The
 * input schemas are pinned against mcp-server.md by `McpToolSurfaceSpecDriftTest`, not here.
 */
class LakeTableToolsTest {
    private val lake =
        Datasource(
            name = "sample-lake",
            displayName = "Sample lake",
            dialect = Dialect.LAKE,
            jdbcUrl = "jdbc:duckdb:",
            workspaceId = McpFixtures.WORKSPACE_ID,
        )

    private val registry = FakeDatasourceRegistry(listOf(lake))
    private val service = mockk<LakeTableRegistryService>()

    private val table =
        LakeTable(
            id = UUID.randomUUID(),
            datasourceId = "sample-lake",
            namespace = listOf("nyc", "mobility"),
            name = "hvfhv_zone_day",
            format = LakeTableFormat.PARQUET,
            location = "s3://datapipelines-co/sample-data/lake/v1/hvfhv_zone_day/part-0.parquet",
            partitionColumn = null,
            registeredBy = McpFixtures.USER,
            registeredAt = Instant.parse("2026-09-07T12:00:00Z"),
        )

    private fun tools() = LakeTableTools.all(registry, service)

    @Test
    fun `all three are catalogued, mutating, and on the author floor`() {
        listOf("lake_tables_register", "lake_tables_import", "lake_tables_unregister").forEach { name ->
            assertAll(
                { McpToolCatalog.NAMES shouldContain name },
                { McpToolCatalog.isMutating(name) shouldBe true },
                { ScopeMatrix.requiredScopeForTool(name) shouldBe Scope.AUTHOR },
            )
        }
    }

    @Test
    fun `register assembles the REST body and renders the stored row`() {
        val body = slot<com.fasterxml.jackson.databind.JsonNode>()
        every { service.register(lake, capture(body), any()) } returns table

        val result =
            LakeTablesRegisterTool(registry, service).call(
                McpArguments(
                    mapOf(
                        "name" to "sample-lake",
                        "namespace" to "nyc.mobility",
                        "table" to "hvfhv_zone_day",
                        "format" to "parquet",
                        "location" to "s3://datapipelines-co/sample-data/lake/v1/hvfhv_zone_day/part-0.parquet",
                    ),
                ),
                McpFixtures.ctx(Scope.AUTHOR),
            )

        assertAll(
            // The service gets the REST body shape: the table argument lands in `name`.
            { body.captured.get("name").asText() shouldBe "hvfhv_zone_day" },
            { body.captured.get("namespace").asText() shouldBe "nyc.mobility" },
            { body.captured.get("format").asText() shouldBe "parquet" },
            {
                ExecutorJson.write(result) shouldContain "\"qualified_name\":\"nyc.mobility.hvfhv_zone_day\""
            },
        )
    }

    @Test
    fun `import forwards every argument but the datasource name verbatim`() {
        val body = slot<com.fasterxml.jackson.databind.JsonNode>()
        every { service.importTables(lake, capture(body), any()) } returns
            co.datapipelines.application.datasources
                .LakeImportResult(listOf(table), emptyList())

        LakeTablesImportTool(registry, service).call(
            McpArguments(
                mapOf(
                    "name" to "sample-lake",
                    "manifest_url" to "s3://datapipelines-co/sample-data/lake/v1/manifest.json",
                    "namespace" to listOf("nyc", "mobility"),
                ),
            ),
            McpFixtures.ctx(Scope.AUTHOR),
        )

        assertAll(
            { body.captured.get("manifest_url").asText() shouldBe "s3://datapipelines-co/sample-data/lake/v1/manifest.json" },
            { body.captured.get("namespace").size() shouldBe 2 },
            { body.captured.has("name") shouldBe false },
        )
    }

    @Test
    fun `unregister accepts both namespace spellings and reports the deletion`() {
        val namespace = slot<List<String>>()
        every { service.unregister(lake, capture(namespace), any(), any()) } returns Unit

        val result =
            LakeTablesUnregisterTool(registry, service).call(
                McpArguments(mapOf("name" to "sample-lake", "namespace" to "nyc.mobility", "table" to "hvfhv_zone_day")),
                McpFixtures.ctx(Scope.AUTHOR),
            )

        @Suppress("UNCHECKED_CAST")
        val payload = result as Map<String, Any?>
        assertAll(
            { namespace.captured shouldBe listOf("nyc", "mobility") },
            { payload["deleted"] shouldBe true },
            { payload["table"] shouldBe "nyc.mobility.hvfhv_zone_day" },
        )
    }

    @Test
    fun `the visibility gate fires BEFORE the service - an invisible datasource is not-found`() {
        // No stubbing at all: if any tool reached the service, the strict mock throws, which
        // would surface as the wrong failure — so a passing test proves the gate preceded it.
        shouldThrow<DatapipelinesException> {
            LakeTablesRegisterTool(registry, service).call(
                McpArguments(
                    mapOf(
                        "name" to "other-lake",
                        "namespace" to "nyc",
                        "table" to "t",
                        "format" to "parquet",
                        "location" to "s3://b/x",
                    ),
                ),
                McpFixtures.ctx(Scope.AUTHOR),
            )
        }.code shouldBe PipelineErrorCodes.Datasource.NOT_FOUND
    }

    @Test
    fun `the dispatcher audits each mutating call as a write`() {
        val sink = RecordingAuditSink()
        every { service.register(lake, any(), any()) } returns table
        val dispatcher = McpToolDispatcher(tools(), sink)

        dispatcher.call(
            McpFixtures.request(
                "lake_tables_register",
                mapOf(
                    "name" to "sample-lake",
                    "namespace" to "nyc",
                    "table" to "t",
                    "format" to "parquet",
                    "location" to "s3://b/x",
                ),
            ),
            McpFixtures.ctx(Scope.AUTHOR),
        )

        val events = sink.rows.map { it.first }
        assertAll(
            { events shouldContain "mcp.tool.called" },
            // The mutating declaration is what makes the write visible: called AND write (052/R4).
            { events shouldContain "mcp.tool.write" },
        )
    }

    /**
     * A real in-memory [AuditEventSink] — never a strict mock: a mock would make a MISSING
     * emission the passing state (the module's own rule, from MISTAKES.md).
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
}
