package co.datapipelines.web.dashboards.runtime

import co.datapipelines.application.dashboards.Admission
import co.datapipelines.application.dashboards.InvocationSpec
import co.datapipelines.application.dashboards.RefreshAdmission
import co.datapipelines.application.dashboards.RefreshEngine
import co.datapipelines.application.dashboards.RefreshJob
import co.datapipelines.application.dashboards.RefreshPlan
import co.datapipelines.application.dashboards.RefreshPlanner
import co.datapipelines.application.dashboards.RefreshPorts
import co.datapipelines.application.dashboards.TargetSpec
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Permission
import co.datapipelines.executor.AbortReason
import co.datapipelines.executor.ExecutionCancellationService
import co.datapipelines.executor.RefreshAbortFlags
import co.datapipelines.parameters.EvaluateResponseJson
import co.datapipelines.parameters.ParameterEvaluator
import co.datapipelines.visualization.DashboardErrorCodes
import co.datapipelines.visualization.DashboardRefreshRepository
import co.datapipelines.visualization.DashboardRuntimeConfig
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.RefreshRecord
import co.datapipelines.visualization.RefreshStatus
import co.datapipelines.web.api.ApiErrors
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.api.PagedData
import co.datapipelines.web.api.Pagination
import co.datapipelines.web.pipelines.executedByKeyKind
import co.datapipelines.web.visualizations.ArtifactFamily
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The dashboard runtime's six operations (the implementation spec's §6.2, §8, §9) — what a controller calls after the
 * interceptor has judged `dashboard.execute`. Preflight lives here (resolve, configuration, selections, plan,
 * admission, the row); the refresh itself is [RefreshEngine]'s.
 *
 * ## Order of a refresh, and why (spec §18 premise 3)
 * 1. shape and stream cap; 2. resolve the served dashboard through the caller's lens (a hidden one is the family's 404)
 * and check `configuration_id`; 3. evaluate the selections (an invalid selection is a 400 before anything is held);
 * 4. plan; 5. **admission** — the slots come first; 6. only then the `RUNNING` row; 7. the stream and the engine. A
 * refresh refused at 5 writes nothing, and a failure between 5 and 7 gives every place back.
 *
 * ## The delegated act (D50)
 * The refresh's sources, its set evaluation and its transforms run WITHOUT consulting the principal's
 * `pipeline.execute`, `parameter_set.evaluate` or `template.evaluate` — `dashboard.execute` was the one authorization
 * event. Everything that IS checked here is isolation: the workspace, the lens, ownership of a refresh, and the
 * read-only rule on every source.
 */
@Suppress("LongParameterList", "TooManyFunctions") // the runtime's ports ARE its constructor; one method per route
class DashboardRuntime internal constructor(
    private val resolver: DashboardRuntimeResolver,
    private val evaluator: ParameterEvaluator,
    private val refreshes: DashboardRefreshRepository,
    private val admission: RefreshAdmission,
    private val engine: RefreshEngine,
    private val streams: RefreshStreamRegistry,
    private val streamAuthority: RefreshStreamAuthority,
    private val abortSignal: RefreshAbortSignal,
    private val abortFlags: RefreshAbortFlags,
    private val cancellation: ExecutionCancellationService,
    private val audit: AuditEventSink,
    private val dashboards: DashboardService,
    private val lens: PromoterLens,
    private val config: DashboardRuntimeConfig,
    private val scope: CoroutineScope,
    private val canceller: ServiceExecutionCanceller,
    private val metrics: DashboardMetrics,
    private val planner: RefreshPlanner = RefreshPlanner(),
    private val revisions: ParameterRevisions = ParameterRevisions(),
) {
    private val log = LoggerFactory.getLogger(DashboardRuntime::class.java)

    // ---- GET /runtime/config ------------------------------------------------------------------------------

    fun config(
        principal: AuthenticatedPrincipal,
        id: UUID,
    ): ObjectNode = RuntimeViews.config(resolve(principal, id), config)

    // ---- POST /runtime/parameters -------------------------------------------------------------------------

    internal fun parameters(
        principal: AuthenticatedPrincipal,
        id: UUID,
        request: ParametersRequest,
    ): ObjectNode {
        val resolved = resolve(principal, id)
        requireCurrent(resolved, request.configurationId)
        val revision = revisions.next(request.instanceId)
        val set = resolved.set ?: return emptyEvaluation(revision)
        val response = evaluator.evaluateBlocking(principal.requireWorkspace().id, set, request.selections)
        return RuntimeViews.parameters(response, resolved.served.body, revision)
    }

    /** A dashboard without a parameter set has nothing to evaluate: the answer has the response's shape and is empty. */
    private fun emptyEvaluation(revision: Int): ObjectNode =
        JsonNodeFactory.instance.objectNode().also {
            it.put("valid", true)
            it.putObject("values")
            it.putArray("parameters")
            it.putObject("overrides_applied")
            it.putArray("parents")
            it.put("parameter_revision", revision)
        }

    // ---- POST /runtime/visualizations ---------------------------------------------------------------------

    @Suppress("ThrowsCount") // the preflight is a sequence of refusals, one per reason, in the spec's order
    internal fun startRefresh(
        principal: AuthenticatedPrincipal,
        id: UUID,
        request: RefreshRequest,
    ): SseEmitter {
        if (streams.atStreamLimit(principal.userId)) {
            metrics.refused(DashboardMetrics.REASON_STREAM_LIMIT)
            throw ApiErrors.streamLimitExceeded(streams.maxStreamsPerUser)
        }
        val workspaceId = principal.requireWorkspace().id
        if (refreshes.find(workspaceId, request.refreshId) != null) throw RuntimeRequests.bad("refresh_id", RuntimeRequests.REUSED)
        val resolved = resolve(principal, id)
        requireCurrent(resolved, request.configurationId)
        val values = evaluatedValues(principal, resolved, request)
        val plan = planner.plan(resolved.served.body, request.scope, request.targets, values)
        val admitted = admit(workspaceId, plan)
        var opened = false
        try {
            val job = job(principal, resolved, request, plan, admitted)
            val started = refreshes.insertRunning(recordOf(principal, resolved, request, job))
            if (!started) throw RuntimeRequests.bad("refresh_id", RuntimeRequests.REUSED)
            return open(principal, job, admitted).also { opened = true }
        } finally {
            if (!opened) admitted.close() // nothing was started: every place taken comes back
        }
    }

    private fun evaluatedValues(
        principal: AuthenticatedPrincipal,
        resolved: ResolvedDashboard,
        request: RefreshRequest,
    ): Map<String, JsonNode?> {
        val set = resolved.set ?: return emptyMap()
        val response = evaluator.evaluateBlocking(principal.requireWorkspace().id, set, request.selections)
        if (!response.valid) {
            val invalid = response.parameters.filter { it.state.errors.isNotEmpty() }.map { it.definition.name }
            throw ApiException(
                DashboardErrorCodes.BODY_INVALID,
                "The selections are not valid for this dashboard's parameters.",
                mapOf("path" to "selections", "reason" to "selections_invalid", "parameters" to invalid.take(MAX_ECHOED)),
            )
        }
        return response.parameters.associate { it.definition.name to EvaluateResponseJson.encode(it.definition, it.state.value) }
    }

    private fun admit(
        workspaceId: UUID,
        plan: RefreshPlan,
    ): Admission =
        runBlocking { admission.admit(workspaceId, plan.invocations.size) }
            ?: throw refused()

    private fun refused(): ApiException {
        metrics.refused(DashboardMetrics.REASON_SATURATED)
        return ApiException(
            DashboardErrorCodes.REFRESH_SATURATED,
            "This instance has no room for another refresh right now. Try again shortly.",
            mapOf("retry_after_seconds" to retryAfterSeconds()),
        )
    }

    /** What `Retry-After` says: as long as a refresh waits for room, and at least a second. */
    internal fun retryAfterSeconds(): Int = maxOf(config.maxWaitSeconds, 1)

    private fun job(
        principal: AuthenticatedPrincipal,
        resolved: ResolvedDashboard,
        request: RefreshRequest,
        plan: RefreshPlan,
        admitted: Admission,
    ): RefreshJob {
        val body = resolved.served.body
        return RefreshJob(
            refreshId = request.refreshId,
            workspaceId = principal.requireWorkspace().id,
            userId = principal.userId,
            executedByKeyKind = principal.executedByKeyKind(),
            dashboardId = resolved.served.record.id,
            dashboardVersion = resolved.served.detail.version,
            scope = request.scope,
            instanceId = request.instanceId,
            plan = plan,
            targets =
                body.visualizations.filter { it.name in plan.targets }.associate { occurrence ->
                    val pinned = resolved.visualizations.getValue(occurrence.name)
                    occurrence.name to
                        TargetSpec(
                            occurrence.name,
                            occurrence.visualization,
                            pinned.body,
                            occurrence.inputs.mapValues { it.value.source },
                            occurrence.timeoutSeconds,
                        )
                },
            invocations =
                plan.invocations.associate { invocation ->
                    val source = resolved.sources.getValue(invocation.sources.first())
                    invocation.id to
                        InvocationSpec(invocation.id, source.record.id, source.version, source.executable.pipeline, invocation.parameters)
                },
            deadlineSeconds = RuntimeViews.refreshSeconds(body, config),
            reservation = admitted.reservation,
            startedAt = Instant.now(),
        )
    }

    private fun recordOf(
        principal: AuthenticatedPrincipal,
        resolved: ResolvedDashboard,
        request: RefreshRequest,
        job: RefreshJob,
    ) = RefreshRecord(
        id = request.refreshId,
        dashboardId = resolved.served.record.id,
        dashboardVersion = resolved.served.detail.version,
        workspaceId = principal.requireWorkspace().id,
        instanceId = request.instanceId,
        principalUserId = principal.userId,
        principalKeyId = null,
        scope = request.scope,
        targetsJson =
            JsonNodeFactory.instance
                .arrayNode()
                .also { array -> job.plan.targets.forEach(array::add) }
                .toString(),
        parameterRevision = request.parameterRevision,
        selectionsJson = request.selectionsJson,
        status = RefreshStatus.RUNNING,
        startedAt = job.startedAt,
        finishedAt = null,
        summaryJson = "{}",
    )

    /** Opens the stream, registers the abort places and starts the engine; the engine ends the row whatever happens. */
    private fun open(
        principal: AuthenticatedPrincipal,
        job: RefreshJob,
        admitted: Admission,
    ): SseEmitter {
        val stream = streams.open(job.refreshId, principal, streamAuthority)
        abortSignal.register(job.refreshId)
        val ports =
            RefreshPorts(
                events = stream,
                ledger = RepositoryRefreshLedger(refreshes),
                audit = AuditingRefreshAudit(audit, refreshes),
                abort = abortSignal,
                canceller = canceller,
            )
        scope.launch {
            val began = System.nanoTime()
            try {
                val result = engine.run(job, ports)
                metrics.refreshEnded(result.status, Duration.ofNanos(System.nanoTime() - began))
            } catch (_: CancellationException) {
                // The process is stopping: the engine has already closed the row under NonCancellable.
            } finally {
                admitted.close()
                abortSignal.forget(job.refreshId)
            }
        }
        return stream.emitter
    }

    // ---- POST /runtime/refreshes/{refresh_id}/abort -------------------------------------------------------

    /**
     * Aborts a RUNNING refresh the caller owns (their principal AND their client instance) — or any, with
     * `execution.cancel_all`. Answers without waiting: the flag is written for the owning instance, the local trigger is
     * pulled when this IS the owner, and every execution the refresh has started is cancelled through the executor's own
     * path. Anything else — no such refresh, another dashboard's, someone else's, one that already finished — is the
     * same `dashboard.refresh.not_found`, so an id proves nothing about a refresh the caller may not touch.
     */
    internal fun abort(
        principal: AuthenticatedPrincipal,
        id: UUID,
        refreshId: UUID,
        request: AbortRequest,
    ) {
        val record = refreshes.find(principal.requireWorkspace().id, refreshId)
        val mine = record != null && record.principalUserId == principal.userId && record.instanceId == request.instanceId
        val abortable = record != null && record.dashboardId == id && record.status == RefreshStatus.RUNNING
        if (!abortable || !(mine || principal.holds(Permission.EXECUTION_CANCEL_ALL))) throw refreshNotFound(refreshId)
        abortFlags.request(refreshId, ttlSeconds = (config.maxRefreshSeconds + FLAG_GRACE_SECONDS).toLong())
        if (abortSignal.owns(refreshId)) abortSignal.triggerLocal(refreshId)
        refreshes.linksOf(refreshId).map { it.executionId }.distinct().forEach {
            runCatching { cancellation.cancel(it, AbortReason.CANCELLED) }
                .onFailure { e ->
                    log.warn("event=dashboard.refresh_abort_cancel_failed refresh_id={} error={}", refreshId, e.javaClass.simpleName)
                }
        }
    }

    // ---- GET /refreshes, GET /refreshes/{refresh_id} ------------------------------------------------------

    fun listRefreshes(
        principal: AuthenticatedPrincipal,
        id: UUID,
        offset: Int?,
        limit: Int?,
    ): PagedData<ObjectNode> {
        val workspaceId = principal.requireWorkspace().id
        requireVisible(principal, id)
        val page = Pagination.clampOffset(offset)
        val size = Pagination.clampLimit(limit)
        val owner = principal.userId.takeUnless { principal.holds(Permission.EXECUTION_READ_ALL) }
        val items =
            refreshes.list(workspaceId, id, owner, size, page).map {
                RuntimeViews.refresh(it, null, showExecutions = false)
            }
        return PagedData(items, Pagination.of(page, size, refreshes.count(workspaceId, id, owner), items.size))
    }

    fun getRefresh(
        principal: AuthenticatedPrincipal,
        id: UUID,
        refreshId: UUID,
    ): ObjectNode {
        val workspaceId = principal.requireWorkspace().id
        requireVisible(principal, id)
        val record = refreshes.find(workspaceId, refreshId)?.takeIf { it.dashboardId == id } ?: throw refreshNotFound(refreshId)
        if (record.principalUserId != principal.userId && !principal.holds(Permission.EXECUTION_READ_ALL)) throw refreshNotFound(refreshId)
        val showExecutions = principal.holds(Permission.EXECUTION_READ)
        return RuntimeViews.refresh(record, if (showExecutions) refreshes.linksOf(refreshId) else null, showExecutions)
    }

    // ---- shared --------------------------------------------------------------------------------------------

    private fun resolve(
        principal: AuthenticatedPrincipal,
        id: UUID,
    ): ResolvedDashboard = resolver.resolve(principal.requireWorkspace().id, lens.viewFor(principal).dashboards, id)

    /** The dashboard exists for the caller — the lens hides a dashboard from the list of its own refreshes too. */
    private fun requireVisible(
        principal: AuthenticatedPrincipal,
        id: UUID,
    ) {
        dashboards.findWorking(principal.requireWorkspace().id, lens.viewFor(principal).dashboards, id)
            ?: throw ArtifactFamily.DASHBOARD.notFound(id.toString())
    }

    private fun requireCurrent(
        resolved: ResolvedDashboard,
        presented: String,
    ) {
        if (presented != resolved.configurationId) {
            throw ApiException(
                DashboardErrorCodes.RUNTIME_CONFIGURATION_STALE,
                "This dashboard changed while it was open. Reload it and try again.",
                mapOf("configuration_id" to resolved.configurationId),
            )
        }
    }

    private fun refreshNotFound(refreshId: UUID) =
        ApiException(
            DashboardErrorCodes.REFRESH_NOT_FOUND,
            "Refresh '$refreshId' not found.",
            mapOf("refresh_id" to refreshId.toString()),
        )

    private companion object {
        const val MAX_ECHOED = 20

        /** How long past its refresh's longest deadline an abort flag may live — the poll's slack, no more. */
        const val FLAG_GRACE_SECONDS = 60
    }
}
