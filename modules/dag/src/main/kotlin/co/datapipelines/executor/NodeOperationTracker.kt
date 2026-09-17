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
 * A sample is DUE when (1) none has been taken yet, (2) a phase was entered for the first time
 * since the last sample, or (3) [sampleIntervalMs] has elapsed since the last sample and
 * something changed. Per operation that is at most `1 + phases + duration / interval` events
 * plus the terminal one — never per row, never per batch.
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
    private var newPhaseSinceSample = false
    private var terminal: OperationSnapshot? = null
    private var droppedCount = 0

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
            if (timings[phase] == null) {
                timings[phase] = 0L
                newPhaseSinceSample = true
            }
            changedSinceSample = true
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
     * The pump's question: a snapshot when one is due by the rules above, else null. Never a
     * terminal sample — [finish] is the only source of those.
     */
    fun sampleIfDue(): OperationSnapshot? =
        synchronized(lock) {
            if (terminal != null) return null
            val t = nanoTime()
            val due =
                sequence == 0 ||
                    newPhaseSinceSample ||
                    (changedSinceSample && t - lastSampleNanos >= sampleIntervalMs * NANOS_PER_MILLI)
            if (!due) return null
            take(t, currentPhase?.let { OperationState.of(it) } ?: OperationState.CONNECTING, committed = null, rolledBack = null)
        }

    /**
     * The terminal sample — exactly one per tracker. The first call decides; every later call
     * returns that same snapshot unchanged.
     *
     * @param committed null when the destination has nothing to commit (DDL, a child with no
     *   output); otherwise whether the destination's write is durable now.
     */
    fun finish(
        outcome: OperationOutcome,
        committed: Boolean?,
        rolledBack: Boolean = false,
    ): OperationSnapshot =
        synchronized(lock) {
            terminal?.let { return it }
            val t = nanoTime()
            closeOpenPhase(t)
            currentPhase = null
            val snapshot =
                take(
                    t,
                    OperationState.of(outcome),
                    committed = committed,
                    rolledBack = (rolledBack || rolledBackFlag).takeIf { it },
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
        newPhaseSinceSample = false
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
