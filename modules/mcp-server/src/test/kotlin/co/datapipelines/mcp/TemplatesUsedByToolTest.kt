package co.datapipelines.mcp

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.templates.TemplateUsageService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.spec.McpError
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.util.UUID

/**
 * `templates_used_by` (§6.2.8) — the payload shape, the working-scan contract, and the
 * protocol-versus-application error split. The scan itself (two questions, two scans) is
 * pinned by `PipelineRepositoryIntegrationTest` and `TemplateUsageServiceTest`; this suite
 * owns the tool's argument handling and wire shape.
 */
class TemplatesUsedByToolTest {
    private val usage = mockk<co.datapipelines.application.templates.TemplateUsage>()
    private val ctx = McpFixtures.ctx()
    private val tool = TemplatesUsedByTool(usage, McpFixtures.EVERYTHING_LENS)

    private val pipelineId = UUID.fromString("11111111-1111-1111-1111-111111111111")

    private fun reference(
        pipeline: String,
        node: String,
        pipelineVersion: Int = 7,
        status: PipelineVersionStatus = PipelineVersionStatus.RELEASED,
    ) = co.datapipelines.pipeline.TemplatePin(pipelineId, pipeline, pipelineVersion, status, node, 2)

    @Test
    fun `the payload names pipeline, node and carrying pipeline version - enough to act on`() {
        every { usage.usedBy(any(), any(), "fetch_orders.sql", 2) } returns
            co.datapipelines.application.templates.TemplateUsage.Combined(
                templateId = "fetch_orders.sql",
                version = 2,
                pipelineCount = 2,
                pipelineReferences =
                    listOf(reference("p1", "fetch"), reference("p2", "load", status = PipelineVersionStatus.DRAFT)),
                parameterSetReferences = emptyList(),
                visualizationReferences = emptyList(),
            )

        val payload = tool.call(McpArguments(mapOf("id" to "fetch_orders.sql", "version" to 2)), ctx) as Map<*, *>

        assertAll(
            { payload["template"] shouldBe mapOf("id" to "fetch_orders.sql", "version" to 2) },
            { payload["scan"] shouldBe "working_version" },
            { payload["pipeline_count"] shouldBe 2 },
            {
                (payload["references"] as List<*>).map { (it as Map<*, *>)["pipeline"] to it["node_id"] } shouldContainExactly
                    listOf("p1" to "fetch", "p2" to "load")
            },
            {
                (payload["references"] as List<*>).first().let { first ->
                    (first as Map<*, *>)["pipeline_version"] shouldBe 7
                    first["pipeline_version_status"] shouldBe "RELEASED"
                }
            },
        )
    }

    @Test
    fun `a visualization-only pin is a real reference - named on the wire beside the pipelines and the sets (#320)`() {
        val chart = UUID.fromString("22222222-2222-2222-2222-222222222222")
        every { usage.usedBy(any(), any(), "shape.sql", 3) } returns
            co.datapipelines.application.templates.TemplateUsage.Combined(
                templateId = "shape.sql",
                version = 3,
                pipelineCount = 0,
                pipelineReferences = emptyList(),
                parameterSetReferences = emptyList(),
                visualizationReferences =
                    listOf(co.datapipelines.visualization.ArtifactPin(chart, "acme/charts/revenue", 5, PipelineVersionStatus.DRAFT, 3)),
            )

        val payload = tool.call(McpArguments(mapOf("id" to "shape.sql", "version" to 3)), ctx) as Map<*, *>

        assertAll(
            { payload["pipeline_count"] shouldBe 0 },
            { payload["references"] shouldBe emptyList<Any>() },
            {
                payload["visualization_references"] shouldBe
                    listOf(
                        mapOf(
                            "visualization" to "acme/charts/revenue",
                            "visualization_id" to chart.toString(),
                            "visualization_version" to 5,
                            "visualization_version_status" to "DRAFT",
                        ),
                    )
            },
        )
    }

    @Test
    fun `an unknown template is the catalogued not-found, a missing version a protocol error`() {
        every { usage.usedBy(any(), any(), "nope.sql", 1) } throws
            co.datapipelines.typesystem.DatapipelinesException(
                code = co.datapipelines.pipeline.PipelineErrorCodes.Template.NOT_FOUND,
                message = "Template 'nope.sql' does not exist.",
                details = mapOf("template_id" to "nope.sql"),
            )
        shouldThrow<co.datapipelines.typesystem.DatapipelinesException> {
            tool.call(McpArguments(mapOf("id" to "nope.sql", "version" to 1)), ctx)
        }.code shouldBe co.datapipelines.pipeline.PipelineErrorCodes.Template.NOT_FOUND

        // The version argument is required (D2: the question is per version) and is never
        // clamped — a version below 1 is a protocol refusal, not a silent neighbour read.
        shouldThrow<McpError> { tool.call(McpArguments(mapOf("id" to "t.sql", "version" to 0)), ctx) }
            .jsonRpcError
            .code() shouldBe McpArguments.INVALID_PARAMS
        shouldThrow<McpError> { tool.call(McpArguments(mapOf("id" to "t.sql")), ctx) }
            .jsonRpcError
            .code() shouldBe McpArguments.INVALID_PARAMS
    }
}
