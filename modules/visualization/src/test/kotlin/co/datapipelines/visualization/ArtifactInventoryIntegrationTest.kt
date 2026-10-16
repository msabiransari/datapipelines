package co.datapipelines.visualization

import co.datapipelines.pipeline.CreateLifecycle
import co.datapipelines.pipeline.Inventory
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.visualization.VisualizationTestDb.AUTHOR
import co.datapipelines.visualization.VisualizationTestDb.OTHER_WORKSPACE
import co.datapipelines.visualization.VisualizationTestDb.WORKSPACE
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/** The repository module's own proof: real lifecycle writes move one object between inventory buckets. */
class ArtifactInventoryIntegrationTest {
    @BeforeEach
    fun reset() = VisualizationTestDb.reset()

    @Test
    fun `visualization inventory follows create release edit discard and purge`() {
        val body = ValidatorFakes.visualizationDocument(DocumentFixtures.visualization()).body
        exercise(VisualizationRepository(VisualizationTestDb.jdbc), body, body.copy(displayName = "Changed visualization"))
    }

    @Test
    fun `dashboard inventory follows create release edit discard and purge`() {
        val body = ValidatorFakes.dashboardDocument(DocumentFixtures.dashboard()).body
        exercise(DashboardRepository(VisualizationTestDb.jdbc), body, body.copy(displayName = "Changed dashboard"))
    }

    private fun <B : Any> exercise(
        repository: ArtifactRepository<B>,
        body: B,
        edited: B,
    ) {
        val id = UUID.randomUUID()
        val name = "inventory/${repository.kind.index}/item"
        val lens = ReadLens.Only(setOf(name))
        val draft = repository.create(WORKSPACE, id, name, body, AUTHOR, CreateLifecycle.DRAFT, WriteSurface.MCP)
        repository.inventory(WORKSPACE, ReadLens.Everything) shouldBe Inventory(1, 0, 0, 0)
        repository.inventory(WORKSPACE, lens) shouldBe Inventory(0, 0, 0, 0)
        repository.inventory(OTHER_WORKSPACE, ReadLens.Everything) shouldBe Inventory(0, 0, 0, 0)

        val released = requireNotNull(repository.releaseDraft(WORKSPACE, id, draft.bodyHash, AUTHOR))
        repository.inventory(WORKSPACE, lens) shouldBe Inventory(0, 1, 0, 0)
        repository.inventory(WORKSPACE, ReadLens.Everything) shouldBe Inventory(0, 1, 0, 0)

        val changes = requireNotNull(repository.createDraft(WORKSPACE, id, edited, released.bodyHash, AUTHOR, WriteSurface.MCP))
        repository.inventory(WORKSPACE, ReadLens.Everything) shouldBe Inventory(0, 0, 1, 0)
        repository.inventory(WORKSPACE, lens) shouldBe Inventory(0, 1, 0, 0)

        requireNotNull(repository.discardVersion(WORKSPACE, id, released.version, AUTHOR, draftEligible = true))
        repository.inventory(WORKSPACE, ReadLens.Everything) shouldBe Inventory(1, 0, 0, 0)
        requireNotNull(repository.purgeDraft(WORKSPACE, id, changes.bodyHash, draftEligible = true))
        repository.inventory(WORKSPACE, ReadLens.Everything) shouldBe Inventory(0, 0, 0, 1)
        repository.inventory(WORKSPACE, lens) shouldBe Inventory(0, 0, 0, 0)
    }
}
