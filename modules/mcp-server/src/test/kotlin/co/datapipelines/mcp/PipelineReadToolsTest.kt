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
    private val service = McpFixtures.pipelineService(pipelines)

    // 040: the upgrade-signal service. Relaxed so the existing get-tests see an empty signal
    // (no upgrade_available key) without each stubbing it; the signal's own tests stub it.
    private val usage = mockk<co.datapipelines.templates.TemplateUsageService>(relaxed = true)
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
        every { pipelines.findAll(any(), null) } returns listOf(revenue)

        val payload = PipelinesListTool(service).call(McpArguments(emptyMap()), ctx)
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
        every { pipelines.findAll(any(), null) } returns listOf(revenue, churn)

        val hits = PipelinesListTool(service).call(McpArguments(mapOf("q" to "CHURN")), ctx) as List<*>

        hits.map { (it as Map<*, *>)["name"] } shouldContainExactly listOf("customer_churn")
    }

    @Test
    fun `owner is pushed down to the repository`() {
        every { pipelines.findAll(any(), McpFixtures.OTHER_USER) } returns emptyList()

        val hits =
            PipelinesListTool(service).call(McpArguments(mapOf("owner" to McpFixtures.OTHER_USER.toString())), ctx) as List<*>

        hits.size shouldBe 0
    }

    @Test
    fun `the datasource filter is pushed down to SQL`() {
        every { pipelines.findAllByDatasource(any(), "mysql-prod", null) } returns listOf(churn)

        val hits = PipelinesListTool(service).call(McpArguments(mapOf("datasource" to "mysql-prod")), ctx) as List<*>

        hits.map { (it as Map<*, *>)["name"] } shouldContainExactly listOf("customer_churn")
    }

    @Test
    fun `limit caps the page`() {
        every { pipelines.findAll(any(), null) } returns listOf(revenue, churn)

        val hits = PipelinesListTool(service).call(McpArguments(mapOf("limit" to 1)), ctx) as List<*>

        hits.size shouldBe 1
    }

    @Test
    fun `get returns the stored body of the current version`() {
        every { pipelines.findById(any(), McpFixtures.PIPELINE_ID) } returns revenue
        every { pipelines.findDraftDetail(any(), McpFixtures.PIPELINE_ID) } returns null
        every { pipelines.findVersionBody(any(), McpFixtures.PIPELINE_ID, 1) } returns McpFixtures.pipelineBody()
        every { pipelines.findVersionDetail(any(), McpFixtures.PIPELINE_ID, 1) } returns
            co.datapipelines.pipeline.PipelineVersionDetail(
                pipelineId = McpFixtures.PIPELINE_ID,
                version = 1,
                status = co.datapipelines.pipeline.PipelineVersionStatus.RELEASED,
                bodyHash = "hash-v1",
                createdAt = java.time.Instant.EPOCH,
                createdBy = McpFixtures.USER,
            )

        val body = PipelinesGetTool(service, usage).call(McpArguments(mapOf("id" to McpFixtures.PIPELINE_ID.toString())), ctx)

        McpTools.readTree(body.toString())["name"].asText() shouldBe "monthly_revenue"
        // 040 D5: an empty upgrade signal is OMITTED, not an empty array — the envelope's
        // omit-when-empty convention (the relaxed mock answers emptyList).
        McpTools.readTree(body.toString()).has("upgrade_available") shouldBe false
    }

    private fun upgrade(
        node: String,
        templateId: String,
        pinned: Int,
        latest: Int,
    ): co.datapipelines.templates.TemplateUsageService.UpgradeAvailable =
        co.datapipelines.templates.TemplateUsageService.UpgradeAvailable(
            node,
            templateId,
            pinned,
            latest,
        )

    @Test
    fun `get carries the upgrade signal for pins a newer released version outdates`() {
        every { pipelines.findById(any(), McpFixtures.PIPELINE_ID) } returns revenue
        every { pipelines.findDraftDetail(any(), McpFixtures.PIPELINE_ID) } returns null
        every { pipelines.findVersionBody(any(), McpFixtures.PIPELINE_ID, 1) } returns McpFixtures.pipelineBody()
        every { pipelines.findVersionDetail(any(), McpFixtures.PIPELINE_ID, 1) } returns
            co.datapipelines.pipeline.PipelineVersionDetail(
                pipelineId = McpFixtures.PIPELINE_ID,
                version = 1,
                status = co.datapipelines.pipeline.PipelineVersionStatus.RELEASED,
                bodyHash = "hash-v1",
                createdAt = java.time.Instant.EPOCH,
                createdBy = McpFixtures.USER,
            )
        every { usage.upgradeAvailable(any(), any<String>()) } returns
            listOf(
                upgrade("fetch_orders", "fetch_orders.sql", 2, 3),
                upgrade("join_revenue", "join_revenue.sql", 1, 4),
            )

        val body = PipelinesGetTool(service, usage).call(McpArguments(mapOf("id" to McpFixtures.PIPELINE_ID.toString())), ctx)
        val signal = McpTools.readTree(body.toString())["upgrade_available"]

        signal.isArray shouldBe true
        signal.size() shouldBe 2
        signal[0].let {
            it["node"].asText() shouldBe "fetch_orders"
            it["template_id"].asText() shouldBe "fetch_orders.sql"
            it["pinned"].asInt() shouldBe 2
            it["latest_released"].asInt() shouldBe 3
        }
        signal[1]["latest_released"].asInt() shouldBe 4
    }

    @Test
    fun `get defaults to the working version - the draft's body and status when one exists`() {
        every { pipelines.findById(any(), McpFixtures.PIPELINE_ID) } returns revenue
        every { pipelines.findDraftDetail(any(), McpFixtures.PIPELINE_ID) } returns
            co.datapipelines.pipeline.PipelineVersionDetail(
                pipelineId = McpFixtures.PIPELINE_ID,
                version = 2,
                status = co.datapipelines.pipeline.PipelineVersionStatus.DRAFT,
                bodyHash = "hash-v2",
                createdAt = java.time.Instant.EPOCH,
                createdBy = McpFixtures.USER,
                updatedBy = McpFixtures.USER,
                updatedAt = java.time.Instant.EPOCH,
            )
        every { pipelines.findVersionBody(any(), McpFixtures.PIPELINE_ID, 2) } returns McpFixtures.pipelineBody(name = "the_draft_body")
        every { pipelines.findVersionDetail(any(), McpFixtures.PIPELINE_ID, 2) } returns
            co.datapipelines.pipeline.PipelineVersionDetail(
                pipelineId = McpFixtures.PIPELINE_ID,
                version = 2,
                status = co.datapipelines.pipeline.PipelineVersionStatus.DRAFT,
                bodyHash = "hash-v2",
                createdAt = java.time.Instant.EPOCH,
                createdBy = McpFixtures.USER,
            )

        val body = PipelinesGetTool(service, usage).call(McpArguments(mapOf("id" to McpFixtures.PIPELINE_ID.toString())), ctx)
        val tree = McpTools.readTree(body.toString())

        // §7.1: the default is the working version — an agent must read the draft, never
        // silently rebase on released content — and the result says which one it returned.
        tree["name"].asText() shouldBe "the_draft_body"
        tree["version"].asInt() shouldBe 2
        tree["status"].asText() shouldBe "DRAFT"
        tree["body_hash"].asText() shouldBe "hash-v2"
    }

    @Test
    fun `get honours an explicit version`() {
        every { pipelines.findById(any(), McpFixtures.PIPELINE_ID) } returns McpFixtures.pipelineRecord(version = 4)
        every { pipelines.findDraftDetail(any(), McpFixtures.PIPELINE_ID) } returns null
        every { pipelines.findVersionBody(any(), McpFixtures.PIPELINE_ID, 2) } returns McpFixtures.pipelineBody(name = "v2")
        every { pipelines.findVersionDetail(any(), McpFixtures.PIPELINE_ID, 2) } returns
            co.datapipelines.pipeline.PipelineVersionDetail(
                pipelineId = McpFixtures.PIPELINE_ID,
                version = 2,
                status = co.datapipelines.pipeline.PipelineVersionStatus.RELEASED,
                bodyHash = "hash-v2",
                createdAt = java.time.Instant.EPOCH,
                createdBy = McpFixtures.USER,
            )

        val body =
            PipelinesGetTool(service, usage).call(
                McpArguments(mapOf("id" to McpFixtures.PIPELINE_ID.toString(), "version" to 2)),
                ctx,
            )

        McpTools.readTree(body.toString())["name"].asText() shouldBe "v2"
    }

    @Test
    fun `an unknown pipeline is a catalogued not-found`() {
        every { pipelines.findById(any(), any()) } returns null

        val error =
            shouldThrow<DatapipelinesException> {
                PipelinesGetTool(service, usage).call(McpArguments(mapOf("id" to McpFixtures.PIPELINE_ID.toString())), ctx)
            }
        error.code shouldBe PipelineErrorCodes.Execution.NOT_FOUND
    }

    @Test
    fun `an unknown version of a known pipeline is a catalogued not-found`() {
        every { pipelines.findById(any(), McpFixtures.PIPELINE_ID) } returns revenue
        every { pipelines.findVersionBody(any(), McpFixtures.PIPELINE_ID, 9) } returns null

        val error =
            shouldThrow<DatapipelinesException> {
                PipelinesGetTool(service, usage).call(
                    McpArguments(mapOf("id" to McpFixtures.PIPELINE_ID.toString(), "version" to 9)),
                    ctx,
                )
            }
        error.details["version"] shouldBe 9
    }

    @Test
    fun `a version below 1 is refused, never silently read as version 1`() {
        every { pipelines.findById(any(), McpFixtures.PIPELINE_ID) } returns revenue

        assertAll(
            {
                shouldThrow<McpError> {
                    PipelinesGetTool(service, usage).call(
                        McpArguments(mapOf("id" to McpFixtures.PIPELINE_ID.toString(), "version" to 0)),
                        ctx,
                    )
                }.jsonRpcError.code() shouldBe McpArguments.INVALID_PARAMS
            },
            {
                shouldThrow<McpError> {
                    PipelinesGetTool(service, usage).call(
                        McpArguments(mapOf("id" to McpFixtures.PIPELINE_ID.toString(), "version" to -3)),
                        ctx,
                    )
                }.jsonRpcError.code() shouldBe McpArguments.INVALID_PARAMS
            },
        )
        verify(exactly = 0) { pipelines.findVersionBody(any(), any(), any()) }
    }

    @Test
    fun `a missing id is a protocol error, not a tool error`() {
        val error = shouldThrow<McpError> { PipelinesGetTool(service, usage).call(McpArguments(emptyMap()), ctx) }
        error.jsonRpcError.code() shouldBe McpArguments.INVALID_PARAMS
    }

    @Test
    fun `a non-uuid id is a protocol error`() {
        val error = shouldThrow<McpError> { PipelinesGetTool(service, usage).call(McpArguments(mapOf("id" to "not-a-uuid")), ctx) }
        error.jsonRpcError.code() shouldBe McpArguments.INVALID_PARAMS
    }
}
