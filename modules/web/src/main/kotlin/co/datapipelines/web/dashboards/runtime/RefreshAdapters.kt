package co.datapipelines.web.dashboards.runtime

import co.datapipelines.application.dashboards.ExecutionCanceller
import co.datapipelines.application.dashboards.RefreshAudit
import co.datapipelines.application.dashboards.RefreshJob
import co.datapipelines.application.dashboards.RefreshLedger
import co.datapipelines.application.dashboards.RefreshResult
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.executor.AbortReason
import co.datapipelines.executor.ExecutionCancellationService
import co.datapipelines.visualization.DashboardRefreshRepository
import co.datapipelines.visualization.RefreshExecutionLink
import co.datapipelines.visualization.RefreshStatus
import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.UUID

/** The engine's [RefreshLedger] over `dashboard_refreshes` / `dashboard_refresh_executions` (V43). */
internal class RepositoryRefreshLedger(
    private val refreshes: DashboardRefreshRepository,
) : RefreshLedger {
    override fun link(
        refreshId: UUID,
        source: String,
        executionId: UUID,
        shared: Boolean,
    ) = refreshes.link(RefreshExecutionLink(refreshId, source, executionId, shared))

    override fun finish(
        refreshId: UUID,
        status: RefreshStatus,
        summary: ObjectNode,
    ): Boolean = refreshes.finish(refreshId, status, summary.toString())
}

/**
 * The ONE `dashboard.refresh` audit row per refresh (the implementation spec's §18 premise 1): AWAITED — audit rows are
 * authorization inputs (#266: `McpCallAudit`, `EndpointServeAudit` read them back), so this goes through
 * [AuditEventSink.log], never the fire-and-forget batching path — and written at the refresh's END, by the engine, under
 * `NonCancellable`. It names the refresh, the dashboard and version, the principal, the scope, the outcome and the
 * execution ids; it never carries a selection, a row or a driver message (the sink is redaction-bound).
 */
internal class AuditingRefreshAudit(
    private val audit: AuditEventSink,
    private val refreshes: DashboardRefreshRepository,
) : RefreshAudit {
    override fun record(
        job: RefreshJob,
        result: RefreshResult,
    ) {
        audit.log(
            event = DashboardAuditEvents.REFRESH,
            userId = job.userId,
            details =
                mapOf(
                    "refresh_id" to job.refreshId.toString(),
                    "dashboard_id" to job.dashboardId.toString(),
                    "dashboard_version" to job.dashboardVersion,
                    "workspace_id" to job.workspaceId.toString(),
                    "instance_id" to job.instanceId.toString(),
                    "scope" to job.scope.wire,
                    "status" to result.status.name,
                    "targets" to result.targets.mapValues { (_, outcome) -> outcome.wire },
                    "execution_ids" to refreshes.linksOf(job.refreshId).map { it.executionId.toString() }.distinct(),
                ),
        )
    }
}

/** The engine's [ExecutionCanceller] over the executor's own cancel path (flag + local registry). */
internal class ServiceExecutionCanceller(
    private val cancellation: ExecutionCancellationService,
) : ExecutionCanceller {
    override fun cancel(executionId: UUID) {
        cancellation.cancel(executionId, AbortReason.CANCELLED)
    }
}
