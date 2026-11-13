package co.datapipelines.staging

import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A stale ownership proof never deletes a reclaimed live database (#803 review R1; staging.md §8.5).
 *
 * Each test holds a housekeeper in the window between reading a dead owner's marker and probing that
 * owner (the `afterMarkerRead` seam), lets an already-swept factory reclaim the same execution id
 * from the dead owner and open a live database, then lets the housekeeper go on. Deterministic: the
 * interleaving is forced with latches, never hoped for.
 */
class DuckDbScratchReclaimRaceTest {
    @TempDir
    lateinit var scratch: Path

    private val pool = Executors.newSingleThreadExecutor()
    private val resume = CountDownLatch(1)
    private val paused = CountDownLatch(1)

    @AfterEach
    fun release() {
        resume.countDown()
        pool.shutdownNow()
    }

    /** A housekeeper of a fresh owner that stops after its marker read until [resume]. */
    private fun pausedHousekeeper(): DuckDbScratchHousekeeper =
        DuckDbScratchHousekeeper(scratch, DuckDbScratchOwnership.newOwner(), RecordingLogger()) {
            paused.countDown()
            check(resume.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "never resumed" }
        }

    /** A factory whose own sweep already ran (on an empty directory), so its next open meets the same-id path. */
    private fun sweptReclaimer(): DuckDbStagingFactory =
        duckFactory(scratch).also {
            it.prepareDelivery(UUID.randomUUID(), MIB).close()
            it.budget.leftoverBytes shouldBe 0L
        }

    @Test
    fun `a sweep that read the dead owner's marker before a reclaim leaves the new live database alone`() {
        val reclaimer = sweptReclaimer()
        val deadOwner = plantDeadOwner(scratch)
        val id = UUID.randomUUID()
        val files = plantDatabase(scratch, id, ownerId = deadOwner)
        Files.readString(files.marker) shouldBe deadOwner.toString()
        val sweeper = pausedHousekeeper()
        val sweep = pool.submit<DuckDbScratchHousekeeper.SweepResult> { sweeper.sweep() }
        paused.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true

        reclaimer.create(id, MIB * 1024).use { live ->
            live.authorExec("CREATE TABLE t AS SELECT 42 AS id")
            val newMarker = Files.readString(files.marker)
            newMarker shouldNotBe deadOwner.toString()
            Files.exists(files.database) shouldBe true

            resume.countDown()
            val result = sweep.get(WAIT_SECONDS, TimeUnit.SECONDS)

            // Rules out: deleting under a proof of the OLD owner what the marker snapshot named.
            Files.exists(files.database) shouldBe true
            Files.readString(files.marker) shouldBe newMarker
            result.removed shouldNotContain files.database
            result.counted.keys shouldNotContain files.database
            live.authorExec("INSERT INTO t VALUES (43)")
            live.scalarText("SELECT count(*) FROM t") shouldBe "2"
        }
    }

    @Test
    fun `a same-id open that read the dead owner's marker before a reclaim fails and leaves the new live database alone`() {
        val reclaimer = sweptReclaimer()
        val deadOwner = plantDeadOwner(scratch)
        val id = UUID.randomUUID()
        val files = plantDatabase(scratch, id, ownerId = deadOwner)
        Files.readString(files.marker) shouldBe deadOwner.toString()
        val claimer = pausedHousekeeper()
        val claim = pool.submit { claimer.claim(DuckDbStagingFiles(scratch, id)) }
        paused.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true

        reclaimer.create(id, MIB * 1024).use { live ->
            live.authorExec("CREATE TABLE t AS SELECT 42 AS id")
            val newMarker = Files.readString(files.marker)
            newMarker shouldNotBe deadOwner.toString()
            Files.exists(files.database) shouldBe true

            resume.countDown()
            val outcome = runCatching { claim.get(WAIT_SECONDS, TimeUnit.SECONDS) }

            // Rules out: the same-id cleanup deleting a reclaimed database under the old owner's proof.
            Files.exists(files.database) shouldBe true
            Files.readString(files.marker) shouldBe newMarker
            // The id is live in another owner: the claim is refused, nothing of it written.
            (outcome.exceptionOrNull()?.cause is IOException) shouldBe true
            live.authorExec("INSERT INTO t VALUES (43)")
            live.scalarText("SELECT count(*) FROM t") shouldBe "2"
        }
    }

    private companion object {
        const val WAIT_SECONDS = 30L
    }
}
