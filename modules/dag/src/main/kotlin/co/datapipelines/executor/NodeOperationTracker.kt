package co.datapipelines.executor

import java.time.Instant
import java.util.EnumMap
import java.util.UUID

/**
 * The measured state of ONE node operation (149 §2/§4): the phase clock, the cumulative counts,
 * the emission rules that bound `node_progress` volume, and the seal.
 *
 * ## Recording versus emitting
 *
 * Every `record` here is a plain, lock-guarded write: the writers call it from a staging drain,
 * from a blocking JDBC loop, from the composition sink — places that must not suspend and must
 * not block on a reader. Emission is somebody else's job: the executor's pump asks
 * [sampleIfDue] on its tick and the node coroutine asks [finish] once. Both hand back an
 * immutable [OperationSnapshot]; nothing here touches the emitter.
 *
 * ## The rules that bound the volume
 *
 * A sample is DUE when (1) a phase was entered for the FIRST time — that sample is taken AT
 * the entry, so a `writing` interval shorter than the pump's tick is still seen on the wire
 * as `writing`, with its own observation instant — or (2) [sampleIntervalMs]
 * (`progress-sample-interval-seconds`) has elapsed since the last sample and something changed.
 * Per operation that is at most `phases + duration / interval` events plus the terminal one —
 * never per row, never per batch. First-entry
 * samples wait in a queue no longer than the number of phases until the pump collects them.
 *
 * ## The seal
 *
 * [finish] is the terminal sample and it is final: the first outcome stands, later observations
 * are counted in [dropped] and otherwise ignored, and [sampleIfDue] answers null forever. That is
 * what keeps an abandoned driver body (108 §A) from publishing progress or success after the
 * node — or the execution — has ended.
 */
class NodeOperationTracker(
    val nodeId: String,
    val attempt: Int,
    val kind: OperationKind,
    val destination: OperationDestination,
    private val sampleIntervalMs: Long,
    private val nanoTime: () -> Long = System::nanoTime,
    private val now: () -> Instant = Instant::now,
) : OperationObserver {
    private val lock = Any()
    private val startedNanos = nanoTime()
    private val startedAt = now()

    // ---- guarded by [lock] ----
    private val timings = EnumMap<OperationPhase, Long>(OperationPhase::class.java)
    private var currentPhase: OperationPhase? = null
    private var phaseEnteredNanos = startedNanos
    private var rowsFetched: Long? = null
    private var rowsWritten: Long? = null
    private var batches = 0L
    private var committedFlag = false
    private var rolledBackFlag = false
    private var child: UUID? = null
    private var sequence = 0
    private var lastSampleNanos = Long.MIN_VALUE
    private var changedSinceSample = true
    private var terminal: OperationSnapshot? = null
    private var droppedCount = 0

    /** First-entry samples taken at the entry instant, awaiting the pump (≤ one per phase). */
    private val pendingEntries = ArrayDeque<OperationSnapshot>()

    /** True once [finish] ran. */
    val isSealed: Boolean get() = synchronized(lock) { terminal != null }

    /** Observations that arrived after the seal — the late-callback evidence for tests. */
    val dropped: Int get() = synchronized(lock) { droppedCount }

    val wasCommitted: Boolean get() = synchronized(lock) { committedFlag }

    val wasRolledBack: Boolean get() = synchronized(lock) { rolledBackFlag }

    /** [OperationObserver.phase]: closes the open phase's interval, opens [phase]'s. */
    fun enter(phase: OperationPhase) {
        record {
            if (currentPhase == phase) return@record
            val t = nanoTime()
            closeOpenPhase(t)
            currentPhase = phase
            phaseEnteredNanos = t
            changedSinceSample = true
            if (timings[phase] == null) {
                timings[phase] = 0L
                // Sampled NOW, not at the next tick: the observation instant is the entry.
                pendingEntries.addLast(take(t, OperationState.of(phase), committed = null, rolledBack = null))
            }
        }
    }

    override fun phase(phase: OperationPhase) = enter(phase)

    override fun fetched(rows: Long) {
        record {
            rowsFetched = (rowsFetched ?: 0L) + rows
            changedSinceSample = true
        }
    }

    override fun written(rows: Long) {
        record {
            rowsWritten = (rowsWritten ?: 0L) + rows
            batches++
            changedSinceSample = true
        }
    }

    override fun committed() {
        record { committedFlag = true }
    }

    override fun rolledBack() {
        record { rolledBackFlag = true }
    }

    fun childExecution(id: UUID) {
        record {
            child = id
            changedSinceSample = true
        }
    }

    /**
     * The pump's question: the oldest first-entry sample still waiting, else a fresh snapshot
     * when one is due by the periodic rule, else null. Never a terminal sample — [finish] is
     * the only source of those. Called again until it answers null, it drains the queue in
     * sequence order.
     */
    fun sampleIfDue(): OperationSnapshot? =
        synchronized(lock) {
            // Sealed: whatever was queued went out with the terminal flush (or never will).
            if (terminal != null) return null
            pendingEntries.removeFirstOrNull()?.let { return it }
            val t = nanoTime()
            val due = changedSinceSample && (sequence == 0 || t - lastSampleNanos >= sampleIntervalMs * NANOS_PER_MILLI)
            if (!due) return null
            take(t, currentPhase?.let { OperationState.of(it) } ?: OperationState.CONNECTING, committed = null, rolledBack = null)
        }

    /** The first-entry samples not yet collected — the terminal flush emits them before [finish]. */
    fun drainPending(): List<OperationSnapshot> =
        synchronized(lock) {
            val drained = pendingEntries.toList()
            pendingEntries.clear()
            drained
        }

    /**
     * The terminal sample — exactly one per tracker. The first call decides; every later call
     * returns that same snapshot unchanged.
     *
     * `committed` is COMMIT EVIDENCE, decided here from the writer's own reports and never from
     * [outcome] (review R149-1): a node can fail AFTER its write became durable — a connection
     * close that throws once `commit()` returned, a cancellation that lands between the commit
     * and the node's completion — and a failed node is not evidence that its side effects were
     * undone. So:
     *
     * - `true` — the writer reported [committed]: the write is durable, whatever the node did next;
     * - `false` — the writer reported [rolledBack] (or the caller confirms an undo): the write is
     *   known to be gone;
     * - absent — neither was observed: the outcome is UNKNOWN (a driver that never confirmed
     *   the commit before the node's deadline, a cancellation before the commit whose implicit
     *   rollback nobody witnessed) — and absent for a destination with nothing to commit.
     *
     * A completed operation whose writer never reported a commit is also absent, not `true`:
     * every production writer reports, so that case is a defect, and "unknown" is the honest
     * label for it.
     *
     * @param rolledBack a caller-confirmed undo (a partial table dropped after the writer's own
     *   report could no longer reach this tracker); OR-ed with the writer's report.
     */
    fun finish(
        outcome: OperationOutcome,
        rolledBack: Boolean = false,
    ): OperationSnapshot =
        synchronized(lock) {
            terminal?.let { return it }
            val t = nanoTime()
            closeOpenPhase(t)
            currentPhase = null
            val undone = rolledBack || rolledBackFlag
            val committed =
                when {
                    destination.kind == OperationDestination.Kind.NONE -> null
                    committedFlag -> true
                    undone -> false
                    else -> null
                }
            val snapshot =
                take(
                    t,
                    OperationState.of(outcome),
                    committed = committed,
                    rolledBack = undone.takeIf { it },
                )
            terminal = snapshot
            snapshot
        }

    // ------------------------------------------------------------ internals (under the lock)

    private inline fun record(body: () -> Unit) {
        synchronized(lock) {
            if (terminal != null) {
                droppedCount++
                return
            }
            body()
        }
    }

    private fun closeOpenPhase(t: Long) {
        val open = currentPhase ?: return
        timings[open] = (timings[open] ?: 0L) + (t - phaseEnteredNanos)
        phaseEnteredNanos = t
    }

    private fun take(
        t: Long,
        state: OperationState,
        committed: Boolean?,
        rolledBack: Boolean?,
    ): OperationSnapshot {
        sequence++
        lastSampleNanos = t
        changedSinceSample = false
        val observed = EnumMap(timings)
        currentPhase?.let { open -> observed[open] = (observed[open] ?: 0L) + (t - phaseEnteredNanos) }
        return OperationSnapshot(
            nodeId = nodeId,
            attempt = attempt,
            sequence = sequence,
            kind = kind,
            destination = destination,
            state = state,
            startedAt = startedAt,
            observedAt = now(),
            elapsedMs = (t - startedNanos) / NANOS_PER_MILLI,
            timingsMs = observed.mapValues { (_, nanos) -> nanos / NANOS_PER_MILLI },
            rowsFetched = rowsFetched,
            rowsWritten = rowsWritten,
            batchesWritten = batches,
            committed = committed,
            rolledBack = rolledBack,
            childExecutionId = child,
        )
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
