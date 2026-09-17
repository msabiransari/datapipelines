package co.datapipelines.executor

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The per-node operation tracker (149 §2/§4): phase clock, cumulative counts, the emission
 * rules that bound event volume, and the seal that stops an abandoned driver body from
 * publishing after the node ended.
 *
 * The clock is injected (nanos + instants) so every timing assertion is exact.
 */
class NodeOperationTrackerTest {
    private val clock = FakeClock()

    private fun tracker(intervalMs: Long = 5_000) =
        NodeOperationTracker(
            nodeId = "n",
            attempt = 1,
            kind = OperationKind.STAGE,
            destination = OperationDestination.tempdb("t"),
            sampleIntervalMs = intervalMs,
            nanoTime = clock::nanos,
            now = clock::instant,
        )

    @Test
    fun `the first sample is due at once and carries the opening state`() {
        val t = tracker()
        t.enter(OperationPhase.EXECUTING)
        val sample = t.sampleIfDue()
        sample.shouldNotBeNull()
        sample.sequence shouldBe 1
        sample.state shouldBe OperationState.EXECUTING
        sample.kind shouldBe OperationKind.STAGE
        sample.destination shouldBe OperationDestination.tempdb("t")
        sample.rowsFetched.shouldBeNull()
        sample.rowsWritten.shouldBeNull()
        // Nothing changed since: not due again.
        t.sampleIfDue().shouldBeNull()
    }

    @Test
    fun `a first entry is sampled at the entry instant even when the pump only asks later`() {
        val t = tracker(intervalMs = 5_000)
        t.enter(OperationPhase.EXECUTING)
        clock.advanceMs(100)
        t.enter(OperationPhase.WRITING)
        t.written(10)
        clock.advanceMs(40)
        t.enter(OperationPhase.FETCHING)
        // A writing interval of 40 ms, shorter than any tick, is still on the wire as WRITING,
        // observed when it began — never lost to sampling.
        val samples = generateSequence { t.sampleIfDue() }.toList()
        samples.map { it.state } shouldContainExactly listOf(OperationState.EXECUTING, OperationState.WRITING, OperationState.FETCHING)
        samples[1].observedAt shouldBe Instant.parse("2026-09-16T10:00:00.100Z")
        samples[1].rowsWritten.shouldBeNull()
        samples[2].rowsWritten shouldBe 10
        // The terminal flush drains what the pump has not collected, in sequence.
        t.enter(OperationPhase.FINALIZING)
        t.drainPending().map { it.state } shouldContainExactly listOf(OperationState.FINALIZING)
        t.finish(OperationOutcome.COMPLETED).sequence shouldBe 5
    }

    @Test
    fun `a state entered for the first time is due immediately, a repeat is throttled`() {
        val t = tracker(intervalMs = 5_000)
        t.enter(OperationPhase.EXECUTING)
        t.sampleIfDue().shouldNotBeNull()
        clock.advanceMs(10)
        t.enter(OperationPhase.FETCHING)
        t.sampleIfDue().shouldNotBeNull().state shouldBe OperationState.FETCHING
        t.enter(OperationPhase.WRITING)
        t.sampleIfDue().shouldNotBeNull().state shouldBe OperationState.WRITING
        // The second fetch/write cycle is a repeat: throttled until the interval elapses.
        t.enter(OperationPhase.FETCHING)
        t.fetched(100)
        t.sampleIfDue().shouldBeNull()
        clock.advanceMs(5_000)
        val due = t.sampleIfDue().shouldNotBeNull()
        due.state shouldBe OperationState.FETCHING
        due.rowsFetched shouldBe 100
    }

    @Test
    fun `timings accumulate per state and the open state counts up to the observation`() {
        val t = tracker()
        t.enter(OperationPhase.EXECUTING)
        clock.advanceMs(100)
        t.enter(OperationPhase.FETCHING)
        clock.advanceMs(30)
        t.enter(OperationPhase.WRITING)
        clock.advanceMs(20)
        t.enter(OperationPhase.FETCHING)
        clock.advanceMs(10)
        // The three first-entry samples were taken at their entry instants; drain them.
        val entries = generateSequence { t.sampleIfDue() }.take(3).toList()
        entries.map { it.state } shouldContainExactly listOf(OperationState.EXECUTING, OperationState.FETCHING, OperationState.WRITING)
        entries[1].timingsMs[OperationPhase.EXECUTING] shouldBe 100
        entries[2].timingsMs[OperationPhase.FETCHING] shouldBe 30
        // Then the periodic rule, with the open phase counted up to the observation.
        clock.advanceMs(5_000)
        val s = t.sampleIfDue().shouldNotBeNull()
        s.timingsMs[OperationPhase.EXECUTING] shouldBe 100
        s.timingsMs[OperationPhase.FETCHING] shouldBe 5_040
        s.timingsMs[OperationPhase.WRITING] shouldBe 20
        s.timingsMs.shouldNotContainKey(OperationPhase.WAITING_OUTPUT)
        s.elapsedMs shouldBe 5_160
    }

    @Test
    fun `counts are cumulative and batches are counted per write`() {
        val t = tracker()
        t.enter(OperationPhase.WRITING)
        t.written(1000)
        t.written(1000)
        t.written(7)
        t.fetched(2007)
        t.committed()
        val s = t.finish(OperationOutcome.COMPLETED)
        s.rowsWritten shouldBe 2007
        s.rowsFetched shouldBe 2007
        s.batchesWritten shouldBe 3
        s.committed shouldBe true
        s.state shouldBe OperationState.COMPLETED
    }

    @Test
    fun `finish seals the tracker — later observations are dropped and counted`() {
        val t = tracker()
        t.enter(OperationPhase.WRITING)
        t.written(10)
        t.finish(OperationOutcome.FAILED, rolledBack = true)
        t.isSealed.shouldBeTrue()
        t.written(500)
        t.enter(OperationPhase.FINALIZING)
        t.dropped shouldBe 2
        t.sampleIfDue().shouldBeNull()
        t.committed()
        val again = t.finish(OperationOutcome.COMPLETED)
        // The first terminal outcome stands; a second finish is a no-op returning the sealed snapshot.
        again.state shouldBe OperationState.FAILED
        again.rowsWritten shouldBe 10
        again.rolledBack shouldBe true
        again.committed shouldBe false
    }

    @Test
    fun `a committed label never appears before the terminal sample`() {
        val t = tracker()
        t.enter(OperationPhase.FINALIZING)
        t.committed()
        t
            .sampleIfDue()
            .shouldNotBeNull()
            .committed
            .shouldBeNull()
        t.finish(OperationOutcome.COMPLETED).committed shouldBe true
    }

    @Test
    fun `zero rows still reach a terminal state with zero counts`() {
        val t = tracker()
        t.enter(OperationPhase.FETCHING)
        t.fetched(0)
        t.enter(OperationPhase.WRITING)
        t.written(0)
        t.committed()
        val s = t.finish(OperationOutcome.COMPLETED)
        s.rowsFetched shouldBe 0
        s.rowsWritten shouldBe 0
        s.batchesWritten shouldBe 1
    }

    @Test
    fun `the terminal sample is due exactly once through the pump path`() {
        val t = tracker()
        t.enter(OperationPhase.EXECUTING)
        t.sampleIfDue()
        t.finish(OperationOutcome.ABORTED)
        // The pump must not re-send a sealed tracker's sample: the node coroutine flushed it.
        t.sampleIfDue().shouldBeNull()
        t.isSealed.shouldBeTrue()
    }

    @Test
    fun `sequence numbers are strictly increasing across pump samples and the terminal flush`() {
        val t = tracker(intervalMs = 1)
        t.enter(OperationPhase.EXECUTING)
        val seqs = mutableListOf<Int>()
        seqs += t.sampleIfDue().shouldNotBeNull().sequence
        clock.advanceMs(2)
        t.fetched(1)
        seqs += t.sampleIfDue().shouldNotBeNull().sequence
        seqs += t.finish(OperationOutcome.COMPLETED).sequence
        seqs shouldContainExactly listOf(1, 2, 3)
    }

    @Test
    fun `child execution id rides on every later sample`() {
        val t =
            NodeOperationTracker(
                "p",
                1,
                OperationKind.CHILD,
                OperationDestination.NONE,
                sampleIntervalMs = 5_000,
                nanoTime = clock::nanos,
                now = clock::instant,
            )
        t.enter(OperationPhase.EXECUTING)
        t
            .sampleIfDue()
            .shouldNotBeNull()
            .childExecutionId
            .shouldBeNull()
        val child = java.util.UUID.randomUUID()
        t.childExecution(child)
        clock.advanceMs(5_000)
        t.sampleIfDue().shouldNotBeNull().childExecutionId shouldBe child
        t.finish(OperationOutcome.COMPLETED).childExecutionId shouldBe child
    }

    @Test
    fun `an operation that never entered a state still finishes honestly`() {
        val t = tracker()
        val s = t.finish(OperationOutcome.ABORTED)
        s.state shouldBe OperationState.ABORTED
        s.timingsMs.isEmpty().shouldBeTrue()
        s.elapsedMs shouldBe 0
        s.sequence shouldBe 1
    }

    @Test
    fun `a sample never says committed while running even after the writer reported the commit`() {
        val t = tracker()
        t.enter(OperationPhase.WRITING)
        t.written(5)
        t.enter(OperationPhase.FINALIZING)
        t.committed()
        val s = generateSequence { t.sampleIfDue() }.toList().last()
        s.committed.shouldBeNull()
        s.state shouldBe OperationState.FINALIZING
        s.timingsMs shouldContainKey OperationPhase.FINALIZING
        t.wasCommitted.shouldBeTrue()
        t.wasRolledBack.shouldBeFalse()
    }

    private class FakeClock {
        private var nanosNow = 1_000_000_000L
        private var instantNow: Instant = Instant.parse("2026-09-16T10:00:00Z")

        fun nanos(): Long = nanosNow

        fun instant(): Instant = instantNow

        fun advanceMs(ms: Long) {
            nanosNow += ms * 1_000_000
            instantNow = instantNow.plusMillis(ms)
        }
    }

    // ---------------------------------------------------------------- commit evidence (R149-1)

    @Test
    fun `a confirmed commit survives a node that fails afterwards`() {
        val t = tracker()
        t.enter(OperationPhase.FINALIZING)
        t.written(300)
        t.committed()
        // The connection close threw after commit(); the node is FAILED — the rows are durable.
        val s = t.finish(OperationOutcome.FAILED)
        s.state shouldBe OperationState.FAILED
        s.committed shouldBe true
        s.rolledBack.shouldBeNull()
    }

    @Test
    fun `a confirmed commit survives an abort that lands after it`() {
        val t = tracker()
        t.enter(OperationPhase.FINALIZING)
        t.committed()
        val s = t.finish(OperationOutcome.ABORTED)
        s.state shouldBe OperationState.ABORTED
        s.committed shouldBe true
    }

    @Test
    fun `a confirmed rollback reads false and names itself, from the writer or from the caller`() {
        val a = tracker()
        a.enter(OperationPhase.WRITING)
        a.rolledBack()
        a.finish(OperationOutcome.FAILED).let {
            it.committed shouldBe false
            it.rolledBack shouldBe true
        }
        val b = tracker()
        b.enter(OperationPhase.WRITING)
        b.finish(OperationOutcome.FAILED, rolledBack = true).let {
            it.committed shouldBe false
            it.rolledBack shouldBe true
        }
    }

    @Test
    fun `an unobserved commit on a failed or aborted outcome is absent, never false`() {
        listOf(OperationOutcome.FAILED, OperationOutcome.ABORTED).forEach { outcome ->
            val t = tracker()
            t.enter(OperationPhase.FINALIZING)
            // The driver never returned from commit() before the deadline: nobody knows.
            val s = t.finish(outcome)
            s.committed.shouldBeNull()
            s.rolledBack.shouldBeNull()
        }
    }

    @Test
    fun `a completed outcome without a commit report is absent, not true`() {
        val t = tracker()
        t.enter(OperationPhase.WRITING)
        t.written(1)
        t.finish(OperationOutcome.COMPLETED).committed.shouldBeNull()
    }

    @Test
    fun `a destination with nothing to commit reports no commit on any outcome`() {
        OperationOutcome.entries.forEach { outcome ->
            val t =
                NodeOperationTracker(
                    nodeId = "ddl",
                    attempt = 1,
                    kind = OperationKind.STATEMENT,
                    destination = OperationDestination.NONE,
                    sampleIntervalMs = 5_000,
                    nanoTime = clock::nanos,
                    now = clock::instant,
                )
            t.enter(OperationPhase.EXECUTING)
            t.committed()
            t.finish(outcome).committed.shouldBeNull()
        }
    }
}
