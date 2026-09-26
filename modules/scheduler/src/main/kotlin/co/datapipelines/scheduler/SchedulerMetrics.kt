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

    companion object {
        const val OCCURRENCES = "datapipelines.scheduler.occurrences"
        const val RUNS_FINISHED = "datapipelines.scheduler.runs.finished"
        const val CAPACITY_RETRIES = "datapipelines.scheduler.capacity.retries"
    }
}
