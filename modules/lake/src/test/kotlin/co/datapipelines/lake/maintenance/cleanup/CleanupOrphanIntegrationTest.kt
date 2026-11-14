package co.datapipelines.lake.maintenance.cleanup

import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.LeaseKind
import co.datapipelines.lake.LeaseOutcome
import co.datapipelines.lake.maintenance.MaintenanceHarness
import co.datapipelines.lake.maintenance.MaintenanceOutcome
import co.datapipelines.lake.maintenance.MaintenanceTable
import co.datapipelines.lake.maintenance.PartResult
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Brief 962 B (C7, C10, C15) and D7 on Postgres and MinIO: every key a cleanup deletes is decided under the table lease
 * from a reachability read taken after the acquire; a never-published table loses an abandoned append's files after the
 * grace with no commit; another run's marker goes only once that run's row is terminal; and the lease-hold time with
 * 5,000 orphans. Ages are moved by the listing decorator only ([AgingS3Client]); the remover's clock is real.
 */
class CleanupOrphanIntegrationTest {
    private val h = CleanupHarness()

    @BeforeEach
    fun reset() = h.reset()

    @AfterEach
    fun close() {
        LakeTestDb.jdbc.execute("DROP TRIGGER IF EXISTS zz_test_962_hold ON iceberg_tables")
        LakeTestDb.jdbc.execute("DROP FUNCTION IF EXISTS test_962_hold()")
        h.close()
    }

    @Test
    fun `C7a a late commit held past its deadline - the acquire is refused, busy, nothing deleted, then its files survive`() {
        val t = h.table("c7a")
        h.append(t)
        val orphan = h.orphan(t)
        h.store.age = CleanupHarness.OLD
        val held = holdLateCommit(t)
        try {
            val keys = keys(t)

            val refused = h.cleanup().run(h.run(t))

            refused.outcome shouldBe MaintenanceOutcome.BUSY
            keys(t) shouldBe keys
            println("(962 C7a) refused at the acquire: outcome=${refused.outcome} deleted=${refused.figures.files} expected=0")
        } finally {
            held.release()
        }
        val late = held.landed()

        val after = h.cleanup().run(h.run(t))

        h.exists(late) shouldBe true
        h.exists(orphan) shouldBe false
        after.figures.files shouldBe 1
        println("(962 C7a) after the late commit landed: deleted=${after.figures.files} expected=1 (the orphan), its file kept")
    }

    @Test
    fun `C7b a late commit held past its deadline - the cleanup's acquire waits behind it, and its fresh read deletes none of its files`() {
        val t = h.table("c7b")
        h.append(t)
        val orphan = h.orphan(t)
        h.store.age = CleanupHarness.OLD
        val held = holdLateCommit(t)
        val cleanup =
            try {
                val waiting = CompletableFuture.supplyAsync { h.cleanup().run(h.run(t, leases = h.patientLeases())) }
                awaitLockWaiters(2) shouldBe true // the held commit (advisory) and the cleanup's acquire (the lease row)
                waiting
            } finally {
                held.release()
            }
        val late = held.landed()
        val result: PartResult = cleanup.get(MaintenanceHarness.WAIT_SECONDS, TimeUnit.SECONDS)

        h.exists(late) shouldBe true
        h.exists(orphan) shouldBe false
        result.figures.files shouldBe 1
        h.snapshotIds(t).size shouldBe 2 // the first append and the late one: the cleanup's read saw it land
        println(
            "(962 C7b) waited behind the late commit: outcome=${result.outcome} deleted=${result.figures.files} expected=1 (the orphan)",
        )
    }

    @Test
    fun `C10 a never-published table loses an abandoned first append's files after the grace, also deprecated, with no commit`() {
        listOf("active", "deprecated").forEach { status ->
            val t = h.status(h.table("c10_$status"), status)
            val abandoned = List(2) { h.orphan(t) }
            val young = h.orphan(t).also { h.store.young += it }
            h.store.age = CleanupHarness.OLD
            val revisions = h.revisionCount(t)

            val result = h.cleanup().run(h.run(t))

            result.outcome shouldBe MaintenanceOutcome.DONE
            abandoned.forEach { h.exists(it) shouldBe false }
            h.exists(young) shouldBe true
            h.revisionCount(t) shouldBe revisions
            result.details["floor"] shouldBe ExpiryFloor.NOT_APPLICABLE
            result.figures.files shouldBe 2
            h.store.young.clear()
            h.store.age = Duration.ZERO
            println("(962 C10) $status: deleted=${result.figures.files} expected=2, young kept, revisions=$revisions→${h.revisionCount(t)}")
        }
    }

    @Test
    fun `C15 the production RunStates lets a crashed run's marker go only once its row is terminal, and a marker with no row stays`() {
        val t = h.table("c15")
        h.append(t)
        val terminal = listOf("finished", "failed", "abandoned").associateWith { h.runRow(t, it) }
        val live = h.runRow(t, "running")
        val unknown = UUID.randomUUID()
        val target = h.target(t)
        (terminal.values + live + unknown).forEach {
            h.put(target.markerKey(it), 0)
            h.store.agedMarkers += target.markerKey(it) // other runs' markers, older than the grace
        }
        h.store.age = CleanupHarness.OLD

        val first = h.cleanup().run(h.run(t))

        terminal.values.forEach { h.exists(target.markerKey(it)) shouldBe false }
        h.exists(target.markerKey(live)) shouldBe true
        h.exists(target.markerKey(unknown)) shouldBe true
        first.details["left_marker_run_live"] shouldBe 1
        first.details["left_marker_run_unknown"] shouldBe 1
        first.figures.files shouldBe terminal.size.toLong()

        h.setRunState(live, "failed") // the live run crashes and is recorded

        val second = h.cleanup().run(h.run(t))

        h.exists(target.markerKey(live)) shouldBe false
        h.exists(target.markerKey(unknown)) shouldBe true
        second.figures.files shouldBe 1
        val left = first.details.filterKeys { it.startsWith("left_") }
        println(
            "(962 C15) pass1 deleted=${first.figures.files} expected=3 (finished, failed, abandoned) left=$left; " +
                "pass2 deleted=${second.figures.files} expected=1 (the run now failed); the marker with no row stays",
        )
    }

    @Test
    fun `D7 the lease is held through the deletion of 5,000 orphans - the hold time, measured`() {
        val t = h.table("d7")
        h.append(t)
        val orphans = h.orphans(t, ORPHANS)
        h.store.age = CleanupHarness.OLD

        val started = System.nanoTime()
        val result = h.cleanup().run(h.run(t))
        val held = Duration.ofNanos(System.nanoTime() - started)

        result.figures.files shouldBe ORPHANS.toLong()
        orphans.none { h.exists(it) } shouldBe true
        println(
            "(962 D7) orphans=$ORPHANS deleted=${result.figures.files} expected=$ORPHANS batches=${h.faults.deleteCalls.size} " +
                "lease held at most ${held.toMillis() / MILLIS} s ${held.toMillis() % MILLIS} ms (the part's acquire-to-release wall time)",
        )
    }

    /** The test's own listing of [t] (keys only, `dp/` keys apart: the passes' own markers come and go). */
    private fun keys(t: MaintenanceTable): Set<String> =
        h
            .listing(t)
            .map { it.key }
            .filterNot { "/dp/" in it }
            .toSet()

    /** A commit of [table] that passed the fence, held inside its transaction until [release], past its lease's deadline. */
    private inner class HeldCommit(
        private val lock: Connection,
        private val commit: CompletableFuture<String>,
    ) {
        fun release() {
            runCatching { lock.createStatement().use { it.execute("SELECT pg_advisory_unlock_all()") } }
            lock.close()
        }

        fun landed(): String = commit.get(MaintenanceHarness.WAIT_SECONDS, TimeUnit.SECONDS)
    }

    private fun holdLateCommit(table: MaintenanceTable): HeldCommit {
        LakeTestDb.jdbc.execute(
            "CREATE OR REPLACE FUNCTION test_962_hold() RETURNS trigger LANGUAGE plpgsql AS " +
                "\$\$ BEGIN PERFORM pg_advisory_xact_lock_shared($ADVISORY_KEY); RETURN NEW; END \$\$",
        )
        LakeTestDb.jdbc.execute(
            "CREATE TRIGGER zz_test_962_hold AFTER UPDATE ON iceberg_tables FOR EACH ROW EXECUTE FUNCTION test_962_hold()",
        )
        val lock = LakeTestDb.connect()
        lock.createStatement().use { it.execute("SELECT pg_advisory_lock($ADVISORY_KEY)") }
        val ttl = Duration.ofMinutes(5)
        val deadline = h.m.dbNow() + Duration.ofSeconds(WRITER_SECONDS)
        val outcome = LakeTestDb.leases.acquire(LeaseKind.TABLE, table.leaseName, UUID.randomUUID(), ttl, deadline, "test-962-late")
        val lease = (outcome as? LeaseOutcome.Acquired)?.lease ?: error("writer lease not acquired: $outcome")
        val commit = CompletableFuture.supplyAsync { h.appendUnder(table, lease) }
        h.m.awaitWaiter("Lock") shouldBe true
        h.m.awaitDbPast(deadline)
        return HeldCommit(lock, commit)
    }

    /** Polls `pg_stat_activity` on its own connection until [count] backends wait on a lock. */
    private fun awaitLockWaiters(count: Int): Boolean {
        val end = System.nanoTime() + Duration.ofSeconds(MaintenanceHarness.WAIT_SECONDS).toNanos()
        LakeTestDb.connect().use { probe ->
            while (System.nanoTime() < end) {
                if (lockWaiters(probe) >= count) return true
                Thread.sleep(POLL_MILLIS)
            }
        }
        return false
    }

    private fun lockWaiters(probe: java.sql.Connection): Int =
        probe
            .prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND pid <> pg_backend_pid()")
            .use { it.executeQuery().use { rs -> rs.next().let { rs.getInt(1) } } }

    private companion object {
        const val ADVISORY_KEY = 962
        const val WRITER_SECONDS = 2L
        const val POLL_MILLIS = 50L
        const val ORPHANS = 5_000
        const val MILLIS = 1000
    }
}
