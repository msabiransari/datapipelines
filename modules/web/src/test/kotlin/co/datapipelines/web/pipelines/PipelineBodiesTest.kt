package co.datapipelines.web.pipelines

import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The bounded-scan stopgap (carry-forward #6): one `findAll()` per scan, bodies parsed at most
 * once, the ceiling truncating instead of scanning unboundedly.
 */
class PipelineBodiesTest {
    private val repository = mockk<PipelineRepository>()
    private val bodies = PipelineBodies(repository)

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
    fun `one scan reads the table once and parses each body at most once`() {
        val first = record("a")
        val second = record("b")
        every { repository.findAll(null) } returns listOf(first, second)
        every { repository.findVersionBody(first.id, 1) } returns """{"nodes":[{"source":"pg-prod"}]}"""
        every { repository.findVersionBody(second.id, 1) } returns """{"nodes":[]}"""

        val scan = bodies.scan()
        scan.usesDatasource(first, "pg-prod") shouldBe true
        scan.usesDatasource(first, "pg-prod") shouldBe true // memoized, not re-read
        scan.usesDatasource(second, "pg-prod") shouldBe false
        scan.records.size shouldBe 2

        verify(exactly = 1) { repository.findAll(null) }
        verify(exactly = 1) { repository.findVersionBody(first.id, 1) }
    }

    @Test
    fun `the datasource filter sees node sources and output datasources`() {
        val rec = record("p")
        every { repository.findAll(null) } returns listOf(rec)
        every { repository.findVersionBody(rec.id, 1) } returns
            """{"nodes":[{"source":"tempdb","output":{"datasource":"pg-warehouse","table":"t"}}]}"""

        val scan = bodies.scan()
        scan.usesDatasource(rec, "pg-warehouse") shouldBe true
        scan.usesDatasource(rec, "tempdb") shouldBe true
        scan.usesDatasource(rec, "pg-prod") shouldBe false
    }

    @Test
    fun `the q filter matches name, display name and description, case-insensitively`() {
        val rec = record("monthly_revenue")
        every { repository.findAll(null) } returns listOf(rec)

        val scan = bodies.scan()
        scan.matchesQuery(rec, "REVENUE") shouldBe true
        scan.matchesQuery(rec, "display monthly") shouldBe true
        scan.matchesQuery(rec, "about monthly") shouldBe true
        scan.matchesQuery(rec, "unrelated") shouldBe false
    }

    @Test
    fun `the ceiling truncates and reports it`() {
        val many = (1..PipelineBodies.MAX_SCANNED_PIPELINES + 5).map { record("p$it") }
        every { repository.findAll(null) } returns many

        val scan = bodies.scan()
        scan.records.size shouldBe PipelineBodies.MAX_SCANNED_PIPELINES
        scan.truncated shouldBe true
    }

    @Test
    fun `an unreadable body scans as not-referencing rather than failing the listing`() {
        val rec = record("broken")
        every { repository.findAll(null) } returns listOf(rec)
        every { repository.findVersionBody(rec.id, 1) } returns "not json"

        bodies.scan().usesDatasource(rec, "pg-prod") shouldBe false
    }
}
