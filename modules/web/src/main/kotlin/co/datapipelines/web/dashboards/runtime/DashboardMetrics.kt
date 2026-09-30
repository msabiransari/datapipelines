package co.datapipelines.web.dashboards.runtime

import co.datapipelines.application.dashboards.RefreshAdmission
import co.datapipelines.visualization.RefreshStatus
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration

/**
 * The dashboard runtime's instruments (observability.md §4.1) — tag values from closed sets only (§4.3): `status` is a
 * [RefreshStatus], `reason` one of [REASON_SATURATED] / [REASON_STREAM_LIMIT]. No dashboard, user or refresh id ever
 * becomes a tag.
 */
class DashboardMetrics(
    private val registry: MeterRegistry,
) {
    /** The `datapipelines.dashboard.refreshes.active` and `…executions.reserved` gauges, bound once to the live admission. */
    fun bind(admission: RefreshAdmission) {
        Gauge
            .builder(REFRESHES_ACTIVE, admission) { it.activeRefreshes.toDouble() }
            .description("Dashboard refreshes admitted and not yet ended on this instance")
            .register(registry)
        Gauge
            .builder(EXECUTIONS_RESERVED, admission) { it.executionsReserved.toDouble() }
            .description("Dashboard executions reserved by the refreshes admitted on this instance")
            .register(registry)
    }

    /** One refresh ended — its terminal status and wall-clock time. */
    fun refreshEnded(
        status: RefreshStatus,
        duration: Duration,
    ) {
        registry.counter(REFRESHES, "status", status.name).increment()
        registry.timer(REFRESH_DURATION, "status", status.name).record(duration)
    }

    /** A refresh refused before it started: `saturated` (429, no room) or `stream_limit` (the per-user stream cap). */
    fun refused(reason: String) {
        registry.counter(REFRESHES_REFUSED, "reason", reason).increment()
    }

    companion object {
        const val REFRESHES = "datapipelines.dashboard.refreshes"
        const val REFRESH_DURATION = "datapipelines.dashboard.refresh.duration"
        const val REFRESHES_REFUSED = "datapipelines.dashboard.refresh.refused"
        const val REFRESHES_ACTIVE = "datapipelines.dashboard.refreshes.active"
        const val EXECUTIONS_RESERVED = "datapipelines.dashboard.executions.reserved"
        const val REASON_SATURATED = "saturated"
        const val REASON_STREAM_LIMIT = "stream_limit"
    }
}
