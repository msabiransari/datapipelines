package co.datapipelines.mcp

import co.datapipelines.application.checks.PipelineCheckRunner
import co.datapipelines.pipeline.CheckExpectation
import co.datapipelines.pipeline.CheckRunOutcome
import co.datapipelines.pipeline.CheckRunVerdict
import co.datapipelines.pipeline.CheckRunVia
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionDetail
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.typesystem.DatapipelinesException
import com.fasterxml.jackson.databind.JsonNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.time.Instant
import java.util.UUID

class PipelineRunChecksToolTest {
    private val pipelines = mockk<PipelineRepository>()
    private val runner = mockk<PipelineCheckRunner>()
    private val service = McpFixtures.pipelineService(pipelines)
    private val ctx = McpFixtures.ctx()

    private val tool = PipelineRunChecksTool(service, runner)

    private val args = McpArguments(mapOf("id" to McpFixtures.PIPELINE_ID.toString()))

    private fun storedPipeline() {
        every { pipelines.findById(any(), McpFixtures.PIPELINE_ID) } returns McpFixtures.pipelineRecord()
        // D56: with no `version` argument the tool resolves the WORKING version, so it asks for a
        // draft first. This fixture is a released pipeline with none.
        every { pipelines.findDraftDetail(any(), McpFixtures.PIPELINE_ID) } returns null
    }

    /** Stubs the runner's surfaces-entry overload (the typed `any()`s are how mockk picks it over the gate's). */
    private fun stubRun(result: List<CheckRunOutcome>?) {
        every {
            runner.run(
                any<UUID>(),
                any<UUID>(),
                any<Int>(),
                any<Map<String, JsonNode>>(),
                any<CheckRunVia>(),
                any<UUID>(),
                any<String>(),
            )
        } returns result
    }

    private fun outcome(
        expected: CheckExpectation = CheckExpectation(kind = "value", value = 74.62, tolerance = 0.01),
        observed: String? = "74.62",
        verdict: CheckRunVerdict = CheckRunVerdict.PASS,
        message: String? = null,
    ) = CheckRunOutcome(
        checkId = "manhattan_share",
        name = "Manhattan share",
        expected = expected,
        observed = observed,
        verdict = verdict,
        message = message,
        ranAt = Instant.parse("2026-09-14T12:00:00Z"),
    )

    @Test
    fun `the payload is the REST checkRuns shape - version and one entry per run`() {
        storedPipeline()
        stubRun(listOf(outcome()))

        @Suppress("UNCHECKED_CAST")
        val payload = tool.call(args, ctx) as Map<String, Any?>

        payload["version"] shouldBe 1
        @Suppress("UNCHECKED_CAST")
        val run = (payload["runs"] as List<Map<String, Any?>>).single()
        assertAll(
            { run["check_id"] shouldBe "manhattan_share" },
            { run["name"] shouldBe "Manhattan share" },
            { run["observed"] shouldBe "74.62" },
            { run["verdict"] shouldBe "pass" },
            { run["message"] shouldBe null },
            { run.containsKey("message") shouldBe true },
            { run["ran_at"] shouldBe "2026-09-14T12:00:00Z" },
            {
                @Suppress("UNCHECKED_CAST")
                (run["expected"] as Map<String, Any?>) shouldBe
                    mapOf("kind" to "value", "value" to 74.62, "tolerance" to 0.01)
            },
        )
    }

    @Test
    fun `the runner is stamped via MCP with the principal and the correlation id, and the working version is the default`() {
        storedPipeline()
        val workspace = slot<UUID>()
        val pipelineId = slot<UUID>()
        val version = slot<Int>()
        val via = slot<CheckRunVia>()
        val actor = slot<UUID>()
        val correlation = slot<String>()
        every {
            runner.run(
                capture(workspace),
                capture(pipelineId),
                capture(version),
                any<Map<String, JsonNode>>(),
                capture(via),
                capture(actor),
                capture(correlation),
            )
        } returns listOf(outcome())

        tool.call(args, ctx)

        assertAll(
            { workspace.captured shouldBe McpFixtures.WORKSPACE_ID },
            { pipelineId.captured shouldBe McpFixtures.PIPELINE_ID },
            { version.captured shouldBe 1 },
            { via.captured shouldBe CheckRunVia.MCP },
            { actor.captured shouldBe McpFixtures.USER },
            { correlation.captured shouldBe McpFixtures.CORRELATION_ID.toString() },
        )
    }

    @Test
    fun `with no version, a pipeline with a draft runs the DRAFT's checks`() {
        every { pipelines.findById(any(), McpFixtures.PIPELINE_ID) } returns McpFixtures.pipelineRecord(version = 1)
        every { pipelines.findDraftDetail(any(), McpFixtures.PIPELINE_ID) } returns
            PipelineVersionDetail(
                pipelineId = McpFixtures.PIPELINE_ID,
                version = 2,
                status = PipelineVersionStatus.DRAFT,
                bodyHash = "hash-v2",
                createdAt = Instant.EPOCH,
                createdBy = McpFixtures.USER,
            )
        val version = slot<Int>()
        every {
            runner.run(
                any<UUID>(),
                any<UUID>(),
                capture(version),
                any<Map<String, JsonNode>>(),
                any<CheckRunVia>(),
                any<UUID>(),
                any<String>(),
            )
        } returns listOf(outcome())

        @Suppress("UNCHECKED_CAST")
        val payload = tool.call(args, ctx) as Map<String, Any?>

        assertAll(
            { version.captured shouldBe 2 },
            { payload["version"] shouldBe 2 },
        )
    }

    @Test
    fun `an explicit version runs that version and the parameters reach the runner as JsonNodes`() {
        storedPipeline()
        val version = slot<Int>()
        val parameters = slot<Map<String, JsonNode>>()
        every {
            runner.run(any<UUID>(), any<UUID>(), capture(version), capture(parameters), any<CheckRunVia>(), any<UUID>(), any<String>())
        } returns listOf(outcome())

        @Suppress("UNCHECKED_CAST")
        val payload =
            tool.call(
                McpArguments(
                    mapOf(
                        "id" to McpFixtures.PIPELINE_ID.toString(),
                        "version" to 3,
                        "parameters" to mapOf("month" to "2026-07"),
                    ),
                ),
                ctx,
            ) as Map<String, Any?>

        assertAll(
            { version.captured shouldBe 3 },
            { parameters.captured["month"]?.asText() shouldBe "2026-07" },
            { payload["version"] shouldBe 3 },
        )
    }

    @Test
    fun `a null from the runner is the house not-found refusal`() {
        storedPipeline()
        stubRun(null)

        shouldThrow<DatapipelinesException> { tool.call(args, ctx) }.code shouldBe PipelineErrorCodes.Execution.NOT_FOUND
    }

    @Test
    fun `an unknown pipeline never reaches the runner`() {
        every { pipelines.findById(any(), any()) } returns null

        shouldThrow<DatapipelinesException> {
            tool.call(McpArguments(mapOf("id" to UUID.randomUUID().toString())), ctx)
        }.code shouldBe PipelineErrorCodes.Execution.NOT_FOUND
        verify(exactly = 0) {
            runner.run(any<UUID>(), any<UUID>(), any<Int>(), any<Map<String, JsonNode>>(), any<CheckRunVia>(), any<UUID>(), any<String>())
        }
    }

    @Test
    fun `expected serializes its non-null members only and an error run carries no observed`() {
        storedPipeline()
        stubRun(
            listOf(
                outcome(
                    expected = CheckExpectation(kind = "range", min = 1.0, max = 2.0),
                    observed = null,
                    verdict = CheckRunVerdict.ERROR,
                    message = "Datasource 'pg-prod' is not registered or not visible to this workspace.",
                ),
            ),
        )

        @Suppress("UNCHECKED_CAST")
        val payload = tool.call(args, ctx) as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val run = (payload["runs"] as List<Map<String, Any?>>).single()
        assertAll(
            { run["expected"] shouldBe mapOf("kind" to "range", "min" to 1.0, "max" to 2.0) },
            { run["observed"] shouldBe null },
            { run.containsKey("observed") shouldBe true },
            { run["verdict"] shouldBe "error" },
            { (run["message"] as String).startsWith("Datasource 'pg-prod'") shouldBe true },
        )
    }

    @Test
    fun `a version with no checks returns an empty runs array, not an error`() {
        storedPipeline()
        stubRun(emptyList())

        @Suppress("UNCHECKED_CAST")
        val payload = tool.call(args, ctx) as Map<String, Any?>

        assertAll(
            { payload["version"] shouldBe 1 },
            { payload["runs"] shouldBe emptyList<Map<String, Any?>>() },
        )
    }
}
