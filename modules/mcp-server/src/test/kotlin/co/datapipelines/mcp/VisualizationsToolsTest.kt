package co.datapipelines.mcp

import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.ArtifactFolder
import co.datapipelines.visualization.ArtifactJson
import co.datapipelines.visualization.ArtifactRecord
import co.datapipelines.visualization.ArtifactValidationException
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.ArtifactVersionDetail
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.VisualizationBody
import co.datapipelines.visualization.VisualizationDocument
import co.datapipelines.visualization.VisualizationErrorCodes
import co.datapipelines.visualization.VisualizationReader
import co.datapipelines.visualization.VisualizationService
import com.fasterxml.jackson.core.type.TypeReference
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.time.Instant
import java.util.UUID

/**
 * The visualization tools over mocked services and the REAL reader: the document assembled from the arguments is
 * the reader's (an unknown key or a bound refuses before the service), the 094 new-root rule reads the caller's
 * view of the roots, every read passes the caller's lenses — `used_by` through the DASHBOARD lens — and a promoter
 * never sees a draft pointer. The purge checks existence through the lensed read first.
 */
class VisualizationsToolsTest {
    private val visualizations = mockk<VisualizationService>()
    private val dashboards = mockk<DashboardService>()
    private val reader = VisualizationReader()
    private val ctx = McpFixtures.ctx()
    private val workspaceId = McpFixtures.WORKSPACE.id
    private val id = UUID.randomUUID()
    private val at = Instant.parse("2026-09-29T00:00:00Z")

    @Test
    fun `visualizations_list answers a level - folders, rows and each row's used_by through the dashboard lens`() {
        every { visualizations.listChildFolders(workspaceId, ReadLens.Everything, "acme") } returns
            listOf(ArtifactFolder("acme/charts", "charts", 2))
        every { visualizations.listChildren(workspaceId, ReadLens.Everything, "acme", 0, 50) } returns
            listOf(loaded(PipelineVersionStatus.RELEASED))
        // #331 — used_by for the PAGE in one batched call: the per-row reads are gone, the tool loop
        // only reads the answer's map. Red if anyone restores the per-row `pinnedBy` call.
        every { dashboards.pinnedByAll(workspaceId, ReadLens.Everything, listOf(NAME)) } returns
            mapOf(NAME to listOf("acme/boards/revenue@2"))

        val answer =
            VisualizationsListTool(visualizations, dashboards, McpFixtures.EVERYTHING_LENS).call(
                McpArguments(
                    mapOf("prefix" to "acme"),
                ),
                ctx,
            ) as Map<*, *>

        assertAll(
            { answer["prefix"] shouldBe "acme" },
            { answer["returned"] shouldBe 2 },
            {
                (answer["folders"] as List<*>).single() shouldBe
                    mapOf("path" to "acme/charts", "segment" to "charts", "visualization_count" to 2)
            },
            { ((answer["visualizations"] as List<*>).single() as Map<*, *>)["used_by"] shouldBe listOf("acme/boards/revenue@2") },
            { verify(exactly = 1) { dashboards.pinnedByAll(workspaceId, any(), any<Collection<String>>()) } },
            { verify(exactly = 0) { dashboards.pinnedBy(any(), any(), any()) } },
        )
    }

    @Test
    fun `visualizations_get - the whole view carries the draft pointer, a promoter's lens never does`() {
        every { visualizations.findWorking(workspaceId, ReadLens.Everything, id) } returns loaded(PipelineVersionStatus.DRAFT)
        every { dashboards.pinnedBy(workspaceId, ReadLens.Everything, NAME) } returns emptyList()
        val whole = VisualizationsGetTool(visualizations, dashboards, McpFixtures.EVERYTHING_LENS).call(args(), ctx) as Map<*, *>
        (whole["draft"] as Map<*, *>)["body_hash"] shouldBe "hash-1"
        (whole["document"] as com.fasterxml.jackson.databind.JsonNode).get("renderer").get("kind").asText() shouldBe "plotly"

        val lensed = ReadLens.Only(setOf(NAME))
        val boards = ReadLens.Only(setOf("acme/boards/revenue"))
        every { visualizations.findWorking(workspaceId, lensed, id) } returns loaded(PipelineVersionStatus.RELEASED)
        every { dashboards.pinnedBy(workspaceId, boards, NAME) } returns listOf("acme/boards/revenue@1")
        val promoter = PromoterLens { LensedView(ReadLens.NOTHING, ReadLens.NOTHING, visualizations = lensed, dashboards = boards) }
        val narrowed = VisualizationsGetTool(visualizations, dashboards, promoter).call(args(), ctx) as Map<*, *>

        narrowed["draft"] shouldBe null
        narrowed["used_by"] shouldBe listOf("acme/boards/revenue@1")
    }

    @Test
    fun `visualizations_get - absent or hidden is the family's not_found`() {
        every { visualizations.findWorking(workspaceId, ReadLens.Everything, id) } returns null

        val refused =
            shouldThrow<DatapipelinesException> {
                VisualizationsGetTool(
                    visualizations,
                    dashboards,
                    McpFixtures.EVERYTHING_LENS,
                ).call(args(), ctx)
            }

        refused.code shouldBe VisualizationErrorCodes.NOT_FOUND
    }

    @Test
    fun `visualizations_create - the arguments ARE the document, read by the reader, and a new root is refused unconfirmed`() {
        every { visualizations.listChildFolders(workspaceId, ReadLens.Everything, null) } returns listOf(ArtifactFolder("acme", "acme", 1))
        val document = slot<VisualizationDocument>()
        every { visualizations.create(workspaceId, capture(document), McpFixtures.USER, WriteSurface.MCP) } returns
            loaded(PipelineVersionStatus.DRAFT)
        val tool = VisualizationsCreateTool(visualizations, reader, McpFixtures.EVERYTHING_LENS)

        val answer = tool.call(McpArguments(documentArgs()), ctx) as Map<*, *>
        answer shouldBe mapOf("id" to id.toString(), "name" to NAME, "version" to 1, "status" to "DRAFT", "body_hash" to "hash-1")
        document.captured.name shouldBe NAME
        document.captured.body.renderer.version shouldBe "4"

        val newRoot =
            shouldThrow<DatapipelinesException> { tool.call(McpArguments(documentArgs() + ("name" to "finance/charts/revenue")), ctx) }
        newRoot.code shouldBe VisualizationErrorCodes.NEW_ROOT_REQUIRES_CONFIRMATION
        newRoot.details["existing_roots"] shouldBe listOf("acme")
    }

    @Test
    fun `visualizations_create - the reader's refusal comes before the service`() {
        every { visualizations.listChildFolders(workspaceId, ReadLens.Everything, null) } returns listOf(ArtifactFolder("acme", "acme", 1))
        // A key the reader's nested table does not know (the schema's additionalProperties guards the top level).
        val wrong = documentArgs() + ("renderer" to mapOf("kind" to "plotly", "version" to "4", "theme" to "dark"))

        val refused =
            shouldThrow<ArtifactValidationException> {
                VisualizationsCreateTool(visualizations, reader, McpFixtures.EVERYTHING_LENS).call(McpArguments(wrong), ctx)
            }
        refused.code shouldBe VisualizationErrorCodes.BODY_INVALID
        refused.result.failures
            .single()
            .path shouldBe "renderer.theme"
        verify(exactly = 0) { visualizations.create(any(), any(), any(), any()) }
    }

    @Test
    fun `visualizations_update writes at expected_hash, and the tool-only arguments never reach the reader`() {
        every { visualizations.write(workspaceId, id, any(), "hash-0", McpFixtures.USER, WriteSurface.MCP) } returns
            loaded(PipelineVersionStatus.DRAFT)

        val answer =
            VisualizationsUpdateTool(visualizations, reader)
                .call(McpArguments(documentArgs() + mapOf("id" to id.toString(), "expected_hash" to "hash-0")), ctx) as Map<*, *>

        answer["body_hash"] shouldBe "hash-1"
    }

    @Test
    fun `visualizations_purge_draft checks existence through the lensed read, then purges at the hash`() {
        every { visualizations.findWorking(workspaceId, ReadLens.Everything, id) } returns loaded(PipelineVersionStatus.DRAFT)
        every { visualizations.purgeDraft(workspaceId, id, "hash-1") } returns co.datapipelines.visualization.Purged.Version

        VisualizationsPurgeDraftTool(visualizations, McpFixtures.EVERYTHING_LENS)
            .call(McpArguments(mapOf("id" to id.toString(), "expected_hash" to "hash-1")), ctx) shouldBe
            mapOf("id" to id.toString(), "purged" to true)

        every { visualizations.findWorking(workspaceId, ReadLens.Everything, id) } returns null
        shouldThrow<DatapipelinesException> {
            VisualizationsPurgeDraftTool(visualizations, McpFixtures.EVERYTHING_LENS)
                .call(McpArguments(mapOf("id" to id.toString(), "expected_hash" to "hash-1")), ctx)
        }.code shouldBe VisualizationErrorCodes.NOT_FOUND
        verify(exactly = 1) { visualizations.purgeDraft(any(), any(), any()) }
    }

    @Test
    fun `the tools list in section 6-1's order, each with its own name`() {
        visualizationTools(visualizations, dashboards, reader, McpFixtures.EVERYTHING_LENS).map { it.name } shouldContainExactly
            listOf(
                "visualizations_list",
                "visualizations_get",
                "visualizations_create",
                "visualizations_update",
                "visualizations_purge_draft",
            )
    }

    // ---- fixtures -------------------------------------------------------------------------------------

    private fun args() = McpArguments(mapOf("id" to id.toString()))

    private fun documentArgs(): Map<String, Any?> = ArtifactJson.mapper.readValue(DOCUMENT, object : TypeReference<Map<String, Any?>>() {})

    private fun loaded(status: PipelineVersionStatus): ArtifactVersion<VisualizationBody> =
        ArtifactVersion(
            ArtifactRecord(
                id,
                workspaceId,
                NAME,
                "Revenue",
                "",
                if (status ==
                    PipelineVersionStatus.DRAFT
                ) {
                    null
                } else {
                    1
                },
                at,
                at,
                McpFixtures.USER,
            ),
            ArtifactVersionDetail(id, 1, status, "hash-1", at, McpFixtures.USER),
            reader.readOrThrow(ArtifactJson.mapper.readTree(DOCUMENT)).body,
        )

    private companion object {
        const val NAME = "acme/charts/revenue"
        val DOCUMENT =
            """
            {"name": "$NAME", "display_name": "Revenue", "description": "",
             "renderer": {"kind": "plotly", "version": "4"},
             "inputs": {"revenue": {"columns": [{"name": "amount", "type": "DECIMAL", "nullable": false}]}},
             "config": {"data": [{"type": "bar", "y": []}]},
             "bindings": {"data[0].y": "amount"}}
            """.trimIndent()
    }
}
