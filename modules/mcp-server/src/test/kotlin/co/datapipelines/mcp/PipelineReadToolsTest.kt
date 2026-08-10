package co.datapipelines.mcp

import co.datapipelines.auth.Scope
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.spec.McpError
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.util.UUID

class PipelineReadToolsTest {
    private val pipelines = mockk<PipelineRepository>()
    private val ctx = McpFixtures.ctx(Scope.READ)

    private val revenue = McpFixtures.pipelineRecord(name = "monthly_revenue", displayName = "Monthly Revenue")
    private val churn =
        McpFixtures.pipelineRecord(
            id = UUID.fromString("11111111-1111-1111-1111-111111111112"),
            name = "customer_churn",
            displayName = "Customer Churn",
            description = "Churn by cohort.",
        )

    @Test
    fun `list returns metadata only, never the body`() {
        every { pipelines.findAll(null) } returns listOf(revenue)

        val payload = PipelinesListTool(pipelines).call(McpArguments(emptyMap()), ctx)
        val first = (payload as List<*>).first() as Map<*, *>

        assertAll(
            { first["id"] shouldBe McpFixtures.PIPELINE_ID.toString() },
            { first["name"] shouldBe "monthly_revenue" },
            { first["version"] shouldBe 1 },
            { first.containsKey("nodes") shouldBe false },
            { first.containsKey("body") shouldBe false },
        )
    }

    @Test
    fun `q searches name, display name and description case-insensitively`() {
        every { pipelines.findAll(null) } returns listOf(revenue, churn)

        val hits = PipelinesListTool(pipelines).call(McpArguments(mapOf("q" to "CHURN")), ctx) as List<*>

        hits.map { (it as Map<*, *>)["name"] } shouldContainExactly listOf("customer_churn")
    }

    @Test
    fun `owner is pushed down to the repository`() {
        every { pipelines.findAll(McpFixtures.OTHER_USER) } returns emptyList()

        val hits =
            PipelinesListTool(pipelines).call(McpArguments(mapOf("owner" to McpFixtures.OTHER_USER.toString())), ctx) as List<*>

        hits.size shouldBe 0
    }

    @Test
    fun `the datasource filter reads node sources from the stored body`() {
        every { pipelines.findAll(null) } returns listOf(revenue, churn)
        every { pipelines.findVersionBody(revenue.id, 1) } returns McpFixtures.pipelineBody(source = "pg-prod")
        every { pipelines.findVersionBody(churn.id, 1) } returns McpFixtures.pipelineBody(name = "customer_churn", source = "mysql-prod")

        val hits = PipelinesListTool(pipelines).call(McpArguments(mapOf("datasource" to "mysql-prod")), ctx) as List<*>

        hits.map { (it as Map<*, *>)["name"] } shouldContainExactly listOf("customer_churn")
    }

    @Test
    fun `limit caps the page`() {
        every { pipelines.findAll(null) } returns listOf(revenue, churn)

        val hits = PipelinesListTool(pipelines).call(McpArguments(mapOf("limit" to 1)), ctx) as List<*>

        hits.size shouldBe 1
    }

    @Test
    fun `get returns the stored body of the current version`() {
        every { pipelines.findById(McpFixtures.PIPELINE_ID) } returns revenue
        every { pipelines.findVersionBody(McpFixtures.PIPELINE_ID, 1) } returns McpFixtures.pipelineBody()

        val body = PipelinesGetTool(pipelines).call(McpArguments(mapOf("id" to McpFixtures.PIPELINE_ID.toString())), ctx)

        McpTools.readTree(body.toString())["name"].asText() shouldBe "monthly_revenue"
    }

    @Test
    fun `get honours an explicit version`() {
        every { pipelines.findById(McpFixtures.PIPELINE_ID) } returns McpFixtures.pipelineRecord(version = 4)
        every { pipelines.findVersionBody(McpFixtures.PIPELINE_ID, 2) } returns McpFixtures.pipelineBody(name = "v2")

        val body =
            PipelinesGetTool(pipelines).call(
                McpArguments(mapOf("id" to McpFixtures.PIPELINE_ID.toString(), "version" to 2)),
                ctx,
            )

        McpTools.readTree(body.toString())["name"].asText() shouldBe "v2"
    }

    @Test
    fun `an unknown pipeline is a catalogued not-found`() {
        every { pipelines.findById(any()) } returns null

        val error =
            shouldThrow<DatapipelinesException> {
                PipelinesGetTool(pipelines).call(McpArguments(mapOf("id" to McpFixtures.PIPELINE_ID.toString())), ctx)
            }
        error.code shouldBe PipelineErrorCodes.Execution.NOT_FOUND
    }

    @Test
    fun `an unknown version of a known pipeline is a catalogued not-found`() {
        every { pipelines.findById(McpFixtures.PIPELINE_ID) } returns revenue
        every { pipelines.findVersionBody(McpFixtures.PIPELINE_ID, 9) } returns null

        val error =
            shouldThrow<DatapipelinesException> {
                PipelinesGetTool(pipelines).call(
                    McpArguments(mapOf("id" to McpFixtures.PIPELINE_ID.toString(), "version" to 9)),
                    ctx,
                )
            }
        error.details["version"] shouldBe 9
    }

    @Test
    fun `a version below 1 is refused, never silently read as version 1`() {
        every { pipelines.findById(McpFixtures.PIPELINE_ID) } returns revenue

        assertAll(
            {
                shouldThrow<McpError> {
                    PipelinesGetTool(pipelines).call(
                        McpArguments(mapOf("id" to McpFixtures.PIPELINE_ID.toString(), "version" to 0)),
                        ctx,
                    )
                }.jsonRpcError.code() shouldBe McpArguments.INVALID_PARAMS
            },
            {
                shouldThrow<McpError> {
                    PipelinesGetTool(pipelines).call(
                        McpArguments(mapOf("id" to McpFixtures.PIPELINE_ID.toString(), "version" to -3)),
                        ctx,
                    )
                }.jsonRpcError.code() shouldBe McpArguments.INVALID_PARAMS
            },
        )
        verify(exactly = 0) { pipelines.findVersionBody(any(), any()) }
    }

    @Test
    fun `a missing id is a protocol error, not a tool error`() {
        val error = shouldThrow<McpError> { PipelinesGetTool(pipelines).call(McpArguments(emptyMap()), ctx) }
        error.jsonRpcError.code() shouldBe McpArguments.INVALID_PARAMS
    }

    @Test
    fun `a non-uuid id is a protocol error`() {
        val error = shouldThrow<McpError> { PipelinesGetTool(pipelines).call(McpArguments(mapOf("id" to "not-a-uuid")), ctx) }
        error.jsonRpcError.code() shouldBe McpArguments.INVALID_PARAMS
    }
}
