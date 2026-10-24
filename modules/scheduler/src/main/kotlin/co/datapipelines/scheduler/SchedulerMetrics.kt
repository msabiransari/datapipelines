package co.datapipelines.scheduler

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry

/**
 * The scheduler's counters (observability.md §4.1). The in-flight gauge is the capacity gate's
 * (`web`), which is where the held slots are.
 *
 * - `datapipelines.scheduler.occurrences` — occurrences the dispatcher recorded, by `outcome`
 *   (`queued`, `catch_up`, `missed`, `overlap`).
 * - `datapipelines.scheduler.runs.finished` — runs that reached a terminal state, by `state`
 *   (R6's table; `unknown` counted when it is reached, again if a real terminal follows).
 * - `datapipelines.scheduler.capacity.retries` — admissions refused for capacity and retried
 *   within the lateness window (R4).
 * - `datapipelines.scheduler.notifications.sent` / `.failed` / `.uncertain` (#442b) — deliveries
 *   the sender settled: accepted by the relay, finally failed (refused, out of tries, or off at
 *   send), or possibly sent. A retry that stays `pending` is not counted.
 */
class SchedulerMetrics(
    private val registry: MeterRegistry = SimpleMeterRegistry(),
) {
    fun occurrence(outcome: String) {
        registry.counter(OCCURRENCES, "outcome", outcome).increment()
    }

    fun runFinished(state: RunState) {
        registry.counter(RUNS_FINISHED, "state", state.wire).increment()
    }

    fun capacityRetry() {
        registry.counter(CAPACITY_RETRIES).increment()
    }

    fun notificationSettled(state: DeliveryState) {
        when (state) {
            DeliveryState.ACCEPTED -> registry.counter(NOTIFICATIONS_SENT).increment()
            DeliveryState.FAILED -> registry.counter(NOTIFICATIONS_FAILED).increment()
            DeliveryState.UNCERTAIN -> registry.counter(NOTIFICATIONS_UNCERTAIN).increment()
            DeliveryState.PENDING -> Unit
        }
    }

    companion object {
        const val OCCURRENCES = "datapipelines.scheduler.occurrences"
        const val RUNS_FINISHED = "datapipelines.scheduler.runs.finished"
        const val CAPACITY_RETRIES = "datapipelines.scheduler.capacity.retries"
        const val NOTIFICATIONS_SENT = "datapipelines.scheduler.notifications.sent"
        const val NOTIFICATIONS_FAILED = "datapipelines.scheduler.notifications.failed"
        const val NOTIFICATIONS_UNCERTAIN = "datapipelines.scheduler.notifications.uncertain"
    }
}
