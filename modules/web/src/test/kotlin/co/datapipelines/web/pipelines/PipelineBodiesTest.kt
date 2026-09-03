package co.datapipelines.web.pipelines

import co.datapipelines.pipeline.DatasourceRef
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionStatus
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class PipelineBodiesTest {
    private val repository = mockk<PipelineRepository>()
    private val bodies = PipelineBodies(repository)
    private val workspaceId = UUID.randomUUID()

    private fun record(
        name: String,
        id: UUID = UUID.randomUUID(),
    ) = PipelineRecord(
        id = id,
        name = name,
        displayName = "Display $name",
        description = "About $name",
        ownerId = UUID.randomUUID(),
        currentVersion = 1,
        isDeleted = false,
        createdAt = Instant.parse("2026-08-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-08-01T00:00:00Z"),
    )

    @Test
    fun `scan without datasource queries findAll`() {
        val first = record("a")
        val second = record("b")
        every { repository.findAll(any(), null) } returns listOf(first, second)

        val scan = bodies.scan(workspaceId)
        scan.records.size shouldBe 2
        scan.records shouldBe listOf(first, second)

        verify(exactly = 1) { repository.findAll(any(), null) }
    }

    @Test
    fun `scan with datasource pushes filter to SQL`() {
        val rec = record("p")
        every { repository.findAllByDatasource(any(), "pg-prod", null) } returns listOf(rec)

        val scan = bodies.scan(workspaceId, datasourceName = "pg-prod")
        scan.records.size shouldBe 1
        scan.records[0].name shouldBe "p"

        verify(exactly = 1) { repository.findAllByDatasource(any(), "pg-prod", null) }
    }

    @Test
    fun `the q filter matches name, display name and description, case-insensitively`() {
        val rec = record("monthly_revenue")
        every { repository.findAll(any(), null) } returns listOf(rec)

        val scan = bodies.scan(workspaceId)
        scan.matchesQuery(rec, "REVENUE") shouldBe true
        scan.matchesQuery(rec, "display monthly") shouldBe true
        scan.matchesQuery(rec, "about monthly") shouldBe true
        scan.matchesQuery(rec, "unrelated") shouldBe false
    }

    /**
     * 061/T79 — the delete guard's scan is the ANY-VERSION one, not `findAllByDatasource`.
     * The mock proves the delegation; `PipelineRepositoryDatasourceRefsIntegrationTest` proves
     * the SQL actually sees a historical version, which is the part that was broken.
     */
    @Test
    fun `anyVersionReferences delegates to findAnyVersionDatasourceRefs, never the current-version join`() {
        val refs =
            listOf(
                DatasourceRef(UUID.randomUUID(), "a", 1, PipelineVersionStatus.RELEASED, "n1"),
                DatasourceRef(UUID.randomUUID(), "b", 3, PipelineVersionStatus.DRAFT, "n2"),
            )
        every { repository.findAnyVersionDatasourceRefs(workspaceId, "pg-prod") } returns refs

        bodies.anyVersionReferences(workspaceId, "pg-prod") shouldBe refs
        verify(exactly = 0) { repository.findAllByDatasource(any(), "pg-prod", any(), any(), any()) }
    }
}
