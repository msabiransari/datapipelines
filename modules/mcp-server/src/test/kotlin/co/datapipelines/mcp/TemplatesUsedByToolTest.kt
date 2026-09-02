package co.datapipelines.mcp

import co.datapipelines.auth.Scope
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
    private val usage = mockk<TemplateUsageService>()
    private val ctx = McpFixtures.ctx(Scope.READ)
    private val tool = TemplatesUsedByTool(usage)

    private val pipelineId = UUID.fromString("11111111-1111-1111-1111-111111111111")

    private fun reference(
        pipeline: String,
        node: String,
        pipelineVersion: Int = 7,
        status: PipelineVersionStatus = PipelineVersionStatus.RELEASED,
    ) = co.datapipelines.pipeline.TemplatePin(pipelineId, pipeline, pipelineVersion, status, node, 2)

    @Test
    fun `the payload names pipeline, node and carrying pipeline version - enough to act on`() {
        every { usage.usedBy(any(), "fetch_orders.sql", 2) } returns
            TemplateUsageService.UsedBy(
                templateId = "fetch_orders.sql",
                version = 2,
                references = listOf(reference("p1", "fetch"), reference("p2", "load", status = PipelineVersionStatus.DRAFT)),
                pipelineCount = 2,
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
    fun `an unknown template is the catalogued not-found, a missing version a protocol error`() {
        every { usage.usedBy(any(), "nope.sql", 1) } throws
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
