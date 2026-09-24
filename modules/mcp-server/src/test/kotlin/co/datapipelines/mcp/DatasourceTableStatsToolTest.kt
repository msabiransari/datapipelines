package co.datapipelines.mcp

import co.datapipelines.datasources.ColumnStats
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.IndexStats
import co.datapipelines.datasources.SchemaIntrospector
import co.datapipelines.datasources.TableStats
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/**
 * `datasources_get_table_stats` (§7C, 107) — the tool's translation layer: the §5.3 visibility
 * gate, the namespace argument, and the wire-map pass-through. The catalog reads themselves are
 * pinned in the datasources module's TableStats suites.
 */
class DatasourceTableStatsToolTest {
    private val datasources = mockk<DatasourceRegistry>()
    private val introspector = mockk<SchemaIntrospector>()
    private val tool = DatasourcesGetTableStatsTool(introspector, datasources)
    private val ctx = McpFixtures.ctx()

    private val gated = McpFixtures.datasource("sample-trips")

    private val stats =
        TableStats(
            rowEstimate = "42000000",
            statsAsOf = "2026-09-08T04:00:00Z",
            statsSource = "pg_class",
            indexes = listOf(IndexStats("trips_pkey", listOf("id"), unique = true, primary = true)),
            columns =
                listOf(
                    ColumnStats("city", nDistinct = "812", distinctIsRatio = false, nullFraction = 0.01, min = "Abbeville", max = "Zurich"),
                ),
        )

    @Test
    fun `the happy path gates visibility and maps the stats payload`() {
        every { datasources.getVisible("sample-trips", McpFixtures.WORKSPACE_ID) } returns gated
        every { introspector.tableStats(gated, "trips", listOf("public")) } returns stats

        val payload =
            tool.call(
                McpArguments(mapOf("name" to "sample-trips", "table" to "trips", "namespace" to listOf("public"))),
                ctx,
            ) as Map<*, *>

        assertAll(
            { payload["row_estimate"] shouldBe "42000000" },
            { payload["stats_as_of"] shouldBe "2026-09-08T04:00:00Z" },
            { payload["stats_source"] shouldBe "pg_class" },
            { (payload["indexes"] as List<*>).single().let { (it as Map<*, *>)["name"] shouldBe "trips_pkey" } },
            { (payload["columns"] as List<*>).single().let { (it as Map<*, *>)["n_distinct"] shouldBe "812" } },
        )
    }

    @Test
    fun `a lake table says its partition status out loud - the registered name, or an explicit null (125 B2)`() {
        val lake = McpFixtures.datasource("events-lake", dialect = Dialect.LAKE)
        every { datasources.getVisible("events-lake", McpFixtures.WORKSPACE_ID) } returns lake
        val partitioned =
            stats.copy(
                indexes =
                    listOf(
                        IndexStats(
                            "partition",
                            listOf("event_date"),
                            unique = false,
                            primary = false,
                            kind = IndexStats.INDEX_KIND_PARTITION,
                        ),
                    ),
            )
        every { introspector.tableStats(lake, "events_by_day", null) } returns partitioned
        every { introspector.tableStats(lake, "events_archive", null) } returns stats.copy(indexes = emptyList())

        val registered =
            tool.call(McpArguments(mapOf("name" to "events-lake", "table" to "events_by_day")), ctx) as Map<*, *>
        val unregistered =
            tool.call(McpArguments(mapOf("name" to "events-lake", "table" to "events_archive")), ctx) as Map<*, *>

        assertAll(
            { registered["partition_column"] shouldBe "event_date" },
            // The key is PRESENT with a null value — an absence an agent must not have to infer.
            { unregistered.containsKey("partition_column") shouldBe true },
            { unregistered["partition_column"] shouldBe null },
        )
    }

    @Test
    fun `a non-lake table carries no partition_column key`() {
        every { datasources.getVisible("sample-trips", McpFixtures.WORKSPACE_ID) } returns gated
        every { introspector.tableStats(gated, "trips", null) } returns stats

        val payload = tool.call(McpArguments(mapOf("name" to "sample-trips", "table" to "trips")), ctx) as Map<*, *>

        payload.containsKey("partition_column") shouldBe false
    }

    @Test
    fun `a datasource in another workspace is not-found before any stats read`() {
        every { datasources.getVisible("sample-trips", McpFixtures.WORKSPACE_ID) } returns null

        val thrown =
            shouldThrow<DatapipelinesException> {
                tool.call(McpArguments(mapOf("name" to "sample-trips", "table" to "trips")), ctx)
            }

        thrown.code shouldBe PipelineErrorCodes.Datasource.NOT_FOUND
        verify(exactly = 0) { introspector.tableStats(any<co.datapipelines.datasources.Datasource>(), any(), any()) }
    }
}
