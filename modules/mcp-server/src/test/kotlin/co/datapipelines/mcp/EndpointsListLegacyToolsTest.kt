package co.datapipelines.mcp

import co.datapipelines.application.endpoints.EndpointPath
import co.datapipelines.application.endpoints.EndpointPublishService
import co.datapipelines.application.endpoints.EndpointRow
import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.pipeline.PipelineRepository
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * `endpoints_list` answers a LEGACY row flagged (#274): `"legacy": true` with its `reason`, the
 * stored `enabled`, and no parse-derived fields. The assertion is about the agent's and the
 * operator's problem — a row that predates the current grammar must be VISIBLE and actionable
 * (`endpoints_delete` removes it), never a silent mystery or a boot-killing throw. The list
 * shape is pinned row for row: a valid row stays byte-identical to the shape before #274, and
 * the legacy row is distinguishable by its `legacy` key alone.
 */
class EndpointsListLegacyToolsTest {
    private val publishing = mockk<EndpointPublishService>()
    private val pipelines = mockk<PipelineRepository>()
    private val tool = EndpointsTools.ListTool(publishing, pipelines)
    private val ctx = McpFixtures.ctx()

    @Test
    fun `a legacy row is listed last, flagged with legacy true and its reason`() {
        every { publishing.list(any()) } returns listOf(validEndpoint())
        every { publishing.listLegacy(any()) } returns listOf(legacyRow())
        every { pipelines.findById(any(), any()) } returns null

        @Suppress("UNCHECKED_CAST")
        val endpoints = tool.call(McpArguments(emptyMap()), ctx).asMap()["endpoints"] as List<Map<String, Any?>>

        endpoints.size shouldBe 2

        // The valid row: exactly the pre-#274 shape — no `legacy` key at all.
        endpoints[0] shouldBe
            mapOf(
                "path" to "/nyc/v1/revenue/{borough}",
                "pipeline" to null,
                "timeout_seconds" to 30,
                "description" to "",
                "enabled" to true,
                "path_variables" to listOf("borough"),
                "url" to "/api/nyc/v1/revenue/{borough}",
            )

        // The legacy row: flagged, reasoned, actionable — and no path_variables (no parse).
        endpoints[1] shouldBe
            mapOf(
                "path" to "/nyc/revenue-by-borough",
                "pipeline" to null,
                "enabled" to false,
                "legacy" to true,
                "reason" to REASON,
                "url" to "/api/nyc/revenue-by-borough",
            )
    }

    @Test
    fun `a stored path longer than the grammar allows is echoed cut at EndpointPath MAX_LENGTH - path and url alike (#286)`() {
        // `path_pattern` is TEXT with no CHECK, so only a database write stores one this long;
        // the echo is bounded, the stored row (unpublish is BY PATH) is not touched.
        val long = "/nyc/" + "x".repeat(LONG_TAIL)
        every { publishing.list(any()) } returns emptyList()
        every { publishing.listLegacy(any()) } returns listOf(legacyRow(path = long))
        every { pipelines.findById(any(), any()) } returns null

        @Suppress("UNCHECKED_CAST")
        val row = (tool.call(McpArguments(emptyMap()), ctx).asMap()["endpoints"] as List<Map<String, Any?>>).single()

        row["path"] shouldBe long.take(EndpointPath.MAX_LENGTH)
        row["url"] shouldBe "/api" + long.take(EndpointPath.MAX_LENGTH)
    }

    private fun validEndpoint(): PublishedEndpoint =
        PublishedEndpoint.of(
            id = UUID.nameUUIDFromBytes("/nyc/v1/revenue/{borough}".toByteArray()),
            workspaceId = WORKSPACE,
            pathPattern = "/nyc/v1/revenue/{borough}",
            pipelineId = UUID.randomUUID(),
            timeoutSeconds = 30,
            description = "",
            isEnabled = true,
            createdBy = UUID.randomUUID(),
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    private fun legacyRow(path: String = "/nyc/revenue-by-borough"): EndpointRow.Legacy =
        EndpointRow.Legacy(
            id = UUID.nameUUIDFromBytes(path.toByteArray()),
            workspaceId = WORKSPACE,
            pathPattern = path,
            pipelineId = UUID.randomUUID(),
            reason = REASON,
            enabled = false,
        )

    private companion object {
        val WORKSPACE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000274")
        const val LONG_TAIL = 1_000
        const val REASON = "Path has 2 segment(s); an endpoint is at least 3 — /<category>/<version>/<path…> (R-EP5)."

        private fun Any?.asMap(): Map<String, Any?> =
            @Suppress("UNCHECKED_CAST")
            (this as Map<String, Any?>)
    }
}
