package co.datapipelines.mcp

import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.ValidationFailure
import co.datapipelines.pipeline.ValidationResult
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.ActionScope
import co.datapipelines.visualization.ArtifactFolder
import co.datapipelines.visualization.ArtifactJson
import co.datapipelines.visualization.ArtifactRecord
import co.datapipelines.visualization.ArtifactRef
import co.datapipelines.visualization.ArtifactValidation
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.ArtifactVersionDetail
import co.datapipelines.visualization.DashboardBody
import co.datapipelines.visualization.DashboardDocument
import co.datapipelines.visualization.DashboardErrorCodes
import co.datapipelines.visualization.DashboardReader
import co.datapipelines.visualization.DashboardRefreshHistory
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.PipelineReleaseFact
import co.datapipelines.visualization.PipelineReleaseFacts
import co.datapipelines.visualization.RefreshRecord
import co.datapipelines.visualization.RefreshStatus
import co.datapipelines.visualization.VisualizationBody
import co.datapipelines.visualization.VisualizationService
import com.fasterxml.jackson.core.type.TypeReference
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.time.Instant
import java.util.UUID

/**
 * The dashboard tools over mocked services and the REAL reader: `dashboards_get`'s dependency state is read through
 * the caller's lenses — a hidden pipeline is never asked about (the fact port is not even called) and a hidden or
 * absent visualization reads `status: null` — `last_refresh` is null until the runtime ships, `dashboards_validate`
 * answers the verdict, and the writes go through the reader and the new-root rule.
 */
class DashboardsToolsTest {
    private val dashboards = mockk<DashboardService>()
    private val visualizations = mockk<VisualizationService>()
    private val pipelines = mockk<PipelineReleaseFacts>()
    private val refreshes = mockk<DashboardRefreshHistory>()
    private val reader = DashboardReader()
    private val ctx = McpFixtures.ctx()
    private val workspaceId = McpFixtures.WORKSPACE.id
    private val id = UUID.randomUUID()
    private val at = Instant.parse("2026-09-29T00:00:00Z")

    @Test
    fun `dashboards_get carries each pin's status and each source's release - and last_refresh null`() {
        every { dashboards.findWorking(workspaceId, ReadLens.Everything, id) } returns loaded(PipelineVersionStatus.DRAFT)
        every { visualizations.findVersionByName(workspaceId, ReadLens.Everything, CHART, 3) } returns chart(PipelineVersionStatus.RELEASED)
        every { pipelines.releaseOf(workspaceId, ArtifactRef(PIPELINE, 7)) } returns
            PipelineReleaseFact(PipelineVersionStatus.RELEASED, readOnly = true, parameters = emptyList(), outputColumns = null)
        every { refreshes.latestOf(workspaceId, any(), McpFixtures.USER) } returns null

        val answer =
            DashboardsGetTool(
                dashboards,
                visualizations,
                pipelines,
                McpFixtures.EVERYTHING_LENS,
                refreshes,
            ).call(args(), ctx) as Map<*, *>
        val dependencies = answer["dependencies"] as Map<*, *>

        assertAll(
            {
                dependencies["visualizations"] shouldBe
                    listOf(mapOf("occurrence" to "revenue_chart", "name" to CHART, "version" to 3, "status" to "RELEASED"))
            },
            {
                dependencies["pipelines"] shouldBe
                    listOf(
                        mapOf(
                            "source" to "revenue_source",
                            "name" to PIPELINE,
                            "version" to 7,
                            "status" to "RELEASED",
                            "read_only" to true,
                        ),
                    )
            },
            { answer.containsKey("last_refresh") shouldBe true },
            { answer["last_refresh"] shouldBe null },
            { (answer["draft"] as Map<*, *>)["version"] shouldBe 1 },
        )
    }

    /**
     * `last_refresh` is the CALLER's own (#10 L2): the history is asked with the caller's user id and nothing else —
     * the mock is strict on that argument, so a lookup by any other id is an unstubbed call and fails here — and the
     * answer names the refresh, its version, status and stamps, never a selection or a source.
     */
    @Test
    fun `last_refresh is the callers own latest refresh - asked by their id and answered without a selection`() {
        every { dashboards.findWorking(workspaceId, ReadLens.Everything, id) } returns loaded(PipelineVersionStatus.RELEASED)
        every { visualizations.findVersionByName(workspaceId, ReadLens.Everything, CHART, 3) } returns chart(PipelineVersionStatus.RELEASED)
        every { pipelines.releaseOf(workspaceId, ArtifactRef(PIPELINE, 7)) } returns null
        val refreshId = UUID.randomUUID()
        every { refreshes.latestOf(workspaceId, id, McpFixtures.USER) } returns
            RefreshRecord(
                id = refreshId,
                dashboardId = id,
                dashboardVersion = 4,
                workspaceId = workspaceId,
                instanceId = UUID.randomUUID(),
                principalUserId = McpFixtures.USER,
                principalKeyId = null,
                scope = ActionScope.ALL,
                targetsJson = "[]",
                parameterRevision = 1,
                selectionsJson = """{"year": 424242}""",
                status = RefreshStatus.PARTIAL,
                startedAt = at,
                finishedAt = at.plusSeconds(3),
                summaryJson = "{}",
            )

        val answer =
            DashboardsGetTool(
                dashboards,
                visualizations,
                pipelines,
                McpFixtures.EVERYTHING_LENS,
                refreshes,
            ).call(args(), ctx) as Map<*, *>

        answer["last_refresh"] shouldBe
            mapOf(
                "refresh_id" to refreshId.toString(),
                "dashboard_version" to 4,
                "status" to "PARTIAL",
                "started_at" to at.toString(),
                "finished_at" to at.plusSeconds(3).toString(),
            )
        answer.toString().contains("424242") shouldBe false // no selection leaks into the tool result
    }

    @Test
    fun `under a promoter's lens a hidden source is never asked about and a hidden pin reads null - no draft pointer`() {
        val boards = ReadLens.Only(setOf(NAME))
        every { dashboards.findWorking(workspaceId, boards, id) } returns loaded(PipelineVersionStatus.RELEASED)
        every { visualizations.findVersionByName(workspaceId, ReadLens.NOTHING, CHART, 3) } returns null
        val promoter =
            PromoterLens { LensedView(ReadLens.NOTHING, ReadLens.NOTHING, visualizations = ReadLens.NOTHING, dashboards = boards) }

        every { refreshes.latestOf(workspaceId, any(), McpFixtures.USER) } returns null

        val answer = DashboardsGetTool(dashboards, visualizations, pipelines, promoter, refreshes).call(args(), ctx) as Map<*, *>
        val dependencies = answer["dependencies"] as Map<*, *>

        assertAll(
            { ((dependencies["visualizations"] as List<*>).single() as Map<*, *>)["status"] shouldBe null },
            { ((dependencies["pipelines"] as List<*>).single() as Map<*, *>)["status"] shouldBe null },
            { answer["draft"] shouldBe null },
        )
        verify(exactly = 0) { pipelines.releaseOf(any(), any()) }
    }

    @Test
    fun `dashboards_validate answers the verdict on the working version - valid, and every failure`() {
        val working = loaded(PipelineVersionStatus.DRAFT)
        every { dashboards.findWorking(workspaceId, ReadLens.Everything, id) } returns working
        val failure = ValidationFailure(DashboardErrorCodes.INPUT_UNBOUND, "visualizations[0].inputs.revenue", "unbound", emptyMap())
        every { dashboards.validate(workspaceId, DashboardDocument(NAME, working.body)) } returns
            ArtifactValidation.Invalid(ValidationResult(listOf(failure)))
        val tool = DashboardsValidateTool(dashboards, McpFixtures.EVERYTHING_LENS)

        val refused = tool.call(args(), ctx) as Map<*, *>
        refused["valid"] shouldBe false
        (refused["failures"] as List<*>).single() shouldBe
            mapOf(
                "code" to DashboardErrorCodes.INPUT_UNBOUND,
                "path" to "visualizations[0].inputs.revenue",
                "message" to "unbound",
                "details" to emptyMap<String, Any>(),
            )

        every { dashboards.validate(workspaceId, any()) } returns ArtifactValidation.Valid(DashboardDocument(NAME, working.body))
        (tool.call(args(), ctx) as Map<*, *>)["valid"] shouldBe true

        every { dashboards.findWorking(workspaceId, ReadLens.Everything, id) } returns null
        shouldThrow<DatapipelinesException> { tool.call(args(), ctx) }.code shouldBe DashboardErrorCodes.NOT_FOUND
    }

    @Test
    fun `dashboards_create and dashboards_update go through the reader - the new-root rule on create`() {
        every { dashboards.listChildFolders(workspaceId, ReadLens.Everything, null) } returns
            listOf(ArtifactFolder("finance", "finance", 1))
        every { dashboards.create(workspaceId, any(), McpFixtures.USER, WriteSurface.MCP) } returns loaded(PipelineVersionStatus.DRAFT)
        every { dashboards.write(workspaceId, id, any(), "hash-0", McpFixtures.USER, WriteSurface.MCP) } returns
            loaded(PipelineVersionStatus.DRAFT)

        (
            DashboardsCreateTool(
                dashboards,
                reader,
                McpFixtures.EVERYTHING_LENS,
            ).call(McpArguments(documentArgs()), ctx) as Map<*, *>
        )["id"] shouldBe
            id.toString()
        shouldThrow<DatapipelinesException> {
            DashboardsCreateTool(dashboards, reader, McpFixtures.EVERYTHING_LENS).call(
                McpArguments(documentArgs() + ("name" to "ops/boards/x")),
                ctx,
            )
        }.code shouldBe DashboardErrorCodes.NEW_ROOT_REQUIRES_CONFIRMATION
        val updated =
            DashboardsUpdateTool(dashboards, reader)
                .call(McpArguments(documentArgs() + mapOf("id" to id.toString(), "expected_hash" to "hash-0")), ctx) as Map<*, *>
        updated["version"] shouldBe 1
    }

    @Test
    fun `dashboards_list and dashboards_purge_draft - the lensed reads first`() {
        every { dashboards.listChildFolders(workspaceId, ReadLens.Everything, null) } returns emptyList()
        every { dashboards.listChildren(workspaceId, ReadLens.Everything, null, 0, 50) } returns
            listOf(loaded(PipelineVersionStatus.RELEASED))
        val listed = DashboardsListTool(dashboards, McpFixtures.EVERYTHING_LENS).call(McpArguments(emptyMap()), ctx) as Map<*, *>
        ((listed["dashboards"] as List<*>).single() as Map<*, *>)["name"] shouldBe NAME

        every { dashboards.findWorking(workspaceId, ReadLens.Everything, id) } returns loaded(PipelineVersionStatus.DRAFT)
        every { dashboards.purgeDraft(workspaceId, id, "hash-1") } returns co.datapipelines.visualization.Purged.Version
        DashboardsPurgeDraftTool(dashboards, McpFixtures.EVERYTHING_LENS)
            .call(McpArguments(mapOf("id" to id.toString(), "expected_hash" to "hash-1")), ctx) shouldBe
            mapOf("id" to id.toString(), "purged" to true)
    }

    @Test
    fun `the tools list in section 6-1's order`() {
        dashboardTools(
            dashboards,
            visualizations,
            pipelines,
            reader,
            McpFixtures.EVERYTHING_LENS,
            refreshes,
        ).map { it.name } shouldContainExactly
            listOf(
                "dashboards_list",
                "dashboards_get",
                "dashboards_create",
                "dashboards_update",
                "dashboards_purge_draft",
                "dashboards_validate",
            )
    }

    // ---- fixtures -------------------------------------------------------------------------------------

    private fun args() = McpArguments(mapOf("id" to id.toString()))

    private fun documentArgs(): Map<String, Any?> = ArtifactJson.mapper.readValue(DOCUMENT, object : TypeReference<Map<String, Any?>>() {})

    private fun loaded(status: PipelineVersionStatus): ArtifactVersion<DashboardBody> =
        ArtifactVersion(
            ArtifactRecord(
                id,
                workspaceId,
                NAME,
                "Board",
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

    private fun chart(status: PipelineVersionStatus): ArtifactVersion<VisualizationBody> =
        ArtifactVersion(
            ArtifactRecord(UUID.randomUUID(), workspaceId, CHART, "Chart", "", 3, at, at, McpFixtures.USER),
            ArtifactVersionDetail(id, 3, status, "hash-3", at, McpFixtures.USER),
            mockk(),
        )

    private companion object {
        const val NAME = "finance/boards/revenue"
        const val CHART = "finance/charts/revenue"
        const val PIPELINE = "finance/pipelines/monthly_revenue"
        val DOCUMENT =
            """
            {"name": "$NAME", "display_name": "Board",
             "sources": [{"name": "revenue_source", "pipeline": {"name": "$PIPELINE", "version": 7}}],
             "visualizations": [{"name": "revenue_chart", "type": "visualization", "visualization": {"name": "$CHART", "version": 3},
                                 "inputs": {"revenue": {"source": "revenue_source"}}}],
             "layout": {"grid": [{"name": "revenue_chart", "x": 0, "y": 0, "w": 6, "h": 4}]}}
            """.trimIndent()
    }
}
