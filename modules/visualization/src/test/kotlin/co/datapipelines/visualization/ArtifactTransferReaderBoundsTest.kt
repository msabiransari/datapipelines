package co.datapipelines.visualization

import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.VisualizationTestDb.AUTHOR
import co.datapipelines.visualization.VisualizationTestDb.OTHER_WORKSPACE
import co.datapipelines.visualization.VisualizationTestDb.WORKSPACE
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/** Records every template import; nothing else. */
private class RecordingTransferBundle : TemplateBundle {
    val imported = mutableListOf<List<JsonNode>>()

    override fun export(
        workspaceId: UUID,
        pins: List<TemplateRef>,
    ): List<JsonNode> = emptyList()

    override fun import(
        workspaceId: UUID,
        templates: List<JsonNode>,
        actor: UUID,
    ) {
        imported += templates
    }
}

/**
 * The HIGH item of the L1c brief (#10 L1c): the transfer's import and the promotion receive bind through
 * [ArtifactTransferService]'s entry bind, which runs the family READER — the seven `datapipelines.visualization.*`
 * bounds and the two dashboard bounds hold on import and receive exactly as on save. Before the reader bind, the
 * strip-then-mapper path skipped every reader bound, so an envelope over `max-fixture-rows-per-case` (whose
 * rows × columns cost the security pass bounded at save) imported whole — the F1 class reopened for import.
 *
 * Both cases here are the falsified-at-birth guard: red with the reader bind reverted to the bare mapper
 * (the over-bound payload is ACCEPTED and lands), green bound through the readers.
 */
class ArtifactTransferReaderBoundsTest {
    private lateinit var h: LifecycleHarness
    private lateinit var bundle: RecordingTransferBundle
    private lateinit var transfer: ArtifactTransferService

    @BeforeEach
    fun reset() {
        VisualizationTestDb.reset()
        h = LifecycleHarness()
        bundle = RecordingTransferBundle()
        transfer = ArtifactTransferService(h.visualizations, h.dashboards, bundle, VisualizationReader(), DashboardReader())
    }

    @Test
    fun `an envelope over max-fixture-rows-per-case refuses at the reader - nothing lands`() {
        val created = h.createVisualization()
        val released = h.visualizations.release(WORKSPACE, created.record.id, created.detail.bodyHash, AUTHOR).version
        val envelope = transfer.exportVisualization(WORKSPACE, released.record.id)
        val payload = envelope.get("visualization") as ObjectNode
        val fixtures = payload.at("/tests/cases/0/fixtures/revenue") as ArrayNode
        repeat(ROWS_OVER_BOUND) { fixtures.addObject().put("month", "2026-01-01").put("amount", "10.5") }
        restampHash(payload)

        // The same document is refused at SAVE — the bound the import must hold too (non-vacuity on the bound).
        shouldThrow<ArtifactValidationException> { VisualizationReader().readOrThrow(payload.deepCopy() as ObjectNode) }

        val refusal =
            shouldThrow<DatapipelinesException> { transfer.importVisualization(OTHER_WORKSPACE, envelope, AUTHOR) }

        withClue("the refusal must be the family's body_invalid: ${refusal.details}") {
            refusal.code shouldBe VisualizationErrorCodes.BODY_INVALID
        }
        withClue("nothing may land before the bound refuses") {
            bundle.imported shouldBe emptyList()
            countVisualizations(OTHER_WORKSPACE, released.record.name) shouldBe 0
        }
    }

    @Test
    fun `a dashboard envelope over max-visualizations-per-dashboard refuses at the reader - nothing lands`() {
        val document = DocumentFixtures.dashboard().put("name", DASHBOARD_NAME)
        val occurrences = document.withArray("/visualizations") as ArrayNode
        val grid = document.withArray("/layout/grid") as ArrayNode
        repeat(DASHBOARD_OCCURRENCES_OVER_BOUND - 1) { index ->
            val occurrence = (occurrences.get(0) as ObjectNode).deepCopy()
            occurrence.put("name", "revenue_chart_$index")
            occurrences.add(occurrence)
            val item = grid.addObject()
            item.put("name", "revenue_chart_$index")
            item.put("x", 0)
            item.put("y", 0)
            item.put("w", 6)
            item.put("h", 4)
        }
        // The same document is refused at SAVE — the bound the import must hold too (non-vacuity on the bound).
        shouldThrow<ArtifactValidationException> { DashboardReader().readOrThrow(document.deepCopy() as ObjectNode) }
        // A version-less entry: the hash is present (the envelope shape needs it) but the version is absent,
        // so the landing's hash check is skipped and the refusal below can only be the reader's bound.
        val bodyNode = document.deepCopy() as ObjectNode
        bodyNode.remove("name")
        val payload =
            bodyNode
                .put("id", UUID.randomUUID().toString())
                .put("name", DASHBOARD_NAME)
                .put("body_hash", "unverified-when-version-is-absent")
        val envelope = ArtifactJson.mapper.createObjectNode() as ObjectNode
        envelope.set<JsonNode>("dashboard", payload)
        envelope.set<JsonNode>("visualizations", ArtifactJson.mapper.createArrayNode())

        val refusal = shouldThrow<DatapipelinesException> { transfer.importDashboard(OTHER_WORKSPACE, envelope, AUTHOR) }

        withClue("the refusal must be the family's body_invalid: ${refusal.details}") {
            refusal.code shouldBe DashboardErrorCodes.BODY_INVALID
        }
        bundle.imported shouldBe emptyList()
    }

    /** Recomputes `body_hash` over the edited body so the refusal is the BOUND's, never the hash check's. */
    private fun restampHash(payload: ObjectNode) {
        val body = ArtifactTransferService.bodyOf(payload, VisualizationBody::class.java)
        payload.put("body_hash", h.visualizationRepository.computeBodyHash(checkNotNull(body)))
    }

    private fun countVisualizations(
        workspace: UUID,
        name: String,
    ): Int =
        h.jdbc
            .queryForObject(
                "SELECT count(*) FROM visualizations WHERE workspace_id = :ws AND name = :name",
                mapOf("ws" to workspace, "name" to name),
                Int::class.java,
            ) ?: 0

    private companion object {
        const val ROWS_OVER_BOUND = 1_001 // max-fixture-rows-per-case defaults to 1,000
        const val DASHBOARD_OCCURRENCES_OVER_BOUND = 51 // max-visualizations-per-dashboard defaults to 50
        const val DASHBOARD_NAME = "finance/dashboards/bounds_probe"
    }
}
