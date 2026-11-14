package co.datapipelines.lake.maintenance.cleanup

import ch.qos.logback.classic.Logger
import co.datapipelines.lake.maintenance.MaintenanceOutcome
import co.datapipelines.lake.maintenance.remove.StopReason
import co.datapipelines.logcapture.capturingLogEvents
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import software.amazon.awssdk.core.exception.SdkClientException
import java.time.Duration
import java.time.Instant

/**
 * Brief 962 D (C14b) on Postgres and MinIO, with a recording [CleanupObserver]: one call, under the lease, only when the
 * remover's result carries an inventory; every field of D2b, the snapshots and refs being those AFTER the expiry; an
 * observer that throws never changes the cleanup.
 */
class CleanupObserverIntegrationTest {
    private val h = CleanupHarness()
    private val partLog = LoggerFactory.getLogger(CleanupPart::class.java) as Logger

    @BeforeEach
    fun reset() = h.reset()

    @AfterEach
    fun close() = h.close()

    @Test
    fun `C14b a complete pass - one call with every field, the snapshots and refs those after the expiry`() {
        val t = h.table("c14b_full")
        val snapshots = h.keptAndExpirable(t, "c14b_full_k")
        val orphan = h.orphan(t)
        h.store.age = CleanupHarness.OLD
        val run = h.run(t)
        val before = h.m.dbNow()

        val result = h.cleanup().run(run)

        val after = h.m.dbNow()
        val o = h.observer.observations.single()
        val metadata = h.metadata(t)
        o.runId shouldBe run.runId
        o.tableId shouldBe t.id
        o.location shouldBe h.target(t).location
        (!o.listingTime.isBefore(before.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)) && !o.listingTime.isAfter(after)) shouldBe true
        o.currentSnapshotId shouldBe snapshots.current
        o.snapshots shouldBe metadata.snapshots().map { ObservedSnapshot(it.snapshotId(), Instant.ofEpochMilli(it.timestampMillis())) }
        o.snapshots.map { it.snapshotId } shouldBe listOf(snapshots.kept, snapshots.current)
        o.refs.toSet() shouldBe
            metadata
                .refs()
                .map { (name, ref) -> ObservedRef(name, if (ref.isBranch) ObservedRefType.BRANCH else ObservedRefType.TAG, ref.snapshotId()) }
                .toSet()
        o.refs.count { it.type == ObservedRefType.TAG } shouldBe 2
        o.floor.shouldBeInstanceOf<ExpiryFloor.Applied>().wire shouldBe result.details["floor"]
        o.inventory.map { it.key } shouldContainAll h.listing(t).map { it.key }.filterNot { "/dp/" in it }
        o.inventory.none { it.key == orphan } shouldBe true // the inventory is what stays, never a deleted key
        (o.visits.isNotEmpty()) shouldBe true
        o.pass shouldBe ObservedPass.Complete
        result.details["observer"] shouldBe ObserverOutcome.OK.wire
        println(
            "(962 C14b complete) calls=1 snapshots=${o.snapshots.map { it.snapshotId }} refs=${o.refs.map { "${it.name}:${it.type.wire}" }} " +
                "floor=${o.floor.wire} inventory=${o.inventory.size} visits=${o.visits.size} deleted=${result.figures.files} expected=1",
        )
    }

    @Test
    fun `C14b a never-published table - one call, no current snapshot, the floor not applicable`() {
        val t = h.table("c14b_never")
        h.orphan(t)
        h.store.age = CleanupHarness.OLD

        val result = h.cleanup().run(h.run(t))

        val o = h.observer.observations.single()
        o.currentSnapshotId shouldBe null
        o.snapshots shouldBe emptyList()
        o.floor shouldBe ExpiryFloor.NotApplicable
        o.pass shouldBe ObservedPass.Complete
        result.figures.files shouldBe 1
        println("(962 C14b never published) calls=1 current=null floor=${o.floor.wire} deleted=${result.figures.files} expected=1")
    }

    @Test
    fun `C14b a refused pass - no call`() {
        val t = h.table("c14b_refused")
        h.append(t)
        h.orphan(t)
        h.store.age = CleanupHarness.OLD
        h.removerClock.shift(Duration.ofSeconds(SKEW_S))

        shouldThrow<CleanupClockSkew> { h.cleanup().run(h.run(t)) }

        h.observer.observations shouldBe emptyList()
        println("(962 C14b refused) calls=0 deleted=0 expected=0")
    }

    @Test
    fun `C14b a pass stopped after its listing - one call marked stopped`() {
        val t = h.table("c14b_stopped")
        h.append(t)
        val orphan = h.orphan(t)
        h.store.age = CleanupHarness.OLD
        h.faults.onDelete = { index, _, _ -> if (index == 0) throw SdkClientException.create("injected: batch failed") else null }

        shouldThrow<CleanupIncomplete> { h.cleanup().run(h.run(t)) }.reason shouldBe IncompleteReason.DELETE_FAILED

        val o = h.observer.observations.single()
        o.pass shouldBe ObservedPass.Stopped(StopReason.DELETE_FAILED)
        h.exists(orphan) shouldBe true
        println("(962 C14b stopped) calls=1 pass=${o.pass} deleted=0 expected=0 (outcome unknown, the key still there)")
    }

    @Test
    fun `C14b an observer that throws - the outcome is what it would have been, observer failed on the result, one event`() {
        val t = h.table("c14b_throws")
        h.append(t)
        val orphan = h.orphan(t)
        h.store.age = CleanupHarness.OLD
        h.observer.failure = IllegalStateException("observer secret text")

        val events = capturingLogEvents(partLog) { h.worker().run(t.id.toString()) }

        val row = h.m.rows(t).single()
        row.state shouldBe "finished"
        val part = h.parts(row).single { it["name"] == CleanupPart.NAME }
        part["outcome"] shouldBe MaintenanceOutcome.DONE.wire
        part["observer"] shouldBe ObserverOutcome.FAILED.wire
        part["files"] shouldBe 1
        h.exists(orphan) shouldBe false
        val failed = events.map { it.formattedMessage }.filter { "event=lake.cleanup.observer_failed" in it }
        failed.size shouldBe 1
        failed.single() shouldContain "IllegalStateException"
        failed.single() shouldNotContain "observer secret text"
        println("(962 C14b throws) row=${row.state} outcome=${part["outcome"]} observer=${part["observer"]} deleted=1 expected=1 event: ${failed.single()}")
    }

    private companion object {
        /** Past the probe's bound: the remover refuses before it lists. */
        const val SKEW_S = 3665L
    }
}
