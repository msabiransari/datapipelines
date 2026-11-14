package co.datapipelines.lake.maintenance.cleanup

import co.datapipelines.lake.maintenance.MaintenanceOutcome
import co.datapipelines.lake.maintenance.MaintenanceTable
import co.datapipelines.lake.maintenance.keep.KeepLabel
import co.datapipelines.lake.profiles.KeepPeriod
import co.datapipelines.scheduler.SystemJobOutcome
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Brief 962 A (C1–C6) on Postgres and MinIO: expiry honours the deployment window, §9's protected revisions and reader
 * pins, and the keeps, which are reconciled first in the SAME cleanup. Snapshot ages are real Iceberg timestamps
 * ([CleanupHarness.restamp]); every superseded revision is finalised by SQL ([CleanupHarness.finalise]) unless the case
 * protects it, because only the settlement sweep finalises one (V53) and a protected revision holds the floor back.
 */
class CleanupExpiryIntegrationTest {
    private val h = CleanupHarness()

    @BeforeEach
    fun reset() = h.reset()

    @AfterEach
    fun close() = h.close()

    @Test
    fun `C1 snapshots older than the deployment window expire and younger ones stay, with a non-default protection-days`() {
        val t = h.table("c1")
        val now = h.m.dbNow()
        val (s1, _) = h.appendAt(t, now - Duration.ofDays(5))
        val (s2, _) = h.appendAt(t, now - Duration.ofDays(2))
        val (s3, _) = h.append(t)
        h.finalise(t)
        val revisions = h.revisionCount(t)

        val before = h.partClock.now()
        val result = h.cleanup(properties = h.withWindow(WINDOW_DAYS)).run(h.run(t))
        val after = h.partClock.now()

        result.outcome shouldBe MaintenanceOutcome.DONE
        h.snapshotIds(t) shouldBe listOf(s2, s3)
        result.details["snapshots_expired"] shouldBe 1
        val floor = Instant.parse(result.details["floor"] as String)
        val window = Duration.ofDays(WINDOW_DAYS.toLong())
        (floor >= before - window && floor <= after - window) shouldBe true
        h.revisionCount(t) shouldBe revisions + 1 // the expiry commit, and no other
        println("(962 C1) window=${WINDOW_DAYS}d floor=$floor expired=[$s1] kept=[$s2,$s3] deleted=${result.figures.files} expected=0")
        result.figures.files shouldBe 0 // every key is younger than the grace (no age moved)
    }

    @Test
    fun `C2a a historical untagged snapshot older than the window whose revision is still protected survives`() {
        val t = h.table("c2a")
        h.keepProfile(t, "c2_a", "[]")
        val (s1, s2, s3) = threeSnapshots(t)
        h.finalise(t)
        h.protect(h.revisionOf(t, s1))

        val result = h.cleanup().run(h.run(t))

        h.snapshotIds(t) shouldContainAll listOf(s1, s2, s3)
        result.details["snapshots_expired"] shouldBe 0
        println("(962 C2a) floor=${result.details["floor"]} snapshots=${h.snapshotIds(t)} deleted=${result.figures.files} expected=0")
    }

    @Test
    fun `C2b a historical untagged snapshot older than the window whose revision a reader pins survives`() {
        val t = h.table("c2b")
        h.keepProfile(t, "c2_b", "[]")
        val (s1, s2, s3) = threeSnapshots(t)
        h.finalise(t)
        val pin = h.pin(t, h.revisionOf(t, s1))
        try {
            val result = h.cleanup().run(h.run(t))

            h.snapshotIds(t) shouldContainAll listOf(s1, s2, s3)
            result.details["snapshots_expired"] shouldBe 0
            println("(962 C2b) floor=${result.details["floor"]} snapshots=${h.snapshotIds(t)} deleted=${result.figures.files} expected=0")
        } finally {
            h.unpin(pin)
        }
    }

    @Test
    fun `C2c the current pointer older than the window survives and the floor is not later than its commit time`() {
        val t = h.table("c2c")
        h.keepProfile(t, "c2_c", "[]")
        val committed = h.m.dbNow() - Duration.ofDays(OLD_DAYS)
        val (s1, _) = h.appendAt(t, committed)
        h.finalise(t)

        val result = h.cleanup().run(h.run(t))

        h.snapshotIds(t) shouldBe listOf(s1)
        val floor = Instant.parse(result.details["floor"] as String)
        (floor <= committed.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)) shouldBe true
        println("(962 C2c) committed=$committed floor=$floor deleted=${result.figures.files} expected=0")
    }

    @Test
    fun `C3 nothing older than the floor commits nothing and makes no revision`() {
        val t = h.table("c3")
        h.append(t)
        val revisions = h.revisionCount(t)

        val result = h.cleanup().run(h.run(t))

        result.outcome shouldBe MaintenanceOutcome.NOTHING_TO_DO
        h.revisionCount(t) shouldBe revisions
        result.details["snapshots_expired"] shouldBe 0
        result.details["tags_created"] shouldBe 0
        println("(962 C3) revisions=$revisions→${h.revisionCount(t)} deleted=${result.figures.files} expected=0")
    }

    @Test
    fun `C3 a keep plan that only reports periods commits nothing and both reasons are on the result`() {
        val t = h.table("c3r")
        val now = h.m.dbNow()
        h.createdAt(t, now - Duration.ofDays(KEEP_CREATED_DAYS))
        h.keepProfile(t, "c3_r", DAY_KEEP)
        val (s1, _) = h.append(t)
        val yesterday = now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
        h.branch(t, KeepLabel(KeepPeriod.DAY, yesterday).tagName, s1)
        val revisions = h.revisionCount(t)

        val result = h.cleanup().run(h.run(t))

        result.outcome shouldBe MaintenanceOutcome.NOTHING_TO_DO
        h.revisionCount(t) shouldBe revisions
        result.details["tags_created"] shouldBe 0
        result.details["snapshots_expired"] shouldBe 0
        result.details["not_kept"] shouldBe mapOf("history_not_available" to 2, "name_taken_by_branch" to 1)
        println(
            "(962 C3r) not_kept=${result.details["not_kept"]} revisions=$revisions→${h.revisionCount(
                t,
            )} deleted=${result.figures.files} expected=0",
        )
    }

    @Test
    fun `C4 keeps and protected revisions hold a file through expiry and removal, and after the keep's end and the window it goes`() {
        val t = h.table("c4")
        val now = h.m.dbNow()
        h.createdAt(t, now - Duration.ofDays(KEEP_CREATED_DAYS))
        h.keepProfile(t, "c4_k", SHORT_KEEP)
        val (s1, f1) = h.appendAt(t, now - Duration.ofDays(MID_DAYS))
        val (s2, f2) = h.replace(t, f1)
        h.finalise(t)
        h.store.age = CleanupHarness.OLD // every unreachable key is past the grace: only reachability keeps one

        // Pass 1: the keep tags are made on s1 (two closed days of a two-day keep), so s1 is not expired and f1 stays.
        val (first, gone1) = deleting(t)
        first.details["tags_created"] shouldBe 2
        h.snapshotIds(t) shouldContainAll listOf(s1, s2)
        h.exists(f1) shouldBe true

        // Pass 2, three days later: both keeps have ended, the tags go, s1 expires; f1 is still reached by the revisions
        // this pass superseded (protected until the sweep finalises them), so it stays.
        h.partClock.shift(Duration.ofDays(SHIFT_DAYS))
        val (second, gone2) = deleting(t)
        second.details["tags_removed"] shouldBe 2
        h.snapshotIds(t) shouldNotContain s1
        second.details["snapshots_expired"] shouldBe 1
        h.exists(f1) shouldBe true

        // Pass 3, after the sweep: nothing reaches f1 any more and it is deleted; f2 (current) stays.
        h.finalise(t)
        val (third, gone3) = deleting(t)
        h.exists(f1) shouldBe false
        h.exists(f2) shouldBe true
        (h.target(t).relative(f1) in gone3) shouldBe true
        println("(962 C4) pass1 deleted=${first.figures.files} $gone1")
        println("(962 C4) pass2 deleted=${second.figures.files} $gone2")
        println("(962 C4) pass3 deleted=${third.figures.files} $gone3")
        listOf(first to gone1, second to gone2, third to gone3).forEach { (result, gone) ->
            result.figures.files shouldBe gone.size.toLong()
        }
        // Expected: nothing in passes 1–2; in pass 3 exactly f1, the manifest that added it and s1's manifest list.
        gone1.size shouldBe 0
        gone2.size shouldBe 0
        gone3.size shouldBe EXPIRED_SNAPSHOT_FILES
        gone3.count { it.startsWith("metadata/snap-$s1-") } shouldBe 1
    }

    /** One cleanup of [t] and the keys it deleted (the test's own listing before and after, `dp/` keys apart), relative. */
    private fun deleting(t: MaintenanceTable): Pair<co.datapipelines.lake.maintenance.PartResult, List<String>> {
        val before = dataKeys(t)
        val result = h.cleanup().run(h.run(t))
        return result to (before - dataKeys(t)).map { h.target(t).relative(it) }.sorted()
    }

    @Test
    fun `C5 a snapshot a rule keeps and older than the floor survives the same cleanup that would have expired it`() {
        val t = h.table("c5")
        val now = h.m.dbNow()
        h.createdAt(t, now - Duration.ofDays(KEEP_CREATED_DAYS))
        h.keepProfile(t, "c5_k", SHORT_KEEP)
        val (s1, _) = h.appendAt(t, now - Duration.ofDays(MID_DAYS))
        val (s2, _) = h.append(t)
        h.finalise(t)

        val result = h.cleanup().run(h.run(t))

        result.details["tags_created"] shouldBe 2
        result.details["snapshots_expired"] shouldBe 0
        h.snapshotIds(t) shouldContainAll listOf(s1, s2)
        h
            .metadata(t)
            .refs()
            .filterValues { it.isTag }
            .values
            .map { it.snapshotId() }
            .toSet() shouldBe setOf(s1)
        println("(962 C5) tags=${h.metadata(t).refs().keys} snapshots=${h.snapshotIds(t)} deleted=${result.figures.files} expected=0")
    }

    @Test
    fun `C6 an archived table's cleanup, through the worker, still reconciles keeps and expires`() {
        val t = h.status(h.table("c6"), "archived")
        val now = h.m.dbNow()
        h.createdAt(t, now - Duration.ofDays(KEEP_CREATED_DAYS))
        h.keepProfile(t, "c6_k", SHORT_KEEP)
        val (s0, _) = h.appendAt(t, now - Duration.ofDays(OLD_DAYS))
        val (s1, _) = h.appendAt(t, now - Duration.ofDays(MID_DAYS))
        val (s2, _) = h.append(t)
        h.finalise(t)

        h.worker().run(t.id.toString()) shouldBe SystemJobOutcome.Done

        val row = h.m.rows(t).single()
        row.state shouldBe "finished"
        val cleanup = h.parts(row).single { it["name"] == CleanupPart.NAME }
        cleanup["outcome"] shouldBe MaintenanceOutcome.DONE.wire
        cleanup["tags_created"] shouldBe 2
        cleanup["snapshots_expired"] shouldBe 1
        h.snapshotIds(t) shouldBe listOf(s1, s2)
        println("(962 C6) expired=[$s0] kept=[$s1,$s2] parts=${row.partsJson} deleted=${cleanup["files"]} expected=0")
    }

    /** s1 twenty days old, s2 ten days old, s3 now; each back-dated in the revision that made it current. */
    private fun threeSnapshots(t: MaintenanceTable): Triple<Long, Long, Long> {
        val now = h.m.dbNow()
        val (s1, _) = h.appendAt(t, now - Duration.ofDays(OLD_DAYS))
        val (s2, _) = h.appendAt(t, now - Duration.ofDays(MID_DAYS))
        val (s3, _) = h.append(t)
        return Triple(s1, s2, s3)
    }

    private fun dataKeys(t: MaintenanceTable): Set<String> =
        h
            .listing(t)
            .map { it.key }
            .filterNot { "/dp/" in it }
            .toSet()

    private companion object {
        const val WINDOW_DAYS = 3

        /** A one-file snapshot's own files once it is expired and unreached: its data file, its manifest, its manifest list. */
        const val EXPIRED_SNAPSHOT_FILES = 3
        const val OLD_DAYS = 20L
        const val MID_DAYS = 10L
        const val SHIFT_DAYS = 3L
        const val KEEP_CREATED_DAYS = 3L
        const val DAY_KEEP = """[{"period":"day","keep_for":{"n":30,"unit":"days"}}]"""
        const val SHORT_KEEP = """[{"period":"day","keep_for":{"n":2,"unit":"days"}}]"""
    }
}
