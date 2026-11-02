package co.datapipelines.web.config

import co.datapipelines.application.lake.LakeReferenceIndexRepository
import co.datapipelines.application.lake.LakeReferenceScan
import co.datapipelines.application.lake.LakeReferenceScanService
import co.datapipelines.application.lake.LakeScanStatus
import co.datapipelines.application.lake.ScannedVersion
import co.datapipelines.pipeline.release.ReleaseFamily
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionOperations
import java.util.UUID

/**
 * The backfill's paging (slice D-C): the worklist is a live query, so the stand-in index answers from a mutable set the
 * stand-in scanner shrinks — exactly what a committed scan row does to `unscannedReleased`. A version that fails is
 * left in the set (no row is written), which is the property the termination rule must survive.
 */
class LakeReferenceBackfillTest {
    private val workspace = UUID.randomUUID()
    private val unscanned = LinkedHashSet<ScannedVersion>()
    private val failing = HashSet<ScannedVersion>()
    private var scans = 0

    private val index =
        mockk<LakeReferenceIndexRepository>().also { index ->
            every { index.unscannedReleased(LakeReferenceScanService.SCANNER_VERSION, any()) } answers {
                unscanned.take(secondArg())
            }
        }
    private val scanner =
        mockk<LakeReferenceScanService>().also { scanner ->
            every { scanner.scan(any()) } answers {
                val version = firstArg<ScannedVersion>()
                scans++
                check(version !in failing) { "planted scan failure" }
                unscanned -= version
                LakeReferenceScan(version, "n", LakeScanStatus.EXACT, emptyList(), LakeReferenceScanService.SCANNER_VERSION, emptyList())
            }
        }

    private fun backfill(pageSize: Int) = LakeReferenceBackfill(index, scanner, TransactionOperations.withoutTransaction(), pageSize)

    private fun released(count: Int): List<ScannedVersion> =
        (1..count).map { ScannedVersion(workspace, ReleaseFamily.PIPELINE, UUID.randomUUID(), 1) }.also { unscanned += it }

    @Test
    fun `every page is drained, and a second pass finds nothing`() {
        released(25)
        backfill(pageSize = 10).backfill() shouldBe LakeReferenceBackfill.Outcome(scanned = 25, failed = 0)
        unscanned.size shouldBe 0
        backfill(pageSize = 10).backfill() shouldBe LakeReferenceBackfill.Outcome(scanned = 0, failed = 0)
    }

    @Test
    fun `a version that fails every time is tried once, and does not hide the versions behind it`() {
        val versions = released(25)
        failing += versions[0]
        failing += versions[9]

        backfill(pageSize = 10).backfill() shouldBe LakeReferenceBackfill.Outcome(scanned = 23, failed = 2)
        scans shouldBe 25
        unscanned shouldBe setOf(versions[0], versions[9])
    }

    @Test
    fun `a page with no successful scan ends the pass - a worklist of failures cannot spin it`() {
        failing += released(30)

        backfill(pageSize = 10).backfill() shouldBe LakeReferenceBackfill.Outcome(scanned = 0, failed = 10)
        scans shouldBe 10
    }
}
