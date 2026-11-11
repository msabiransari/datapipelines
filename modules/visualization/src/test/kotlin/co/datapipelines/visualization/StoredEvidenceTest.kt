package co.datapipelines.visualization

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Instant

/**
 * The read-side evidence ladder (#873 R1) shared by the release gate and the visualization dependency provider: every
 * class is reachable, only GREEN for the exact hash is READY, and a RUNNING row past its deadline reads as EXPIRED.
 */
class StoredEvidenceTest {
    private val now = Instant.parse("2026-11-11T12:00:00Z")
    private val later = now.plusSeconds(600)
    private val earlier = now.minusSeconds(1)

    private fun run(
        status: TestRunStatus,
        hash: String = HASH,
        expiresAt: Instant = later,
    ) = LatestRunFacts(status, hash, expiresAt)

    @Test
    fun `GREEN for the exact stored hash is the only READY`() {
        StoredEvidence.classify(run(TestRunStatus.GREEN), HASH, now) shouldBe StoredEvidence.READY
    }

    @Test
    fun `no run is NO_RUNS`() {
        StoredEvidence.classify(null, HASH, now) shouldBe StoredEvidence.NO_RUNS
    }

    @Test
    fun `a GREEN run for other content is HASH_MISMATCH, stale evidence never reads ready`() {
        StoredEvidence.classify(run(TestRunStatus.GREEN, hash = "other"), HASH, now) shouldBe StoredEvidence.HASH_MISMATCH
    }

    @Test
    fun `an open session is OPEN, and the same session past its deadline is EXPIRED`() {
        StoredEvidence.classify(run(TestRunStatus.RUNNING), HASH, now) shouldBe StoredEvidence.OPEN
        StoredEvidence.classify(run(TestRunStatus.RUNNING, expiresAt = earlier), HASH, now) shouldBe StoredEvidence.EXPIRED
        StoredEvidence.classify(run(TestRunStatus.RUNNING, expiresAt = now), HASH, now) shouldBe StoredEvidence.EXPIRED
    }

    @Test
    fun `a swept EXPIRED row is EXPIRED whatever its hash - the gate's order`() {
        StoredEvidence.classify(run(TestRunStatus.EXPIRED), HASH, now) shouldBe StoredEvidence.EXPIRED
        StoredEvidence.classify(run(TestRunStatus.EXPIRED, hash = "other"), HASH, now) shouldBe StoredEvidence.EXPIRED
    }

    @Test
    fun `RED and INCOMPLETE are NOT_GREEN`() {
        StoredEvidence.classify(run(TestRunStatus.RED), HASH, now) shouldBe StoredEvidence.NOT_GREEN
        StoredEvidence.classify(run(TestRunStatus.INCOMPLETE), HASH, now) shouldBe StoredEvidence.NOT_GREEN
    }

    @ParameterizedTest
    @EnumSource(TestRunStatus::class)
    fun `READY iff the status is GREEN and the hash matches, for every status`(status: TestRunStatus) {
        val ready = StoredEvidence.classify(run(status), HASH, now) == StoredEvidence.READY
        ready shouldBe (status == TestRunStatus.GREEN)
        (StoredEvidence.classify(run(status, hash = "other"), HASH, now) == StoredEvidence.READY) shouldBe false
    }

    private companion object {
        const val HASH = "sha256:aa"
    }
}
