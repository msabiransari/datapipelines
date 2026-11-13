package co.datapipelines.application.dashboards

import co.datapipelines.executor.ExecutionSlots
import co.datapipelines.executor.SlotReservation
import co.datapipelines.visualization.DashboardRuntimeConfig
import co.datapipelines.visualization.DashboardRuntimeKey
import kotlinx.coroutines.delay
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Admission of a dashboard refresh (D53; the implementation spec's §9.4, §18 premises 2 and 3): whether THIS instance
 * has room for it, decided BEFORE a row is written or a stream opened, so a refusal is a clean `429` that costs the
 * viewer nothing and leaves nothing behind.
 *
 * A refresh needs three things, all or none:
 * 1. one of the workspace's `max-concurrent-refreshes-per-workspace` refresh places;
 * 2. `n` of the instance's `max-concurrent-dashboard-executions-per-instance` dashboard execution places, where `n` is
 *    the plan's distinct executions;
 * 3. `n` INSTANCE-wide execution slots ([ExecutionSlots.acquireInstanceOnly]) — and NO per-user slot, so a refresh never
 *    starves the viewer's own runs (D53).
 *
 * The wait for all three is bounded by ONE deadline, `max-wait-seconds`; nothing queues past it. The two counters are
 * **JVM-local** like [ExecutionSlots] (the parameter-engine record's 050/R2 shape): two API instances each admit their
 * own; a cluster-wide count is a later decision, stated in configuration.md §3.34.
 *
 * The counters are held until the refresh ENDS ([Admission.close]) rather than released execution by execution: a
 * refresh's executions all finish before the refresh does, so this over-holds by at most the tail of the slowest source.
 *
 * ## Busy or never (#762)
 * A refresh needing more executions than a CEILING of this instance — `max-executions-per-refresh`, or the executor's
 * `max-concurrent-executions-per-instance` ([ExecutionSlots.instanceCeiling]) — can never be admitted however long it
 * waits, so it is answered [AdmissionResult.NeverAdmissible] BEFORE the wait, naming the smaller ceiling and its key.
 * Waiting it out answered "try again shortly" after `max-wait-seconds`, for ever, and sent operators to the wrong key.
 * The dashboard execution cap is not a third ceiling here: `DashboardRuntimeConfig` refuses one below
 * `max-executions-per-refresh`, so it never binds first.
 */
class RefreshAdmission(
    private val slots: ExecutionSlots,
    private val config: DashboardRuntimeConfig,
) {
    private val perWorkspace = ConcurrentHashMap<UUID, Int>()
    private var dashboardExecutions = 0
    private val lock = Any()

    /** Refreshes running in [workspaceId] on this instance — the leak assertion surface. */
    fun refreshesIn(workspaceId: UUID): Int = synchronized(lock) { perWorkspace[workspaceId] ?: 0 }

    /** Refreshes admitted and not yet ended on this instance, all workspaces — the `datapipelines.dashboard.refreshes.active` gauge. */
    val activeRefreshes: Int get() = synchronized(lock) { perWorkspace.values.sum() }

    /** Dashboard executions reserved on this instance. */
    val executionsReserved: Int get() = synchronized(lock) { dashboardExecutions }

    /**
     * Admits a refresh of [executions] distinct executions in [workspaceId]: [AdmissionResult.Admitted] holding every
     * place, [AdmissionResult.NeverAdmissible] at once when a ceiling of this instance is below [executions], or
     * [AdmissionResult.Busy] when the instance stays full for `max-wait-seconds`. Nothing is held after a refusal.
     */
    suspend fun admit(
        workspaceId: UUID,
        executions: Int,
    ): AdmissionResult {
        require(executions >= 0) { "executions must not be negative, was $executions" }
        neverAdmissible(executions)?.let { return it }
        val deadline = System.nanoTime() + Duration.ofSeconds(config.maxWaitSeconds.toLong()).toNanos()
        while (true) {
            if (takeCounters(workspaceId, executions)) {
                val reservation = reserve(executions, deadline)
                if (reservation != null || executions == 0) {
                    return AdmissionResult.Admitted(Admission(reservation) { giveCounters(workspaceId, executions) })
                }
                giveCounters(workspaceId, executions)
            }
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) return AdmissionResult.Busy
            delay(minOf(POLL_MILLIS, Duration.ofNanos(remainingNanos).toMillis().coerceAtLeast(1)))
        }
    }

    /**
     * The smaller ceiling when [executions] exceeds it, else null. The per-refresh bound comes first so a tie names
     * it: the document's own bound, which the validator refuses at save (a document over it was saved under an older,
     * larger bound or on another instance).
     */
    private fun neverAdmissible(executions: Int): AdmissionResult.NeverAdmissible? {
        val ceilings =
            listOf(
                config.maxExecutionsPerRefresh to DashboardRuntimeKey.MAX_EXECUTIONS_PER_REFRESH.path,
                slots.instanceCeiling to ExecutionSlots.INSTANCE_CEILING_KEY,
            )
        val (ceiling, key) = ceilings.minBy { it.first }
        return if (executions > ceiling) AdmissionResult.NeverAdmissible(executions, ceiling, key) else null
    }

    private suspend fun reserve(
        executions: Int,
        deadline: Long,
    ): SlotReservation? {
        if (executions == 0) return null
        val remaining = Duration.ofNanos((deadline - System.nanoTime()).coerceAtLeast(0))
        return slots.acquireInstanceOnly(executions, remaining)
    }

    private fun takeCounters(
        workspaceId: UUID,
        executions: Int,
    ): Boolean =
        synchronized(lock) {
            val held = perWorkspace[workspaceId] ?: 0
            if (held >= config.maxConcurrentRefreshesPerWorkspace) return false
            if (dashboardExecutions + executions > config.maxConcurrentDashboardExecutionsPerInstance) return false
            perWorkspace[workspaceId] = held + 1
            dashboardExecutions += executions
            true
        }

    private fun giveCounters(
        workspaceId: UUID,
        executions: Int,
    ) {
        synchronized(lock) {
            val held = perWorkspace[workspaceId] ?: 0
            if (held <= 1) perWorkspace.remove(workspaceId) else perWorkspace[workspaceId] = held - 1
            dashboardExecutions -= executions
        }
    }

    private companion object {
        const val POLL_MILLIS = 25L
    }
}

/** What [RefreshAdmission.admit] answers (#762): admitted, busy now, or never on this instance. */
sealed interface AdmissionResult {
    /** Every place is held; [admission] gives them back when the refresh ends. */
    data class Admitted(
        val admission: Admission,
    ) : AdmissionResult

    /** The instance stayed full for `max-wait-seconds`; nothing is held, and a later attempt may be admitted. */
    data object Busy : AdmissionResult

    /**
     * [executions] exceeds [ceiling], the value of configuration key [key] on this instance: no wait or retry can admit
     * it until an operator raises that key (or the dashboard needs fewer executions). Decided before any wait.
     */
    data class NeverAdmissible(
        val executions: Int,
        val ceiling: Int,
        val key: String,
    ) : AdmissionResult
}

/**
 * An admitted refresh's places. [reservation] is null for a refresh that runs no execution. [close] returns the counters
 * and the reserved slots never handed out; idempotent.
 */
class Admission internal constructor(
    val reservation: SlotReservation?,
    private val release: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        reservation?.close()
        release()
    }
}
