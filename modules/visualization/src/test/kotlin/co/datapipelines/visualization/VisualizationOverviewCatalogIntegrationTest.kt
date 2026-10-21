package co.datapipelines.visualization

import co.datapipelines.pipeline.CreateLifecycle
import co.datapipelines.pipeline.LifecycleBucket
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.visualization.VisualizationTestDb.AUTHOR
import co.datapipelines.visualization.VisualizationTestDb.OTHER_WORKSPACE
import co.datapipelines.visualization.VisualizationTestDb.WORKSPACE
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/** #581 — the repository module's own real-DB proof of filtered membership, listed versions and metadata. */
class VisualizationOverviewCatalogIntegrationTest {
    private val harness = LifecycleHarness()
    private val repository = harness.visualizationRepository
    private val body = ValidatorFakes.visualizationDocument(DocumentFixtures.visualization()).body

    @BeforeEach
    fun reset() {
        VisualizationTestDb.reset()
    }

    @Test
    fun `a lifecycle filter preserves an earlier current release after a switch`() {
        val id = UUID.randomUUID()
        repository.importNew(WORKSPACE, id, NAME, body, 1, null, AUTHOR)
        requireNotNull(repository.insertReleased(WORKSPACE, id, body.copy(displayName = "Later release"), 2, null, AUTHOR))
        requireNotNull(repository.switchCurrent(WORKSPACE, id, 1, draftEligible = true))
        listOf(LifecycleBucket.RELEASED_ONLY, LifecycleBucket.WITH_RELEASES).forEach { bucket ->
            val filtered = harness.visualizations.listIn(WORKSPACE, admitted(), bucket, null, 0, PAGE)
            filtered.total shouldBe 1
            filtered.items
                .single()
                .detail.version shouldBe 1
            filtered.items
                .single()
                .body.displayName shouldBe body.displayName
        }
    }

    @Test
    fun `search matches the admitted release instead of a hidden draft's indexed title or description`() {
        val id = UUID.randomUUID()
        val released = repository.importNew(WORKSPACE, id, NAME, body, 1, null, AUTHOR)
        val draft = body.copy(displayName = "Unreleased title", description = "Unreleased description")
        requireNotNull(repository.createDraft(WORKSPACE, id, draft, released.bodyHash, AUTHOR, WriteSurface.SESSION))
        requireNotNull(repository.switchCurrent(WORKSPACE, id, 2, draftEligible = true))
        requireNotNull(repository.findRecord(WORKSPACE, id)).displayName shouldBe "Unreleased title"
        listOf("Unreleased title", "Unreleased description").forEach { query ->
            val filtered = harness.visualizations.listIn(WORKSPACE, admitted(), LifecycleBucket.RELEASED_ONLY, query, 0, PAGE)
            filtered.total shouldBe 0
            filtered.items shouldBe emptyList()
        }
        val release = harness.visualizations.listIn(WORKSPACE, admitted(), LifecycleBucket.RELEASED_ONLY, body.displayName, 0, PAGE)
        release.total shouldBe 1
        release.items
            .single()
            .detail.version shouldBe 1
        release.items
            .single()
            .body.displayName shouldBe body.displayName
    }

    @Test
    fun `a dashboard search through the one entry point also refuses a hidden draft's indexed title and shows the pointer rule unlensed`() {
        val dashboards = harness.dashboardRepository
        val dashboardBody = ValidatorFakes.dashboardDocument(DocumentFixtures.dashboard()).body
        val id = UUID.randomUUID()
        val released = dashboards.importNew(WORKSPACE, id, NAME, dashboardBody, 1, null, AUTHOR)
        val draft = dashboardBody.copy(displayName = "Unreleased title", description = "Unreleased description")
        requireNotNull(dashboards.createDraft(WORKSPACE, id, draft, released.bodyHash, AUTHOR, WriteSurface.SESSION))
        requireNotNull(dashboards.switchCurrent(WORKSPACE, id, 2, draftEligible = true))
        requireNotNull(dashboards.findRecord(WORKSPACE, id)).displayName shouldBe "Unreleased title"
        // Narrowed to the admitted name, the selected version is the older RELEASE: the draft's indexed
        // title and description match nothing, rows AND total.
        listOf("Unreleased title", "Unreleased description").forEach { query ->
            val filtered = harness.dashboards.listIn(WORKSPACE, admitted(), LifecycleBucket.RELEASED_ONLY, query, 0, PAGE)
            filtered.total shouldBe 0
            filtered.items shouldBe emptyList()
        }
        // Unlensed the pointer names the draft: the row shows the draft (the pointer rule), so this
        // case goes red on the narrowed half only when index-row search is planted.
        val unlensed = harness.dashboards.listIn(WORKSPACE, ReadLens.Everything, LifecycleBucket.RELEASED_WITH_DRAFT, "Unreleased title", 0, PAGE)
        unlensed.total shouldBe 1
        unlensed.items
            .single()
            .detail.version shouldBe 2
        unlensed.items
            .single()
            .detail.status shouldBe PipelineVersionStatus.DRAFT
    }

    @Test
    fun `every bucket agrees with inventory across lifecycle transitions in this module`() {
        val id = UUID.randomUUID()
        val draft = repository.create(WORKSPACE, id, NAME, body, AUTHOR, CreateLifecycle.DRAFT, WriteSurface.MCP)
        equality()
        val released = requireNotNull(repository.releaseDraft(WORKSPACE, id, draft.bodyHash, AUTHOR))
        equality()
        val changes =
            requireNotNull(
                repository.createDraft(WORKSPACE, id, body.copy(description = "Edited"), released.bodyHash, AUTHOR, WriteSurface.MCP),
            )
        equality()
        requireNotNull(repository.discardVersion(WORKSPACE, id, released.version, AUTHOR, draftEligible = true))
        equality()
        requireNotNull(repository.purgeDraft(WORKSPACE, id, changes.bodyHash, draftEligible = true))
        equality()
        harness.visualizations.listIn(WORKSPACE, ReadLens.NOTHING, LifecycleBucket.WITH_RELEASES, null, 0, PAGE).total shouldBe 0
        repository.recent(WORKSPACE, ReadLens.NOTHING) shouldBe emptyList()
    }

    @Test
    fun `literal search paging and recent reads stay in the workspace`() {
        repeat(3) { index -> repository.importNew(WORKSPACE, UUID.randomUUID(), "test/item_$index", body, 1, null, AUTHOR) }
        repository.importNew(OTHER_WORKSPACE, UUID.randomUUID(), "test/item_foreign", body, 1, null, AUTHOR)
        val first = harness.visualizations.listIn(WORKSPACE, ReadLens.Everything, LifecycleBucket.WITH_RELEASES, "item_", 0, 2)
        val second = harness.visualizations.listIn(WORKSPACE, ReadLens.Everything, LifecycleBucket.WITH_RELEASES, "item_", 2, 2)
        first.total shouldBe 3
        first.items.size shouldBe 2
        second.items.size shouldBe 1
        (first.items.map { it.record.id }.toSet() intersect second.items.map { it.record.id }.toSet()).isEmpty() shouldBe true
        repository.recent(WORKSPACE, ReadLens.Everything).size shouldBe 3
        repository.recent(OTHER_WORKSPACE, ReadLens.Everything).size shouldBe 1
    }

    @Test
    fun `the shared repository's dashboard table mapping uses the same membership`() {
        val dashboards = harness.dashboardRepository
        val dashboard = ValidatorFakes.dashboardDocument(DocumentFixtures.dashboard()).body
        dashboards.importNew(WORKSPACE, UUID.randomUUID(), NAME, dashboard, 1, null, AUTHOR)
        dashboards.listIn(WORKSPACE, LifecycleBucket.RELEASED_ONLY, admitted().names, null).total shouldBe 1
    }

    private fun admitted() = ReadLens.Only(setOf(NAME))

    private fun equality() {
        listOf(ReadLens.Everything, admitted()).forEach { lens ->
            val inventory = repository.inventory(WORKSPACE, lens)
            LifecycleBucket.entries.forEach { bucket ->
                val page = harness.visualizations.listIn(WORKSPACE, lens, bucket, null, 0, PAGE)
                page.total.toLong() shouldBe bucket.countIn(inventory)
                page.items.size.toLong() shouldBe bucket.countIn(inventory)
            }
        }
    }

    private companion object {
        const val NAME = "release/switched"
        const val PAGE = 25
    }
}
