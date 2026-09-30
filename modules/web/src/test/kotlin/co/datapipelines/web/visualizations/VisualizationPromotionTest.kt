package co.datapipelines.web.visualizations

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.ArtifactExport
import co.datapipelines.visualization.ArtifactJson
import co.datapipelines.visualization.ArtifactRecord
import co.datapipelines.visualization.ArtifactTransferService
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.ArtifactVersionDetail
import co.datapipelines.visualization.DashboardErrorCodes
import co.datapipelines.visualization.DashboardReader
import co.datapipelines.visualization.DashboardRepository
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.VisualizationBody
import co.datapipelines.visualization.VisualizationErrorCodes
import co.datapipelines.visualization.VisualizationReader
import co.datapipelines.visualization.VisualizationRepository
import co.datapipelines.visualization.VisualizationService
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.pipelines.PromotableView
import co.datapipelines.web.pipelines.PromotionWire
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.time.Instant
import java.util.UUID

/**
 * The transfer families' promotion half (the `ParameterSetPromotionTest` mould): a REAL batch entry binds through
 * the REAL transfer entry bind — the family READER, so the document bounds hold on receive as on save — a shape
 * refusal lands before any port is touched, `land` hands the bound export to the service's `import` (whose
 * `validateForImport` judges the just-landed rows inside the transaction), and the sender's entry rules are the
 * §10.3 rule plus the view's admission (refuse, never drop).
 */
class VisualizationPromotionTest {
    private val repository = mockk<VisualizationRepository>()
    private val dashboardRepository = mockk<DashboardRepository>()
    private val visualizations = mockk<VisualizationService>()
    private val dashboards = mockk<DashboardService>()
    private val bundle = mockk<co.datapipelines.visualization.TemplateBundle>()
    private val transfer = ArtifactTransferService(visualizations, dashboards, bundle, VisualizationReader(), DashboardReader())

    private val promotion = VisualizationPromotion(repository, visualizations, transfer)
    private val dashboardPromotion = DashboardPromotion(dashboardRepository, dashboards, transfer)

    private val workspaceId = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val artifactId = UUID.randomUUID()
    private val now = Instant.parse("2026-09-29T00:00:00Z")

    @Test
    fun `a real batch entry binds - the reader binds the body and the lifecycle fields ride`() {
        val bound = promotion.bind(entry())

        assertAll(
            { bound.id shouldBe artifactId },
            { bound.name shouldBe NAME },
            { bound.version shouldBe 1 },
            { bound.releasedAt shouldBe now },
            { bound.body.displayName shouldBe "Monthly revenue" },
            { verify { repository wasNot Called } },
        )
    }

    @Test
    fun `an over-bound entry refuses at the reader - the bounds hold on receive as on save`() {
        val over = entry()
        val fixtures = over.at("/tests/cases/0/fixtures/revenue") as ArrayNode
        repeat(1_001) { fixtures.addObject().put("month", "2026-01-01").put("amount", "10.5") }

        val refusal = shouldThrow<DatapipelinesException> { promotion.bind(over) }

        assertAll(
            { refusal.code shouldBe VisualizationErrorCodes.BODY_INVALID },
            { refusal.details.containsKey("failures") shouldBe true },
            { verify { visualizations wasNot Called } },
        )
    }

    @Test
    fun `a present-but-non-integer version refuses - never the version-less path`() {
        val malformed = entry().put("version", "one")

        val refusal = shouldThrow<DatapipelinesException> { promotion.bind(malformed) }

        assertAll(
            { refusal.code shouldBe VisualizationErrorCodes.BODY_INVALID },
            { refusal.details["path"] shouldBe "version" },
            { refusal.details["reason"] shouldBe "wrong_type" },
        )
    }

    @Test
    fun `land hands the bound export to the service import - the just-landed rows are what validateForImport sees`() {
        val bound = promotion.bind(entry())
        val captured = slot<ArtifactExport<VisualizationBody>>()
        every { visualizations.import(workspaceId, capture(captured), actor) } returns mockk()

        promotion.land(bound, workspaceId, actor)

        assertAll(
            { captured.captured.id shouldBe artifactId },
            { captured.captured.name shouldBe NAME },
            { captured.captured.version shouldBe 1 },
            { captured.captured.bodyHash shouldBe bound.bodyHash },
        )
    }

    @Test
    fun `entryFor answers the current release only when it is RELEASED, newer than the target, and the view admits it`() {
        val lens = mockk<co.datapipelines.pipeline.ReadLens>()
        val view = mockk<PromotableView>()
        every { view.visualizationsLens } returns lens
        every { repository.findRecordByName(workspaceId, NAME) } returns record(currentVersion = 1)
        every { repository.findVersionDetail(workspaceId, artifactId, 1) } returns detail(1, PipelineVersionStatus.RELEASED)
        every { repository.findVersion(workspaceId, artifactId, 1) } returns releasedVersion()

        // The view admits and the target holds nothing: promotable.
        every { lens.admits(NAME) } returns true
        promotion.entryFor(workspaceId, NAME, null, view)?.get("id")?.asText() shouldBe artifactId.toString()

        // The view hides it: refused (null — the caller throws the family's 404), nothing read further.
        every { lens.admits(NAME) } returns false
        promotion.entryFor(workspaceId, NAME, null, view) shouldBe null
    }

    @Test
    fun `entryForPin skips an entry the target holds at the same version and hash - the dependency skip rule`() {
        val detail = detail(2, PipelineVersionStatus.RELEASED)
        every { repository.findRecordByName(workspaceId, NAME) } returns record(currentVersion = 2)
        every { repository.findVersionDetail(workspaceId, artifactId, 2) } returns detail

        assertAll(
            // Same version + same hash: OMITTED (the §10.4 idempotent re-push), and the version body is not read.
            { promotion.entryForPin(workspaceId, NAME, 2, PromotionWire.Entry(NAME, 2, detail.bodyHash)) shouldBe null },
            // Same version, different hash: SENT — the landing refuses it if it truly conflicts (§9.2).
            {
                every { repository.findVersion(workspaceId, artifactId, 2) } returns releasedVersion()
                val sent = promotion.entryForPin(workspaceId, NAME, 2, PromotionWire.Entry(NAME, 2, "different"))
                (sent != null) shouldBe true
            },
        )
    }

    @Test
    fun `a dashboard entry that is not an object refuses the family's body_invalid - never a 500`() {
        val refusal = shouldThrow<ApiException> { dashboardPromotion.bind(ArtifactJson.mapper.valueToTree(listOf("x"))) }

        assertAll(
            { refusal.code shouldBe DashboardErrorCodes.BODY_INVALID },
            { refusal.details["reason"] shouldBe "wrong_type" },
            { verify { dashboards wasNot Called } },
        )
    }

    private fun releasedVersion(): ArtifactVersion<VisualizationBody> {
        val document = ArtifactJson.mapper.readTree(DOCUMENT) as ObjectNode
        document.remove("name")
        val body = VisualizationReader().readOrThrow(document.put("name", NAME)).body
        return ArtifactVersion(record(currentVersion = 1), detail(1, PipelineVersionStatus.RELEASED), body)
    }

    private fun entry(): ObjectNode {
        val copy = ArtifactJson.mapper.readTree(DOCUMENT).deepCopy() as ObjectNode
        return copy
            .put("id", artifactId.toString())
            .put("version", 1)
            .put("body_hash", "hash-v1")
            .put("released_at", now.toString())
    }

    private fun record(currentVersion: Int?): ArtifactRecord =
        ArtifactRecord(artifactId, workspaceId, NAME, "Monthly revenue", "", currentVersion, now, now, actor)

    private fun detail(
        version: Int,
        status: PipelineVersionStatus,
    ): ArtifactVersionDetail = ArtifactVersionDetail(artifactId, version, status, "hash-v$version", now, actor, now)

    private companion object {
        const val NAME = "finance/visualizations/monthly_revenue"

        /** The implementation spec's §3.1 worked document — the reader binds it unchanged. */
        val DOCUMENT =
            """
            {"name": "$NAME", "display_name": "Monthly revenue", "description": "",
             "renderer": {"kind": "plotly", "version": "4"},
             "inputs": {"revenue": {"columns": [{"name": "month", "type": "DATE", "nullable": false},
                                                {"name": "amount", "type": "DECIMAL", "nullable": false}]}},
             "transform": {"template": {"name": "finance/transforms/revenue_bars", "version": 2}, "inputs": {"rows": "revenue"}},
             "config": {"data": [{"type": "bar", "x": "${'$'}.x", "y": "${'$'}.y"}], "layout": {"title": {"text": "Revenue"}}},
             "bindings": {"data[0].x": "month_labels", "data[0].y": "amounts"},
             "presentation": {"title": "Monthly revenue", "tokens": {"series": "categorical"}},
             "tests": {"cases": [{"name": "twelve months", "fixtures": {"revenue": [{"month": "2026-01-01", "amount": 10.5}]},
                                  "assertions": [{"kind": "rendered"}, {"kind": "trace_count", "equals": 1}]}]}}
            """.trimIndent()
    }
}
