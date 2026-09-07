package co.datapipelines.mcp

import co.datapipelines.auth.Scope
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineFolder
import co.datapipelines.pipeline.PipelineFolderLevel
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

        val payload = PipelinesListTool(service, pipelines).call(McpArguments(emptyMap()), ctx)
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

        val hits = PipelinesListTool(service, pipelines).call(McpArguments(mapOf("q" to "CHURN")), ctx) as List<*>

        hits.map { (it as Map<*, *>)["name"] } shouldContainExactly listOf("customer_churn")
    }

    @Test
    fun `owner is pushed down to the repository`() {
        every { pipelines.findAll(any(), McpFixtures.OTHER_USER) } returns emptyList()

        val hits =
            PipelinesListTool(service, pipelines).call(McpArguments(mapOf("owner" to McpFixtures.OTHER_USER.toString())), ctx) as List<*>

        hits.size shouldBe 0
    }

    @Test
    fun `the datasource filter is pushed down to SQL`() {
        every { pipelines.findAllByDatasource(any(), "mysql-prod", null) } returns listOf(churn)

        val hits = PipelinesListTool(service, pipelines).call(McpArguments(mapOf("datasource" to "mysql-prod")), ctx) as List<*>

        hits.map { (it as Map<*, *>)["name"] } shouldContainExactly listOf("customer_churn")
    }

    @Test
    fun `limit caps the page`() {
        every { pipelines.findAll(any(), null) } returns listOf(revenue, churn)

        val hits = PipelinesListTool(service, pipelines).call(McpArguments(mapOf("limit" to 1)), ctx) as List<*>

        hits.size shouldBe 1
    }

    @Test
    fun `prefix browses ONE level - folders with counts and the level's own leaves`() {
        // 067. `prefix: ""` is the ROOT: present-but-empty, which is a different request from
        // an absent prefix (that one is the flat listing).
        every { pipelines.listFolder(McpFixtures.WORKSPACE_ID, null, 0, 50) } returns
            PipelineFolderLevel(
                folders = listOf(PipelineFolder("nyc", "nyc", 6), PipelineFolder("trade", "trade", 3)),
                foldersTruncated = false,
                pipelines = listOf(revenue),
                total = 1,
                hasMore = false,
            )

        val payload = PipelinesListTool(service, pipelines).call(McpArguments(mapOf("prefix" to "")), ctx) as Map<*, *>

        assertAll(
            { payload["prefix"] shouldBe "" },
            { (payload["folders"] as List<*>).map { (it as Map<*, *>)["path"] } shouldContainExactly listOf("nyc", "trade") },
            { (payload["folders"] as List<*>).map { (it as Map<*, *>)["pipeline_count"] } shouldContainExactly listOf(6, 3) },
            { (payload["pipelines"] as List<*>).map { (it as Map<*, *>)["name"] } shouldContainExactly listOf("monthly_revenue") },
            { payload["total"] shouldBe 1 },
            { payload["has_more"] shouldBe false },
        )
        // The flat listing is NOT consulted for a browse — one level per request, and browse
        // and search are different presentations.
        verify(exactly = 0) { pipelines.findAll(any(), any()) }
    }

    @Test
    fun `a non-empty prefix asks for exactly that level`() {
        every { pipelines.listFolder(McpFixtures.WORKSPACE_ID, "nyc/mobility", 0, 50) } returns
            PipelineFolderLevel(emptyList(), false, listOf(revenue), 1, false)

        PipelinesListTool(service, pipelines).call(McpArguments(mapOf("prefix" to "nyc/mobility")), ctx)

        verify(exactly = 1) { pipelines.listFolder(McpFixtures.WORKSPACE_ID, "nyc/mobility", 0, 50) }
    }

    @Test
    fun `a prefix that is not a legal name answers an empty level and never reaches the database`() {
        val payload = PipelinesListTool(service, pipelines).call(McpArguments(mapOf("prefix" to "nyc/../etc")), ctx) as Map<*, *>

        assertAll(
            { payload["prefix"] shouldBe "nyc/../etc" },
            { (payload["folders"] as List<*>).size shouldBe 0 },
            { (payload["pipelines"] as List<*>).size shouldBe 0 },
            { payload["total"] shouldBe 0 },
        )
        verify(exactly = 0) { pipelines.listFolder(any(), any(), any(), any()) }
    }

    @Test
    fun `q is unchanged by the browse argument`() {
        // The regression this guards: making `prefix` the only presentation would silently
        // break every existing caller that searches.
        every { pipelines.findAll(any(), null) } returns listOf(revenue, churn)

        val hits = PipelinesListTool(service, pipelines).call(McpArguments(mapOf("q" to "revenue")), ctx) as List<*>

        hits.map { (it as Map<*, *>)["name"] } shouldContainExactly listOf("monthly_revenue")
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

    /**
     * 078 A5-composition: a calculator `context_key` is an implicit optional execute input, so
     * the read surfaces list it under `parameters` — typed by the kind's output (`ANY` for an
     * ANY-output kind), `required: false`, marked `derived: true`. Declared parameters carry no
     * flag: absence is the false. Derived on read, never stored.
     */
    @Test
    fun `get lists calculator context keys under parameters as derived optional inputs`() {
        every { pipelines.findById(any(), McpFixtures.PIPELINE_ID) } returns revenue
        every { pipelines.findDraftDetail(any(), McpFixtures.PIPELINE_ID) } returns null
        every { pipelines.findVersionBody(any(), McpFixtures.PIPELINE_ID, 1) } returns CALCULATOR_BODY
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
        val parameters = McpTools.readTree(body.toString())["parameters"]

        val quarter = parameters["run_fiscal_quarter"]
        quarter["type"].asText() shouldBe "INTEGER"
        quarter["required"].asBoolean() shouldBe false
        quarter["derived"].asBoolean() shouldBe true
        parameters["anything"]["type"].asText() shouldBe "ANY"
        // The declared parameter keeps its own shape — no derived flag.
        parameters["region"]["type"].asText() shouldBe "STRING"
        parameters["region"].has("derived") shouldBe false
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

    private companion object {
        /** A body with one declared parameter and two calculator keys (typed + ANY-output). */
        val CALCULATOR_BODY =
            """
            {
              "schema_version": 1,
              "name": "monthly_revenue",
              "display_name": "Monthly Revenue",
              "description": "Revenue by customer.",
              "parameters": {"region": {"type": "STRING", "required": true}},
              "nodes": [
                {"id": "fq", "description": "fq", "type": "CALCULATOR", "kind": "fiscal_quarter",
                 "inputs": {"date": "${'$'}current_date", "fiscal_start": "01-01"},
                 "context_key": "run_fiscal_quarter", "depends_on": []},
                {"id": "cq", "description": "cq", "type": "CALCULATOR", "kind": "coalesce",
                 "inputs": {"values": ["a", "b"]}, "context_key": "anything", "depends_on": ["fq"]},
                {"id": "fetch", "description": "fetch", "type": "DQL", "source": "pg-prod",
                 "template": {"id": "test/revenue.sql", "version": 1}, "depends_on": ["cq"]}
              ]
            }
            """.trimIndent()
    }
}
