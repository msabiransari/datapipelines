package co.datapipelines.lake.profiles

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.util.unit.DataSize
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * P2 (brief 956 D3, cut L5): the start-up seed copies the configured window and cap into `production` once; a second
 * start changes nothing; an edited `production` is never overwritten; two starts together leave one consistent row.
 * P9's seed half: the seed moves `updated_at` and leaves `updated_by` NULL (a system write).
 */
class LakeProfileSeedIntegrationTest {
    private val h = LakeProfilesHarness()

    @BeforeEach
    fun reset() = h.reset()

    @Test
    fun `the seed copies the configured window and cap once and a second start changes nothing`() {
        val before = row()
        h.seed("01:30-04:00", DataSize.ofGigabytes(10)).seed() shouldBe true
        val seeded = row()
        val values = seeded.values
        values.maintenanceWindow.text shouldBe "01:30-04:00"
        values.maxBytesRewrittenPerRun shouldBe DataSize.ofGigabytes(10).toBytes()
        values.copy(maintenanceWindow = LakeProfileFixtures.builtIn().maintenanceWindow, maxBytesRewrittenPerRun = BUILT_IN_CAP) shouldBe
            LakeProfileFixtures.builtIn()
        (seeded.seedPending to seeded.updatedBy) shouldBe (false to null)
        seeded.updatedAt.isAfter(before.updatedAt) shouldBe true
        h.seed("03:00-06:00", DataSize.ofGigabytes(20)).seed() shouldBe false
        row() shouldBe seeded
        h.profileEvents() shouldBe emptyList()
    }

    @Test
    fun `an edited production is not overwritten by a later start`() {
        h.profiles.edit("production", LakeProfileFixtures.document("maintenance.window" to "\"22:00-23:00\""), h.actor)
        val edited = row()
        edited.seedPending shouldBe false
        h.seed("01:30-04:00", DataSize.ofGigabytes(10)).seed() shouldBe false
        row() shouldBe edited
        row().values.maintenanceWindow.text shouldBe "22:00-23:00"
    }

    @Test
    fun `two seeds started together leave one consistent row and only one of them seeds`() {
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val results =
                (1..2).map {
                    pool.submit<Boolean> {
                        start.await()
                        h.seed("01:30-04:00", DataSize.ofGigabytes(10)).seed()
                    }
                }
            start.countDown()
            results.map { it.get(30, TimeUnit.SECONDS) }.count { it } shouldBe 1
        } finally {
            pool.shutdownNow()
        }
        val seeded = row()
        (seeded.values.maintenanceWindow.text to seeded.values.maxBytesRewrittenPerRun) shouldBe
            ("01:30-04:00" to DataSize.ofGigabytes(10).toBytes())
        seeded.seedPending shouldBe false
    }

    @Test
    fun `a seed the profile rules refuse rolls back and keeps the mark`() {
        val refusal = shouldThrow<DatapipelinesException> { h.seed("02:00-05:00", DataSize.ofMegabytes(100)).seed() }
        refusal.code shouldBe PipelineErrorCodes.Lake.PROFILE_INVALID
        refusal.details["path"] shouldBe "maintenance.max_bytes_rewritten_per_run"
        row().seedPending shouldBe true
        row().values shouldBe LakeProfileFixtures.builtIn()
    }

    private fun row(): StoredLakeProfile = requireNotNull(h.profiles.find("production"))

    private companion object {
        val BUILT_IN_CAP = DataSize.ofGigabytes(50).toBytes()
    }
}
