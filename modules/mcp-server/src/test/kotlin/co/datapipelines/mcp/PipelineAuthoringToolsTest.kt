package co.datapipelines.mcp

import co.datapipelines.auth.Scope
import co.datapipelines.pipeline.NewPipeline
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineDeserializer
import co.datapipelines.pipeline.PipelineDraftService
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineSerializer
import co.datapipelines.pipeline.PipelineValidationException
import co.datapipelines.pipeline.PipelineValidator
import co.datapipelines.pipeline.PipelineVersionDetail
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ValidationFailure
import co.datapipelines.pipeline.ValidationResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.util.UUID

class PipelineAuthoringToolsTest {
    private val pipelines = mockk<PipelineRepository>()
    private val validator = mockk<PipelineValidator>()
    private val ctx = McpFixtures.ctx(Scope.AUTHOR)

    private val args =
        McpArguments(
            mapOf(
                "name" to "monthly_revenue",
                "display_name" to "Monthly Revenue",
                "description" to "Revenue by customer.",
                "parameters" to emptyMap<String, Any?>(),
                "nodes" to
                    listOf(
                        mapOf(
                            "id" to "fetch",
                            "type" to "DQL",
                            "source" to "pg-prod",
                            "template" to mapOf("id" to "revenue.sql", "version" to 1),
                            "depends_on" to emptyList<String>(),
                        ),
                    ),
            ),
        )

    private val drafts = mockk<PipelineDraftService>()

    private fun createTool() = PipelinesCreateTool(pipelines, PipelineDeserializer(), validator, PipelineSerializer())

    private fun updateTool() = PipelinesUpdateTool(pipelines, drafts, PipelineDeserializer(), validator, PipelineSerializer())

    /** The §7 draft answer shape an update returns since the lifecycle round. */
    private fun draftDetail(version: Int = 2) =
        PipelineVersionDetail(
            pipelineId = McpFixtures.PIPELINE_ID,
            version = version,
            status = PipelineVersionStatus.DRAFT,
            bodyHash = "hash-v$version",
            createdAt = java.time.Instant.EPOCH,
            createdBy = McpFixtures.USER,
            updatedBy = McpFixtures.USER,
            updatedAt = java.time.Instant.EPOCH,
        )

    @Test
    fun `create validates before storing and returns id, version and body`() {
        every { validator.validateOrThrow(any(), any()) } answers { firstArg() }
        val stored = slot<String>()
        val row = slot<NewPipeline>()
        every { pipelines.create(any(), capture(row), capture(stored), McpFixtures.USER) } returns McpFixtures.pipelineRecord()
        every { pipelines.findCurrentVersionDetail(any(), McpFixtures.PIPELINE_ID) } returns
            draftDetail(version = 1).copy(status = PipelineVersionStatus.RELEASED)

        @Suppress("UNCHECKED_CAST")
        val payload = createTool().call(args, ctx) as Map<String, Any?>

        assertAll(
            { payload["id"] shouldBe McpFixtures.PIPELINE_ID.toString() },
            { payload["version"] shouldBe 1 },
            { payload["status"] shouldBe "RELEASED" },
            { payload["body_hash"] shouldBe "hash-v1" },
            { payload["owner_id"] shouldBe McpFixtures.USER.toString() },
            { row.captured.ownerId shouldBe McpFixtures.USER },
            // The stored body is the canonical serialization, with the server-set schema_version.
            { stored.captured shouldContain "\"schema_version\":1" },
            { stored.captured shouldContain "\"name\":\"monthly_revenue\"" },
            { McpTools.readTree(payload["body"].toString())["nodes"].size() shouldBe 1 },
        )
    }

    @Test
    fun `a validation failure is raised with its catalogued code and never reaches the repository`() {
        every { validator.validateOrThrow(any(), any()) } throws
            PipelineValidationException(
                ValidationResult(
                    listOf(
                        ValidationFailure(
                            code = PipelineErrorCodes.Validation.UNKNOWN_DATASOURCE,
                            path = "nodes[0].source",
                            message = "Datasource 'pg-prod' is not registered.",
                        ),
                    ),
                ),
            )

        val error = shouldThrow<PipelineValidationException> { createTool().call(args, ctx) }

        error.code shouldBe PipelineErrorCodes.Validation.UNKNOWN_DATASOURCE
    }

    @Test
    fun `a body that is not even parseable is rejected before validation runs`() {
        val broken = McpArguments(args.rawMap() + mapOf("nodes" to listOf(mapOf("id" to "fetch", "type" to "SELECT"))))

        shouldThrow<PipelineValidationException> { createTool().call(broken, ctx) }
    }

    @Test
    fun `update writes the DRAFT and answers with its status, hash and pointer`() {
        every { validator.validateOrThrow(any(), any()) } answers { firstArg() }
        every {
            drafts.write(any(), McpFixtures.PIPELINE_ID, any<Pipeline>(), any(), "hash-v1", McpFixtures.USER)
        } returns PipelineDraftService.DraftWrite(McpFixtures.pipelineRecord(version = 1), draftDetail(version = 2), "body")

        @Suppress("UNCHECKED_CAST")
        val payload =
            updateTool().call(
                McpArguments(args.rawMap() + mapOf("id" to McpFixtures.PIPELINE_ID.toString(), "expected_hash" to "hash-v1")),
                ctx,
            ) as Map<String, Any?>

        assertAll(
            { payload["version"] shouldBe 2 },
            { payload["status"] shouldBe "DRAFT" },
            { payload["body_hash"] shouldBe "hash-v2" },
            // The agent must SEE that its work is a draft and carry this hash forward (§12).
            {
                @Suppress("UNCHECKED_CAST")
                (payload["draft"] as Map<String, Any?>)["version"] shouldBe 2
            },
            { payload["current_version"] shouldBe 1 },
        )
    }

    @Test
    fun `update without expected_hash is refused by the input schema`() {
        // additionalProperties=false and required=["id","expected_hash",…] — the argument
        // reader throws for a missing required key before any repository call.
        shouldThrow<Throwable> {
            updateTool().call(McpArguments(args.rawMap() + mapOf("id" to McpFixtures.PIPELINE_ID.toString())), ctx)
        }
    }

    @Test
    fun `update of an unknown pipeline is a catalogued not-found`() {
        val id = UUID.randomUUID()
        every { validator.validateOrThrow(any(), any()) } answers { firstArg() }
        every { drafts.write(any(), id, any<Pipeline>(), any(), any(), McpFixtures.USER) } throws
            co.datapipelines.typesystem.DatapipelinesException(
                PipelineErrorCodes.Execution.NOT_FOUND,
                "Pipeline '$id' not found.",
                emptyMap(),
            )

        val error =
            shouldThrow<co.datapipelines.typesystem.DatapipelinesException> {
                updateTool().call(
                    McpArguments(args.rawMap() + mapOf("id" to id.toString(), "expected_hash" to "h")),
                    ctx,
                )
            }
        error.code shouldBe PipelineErrorCodes.Execution.NOT_FOUND
    }
}
