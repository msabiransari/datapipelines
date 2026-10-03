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
import co.datapipelines.executor.RefreshStartMarker
import co.datapipelines.executor.RefreshStartMarkers
import co.datapipelines.executor.StartMarkerRegistration
import co.datapipelines.parameters.EvaluateResponseJson
import co.datapipelines.parameters.EvaluationAttempt
import co.datapipelines.parameters.EvaluationCaller
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
    private val startMarkers: RefreshStartMarkers,
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
        version: Int? = null,
    ): ObjectNode = RuntimeViews.config(resolve(principal, id, version), config)

    // ---- POST /runtime/parameters -------------------------------------------------------------------------

    internal fun parameters(
        principal: AuthenticatedPrincipal,
        id: UUID,
        request: ParametersRequest,
        version: Int? = null,
    ): ObjectNode {
        val resolved = resolve(principal, id, version)
        requireCurrent(resolved, request.configurationId)
        val revision = revisions.next(request.instanceId)
        val set = resolved.set ?: return emptyEvaluation(revision)
        // No refresh exists on this route, so the record carries no correlation id (#376).
        val attempt = EvaluationAttempt.of(EvaluationCaller.DASHBOARD, principal.userId, principal.keyId)
        val response = evaluator.evaluateBlocking(principal.requireWorkspace().id, set, request.selections, attempt)
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
        version: Int? = null,
    ): SseEmitter {
        requireSessionForVersion(principal, version)
        if (streams.atStreamLimit(principal.userId)) {
            metrics.refused(DashboardMetrics.REASON_STREAM_LIMIT)
            throw ApiErrors.streamLimitExceeded(streams.maxStreamsPerUser)
        }
        val workspaceId = principal.requireWorkspace().id
        if (refreshes.find(workspaceId, request.refreshId) != null) throw RuntimeRequests.bad("refresh_id", RuntimeRequests.REUSED)
        val resolved = resolve(principal, id, version)
        requireCurrent(resolved, request.configurationId)
        registerStartMarker(principal, workspaceId, resolved, request)
        var opened = false
        var admitted: Admission? = null
        try {
            val values = evaluatedValues(principal, resolved, request)
            val plan = planner.plan(resolved.served.body, request.scope, request.targets, values)
            val granted = admit(workspaceId, plan)
            admitted = granted
            val job = job(principal, resolved, request, plan, granted)
            val started = refreshes.insertRunning(recordOf(principal, resolved, request, job))
            if (!started) throw RuntimeRequests.bad("refresh_id", RuntimeRequests.REUSED)
            return open(principal, job, granted).also { opened = true }
        } finally {
            // The window is closed: from here the row (or the refusal) is the only authority an abort consults, so
            // the in-flight start's marker is removed on EVERY exit (#356). A refresh that fails after an abort was
            // already recorded leaves its TTL'd flag behind — inert, the id is single-use.
            // Admission first: the place is the scarce thing, the marker is TTL'd — a fault in the store's clear
            // that is not a DataAccessException must not skip the close (356 merge follow-up).
            if (!opened) admitted?.close() // nothing was started: every place taken comes back
            startMarkers.clear(workspaceId, principal.userId, request.refreshId)
        }
    }

    /**
     * Marks this start as in flight so an abort arriving before the row is honoured (#356). A principal at the
     * marker bound — more starts in flight than their stream cap — is refused the saturated 429: the bound stays
     * exact, no start ever loses its own abort authorization to a newer one. A start of an id ALREADY in flight
     * (a replayed request; the row check above cannot see it, no row exists yet) is the reused-id 400: the first
     * start keeps its marker and its abort (356 merge follow-up).
     */
    private fun registerStartMarker(
        principal: AuthenticatedPrincipal,
        workspaceId: UUID,
        resolved: ResolvedDashboard,
        request: RefreshRequest,
    ) {
        val registration =
            startMarkers.register(
                RefreshStartMarker(
                    workspaceId = workspaceId,
                    refreshId = request.refreshId,
                    principalUserId = principal.userId,
                    instanceId = request.instanceId,
                    dashboardId = resolved.served.record.id,
                ),
                ttlSeconds = (config.maxRefreshSeconds + FLAG_GRACE_SECONDS).toLong(),
                perPrincipalLimit = streams.maxStreamsPerUser,
            )
        when (registration) {
            StartMarkerRegistration.REGISTERED -> Unit
            StartMarkerRegistration.AT_BOUND -> throw refused()
            StartMarkerRegistration.ALREADY_IN_FLIGHT -> throw RuntimeRequests.bad("refresh_id", RuntimeRequests.REUSED)
        }
    }

    private fun evaluatedValues(
        principal: AuthenticatedPrincipal,
        resolved: ResolvedDashboard,
        request: RefreshRequest,
    ): Map<String, JsonNode?> {
        val set = resolved.set ?: return emptyMap()
        // The refresh's own id is the record's correlation id (#376): the history row and the refresh row join on it.
        val attempt =
            EvaluationAttempt.of(
                EvaluationCaller.DASHBOARD,
                principal.userId,
                principal.keyId,
                correlationId = request.refreshId.toString(),
            )
        val response = evaluator.evaluateBlocking(principal.requireWorkspace().id, set, request.selections, attempt)
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
        // V43's CHECK wants exactly one: a session names its person, a key its credential (L5).
        principalUserId = principal.takeIf { it.keyId == null }?.userId,
        principalKeyId = principal.keyId,
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
     * path. An abort that arrives while the refresh is still STARTING — no row yet, but an in-flight start marker
     * matching the caller (same workspace and dashboard, their principal AND instance, or `execution.cancel_all`,
     * #356) — records the intent under the id the same way, and the engine ends the refresh ABORTED at job start.
     * Anything else — no such refresh, another dashboard's, someone else's, one that already finished — is the
     * same `dashboard.refresh.not_found`, so an id proves nothing about a refresh the caller may not touch.
     */
    internal fun abort(
        principal: AuthenticatedPrincipal,
        id: UUID,
        refreshId: UUID,
        request: AbortRequest,
        version: Int? = null,
    ) {
        requireSessionForVersion(principal, version)
        requireServedVersion(principal, id, version)
        val workspaceId = principal.requireWorkspace().id
        val record = refreshes.find(workspaceId, refreshId)
        val mine = record != null && owns(principal, record) && record.instanceId == request.instanceId
        val abortable = record != null && record.dashboardId == id && record.status == RefreshStatus.RUNNING
        when {
            abortable && (mine || principal.holds(Permission.EXECUTION_CANCEL_ALL)) -> {
                abortFlags.request(refreshId, ttlSeconds = abortFlagTtlSeconds())
                if (abortSignal.owns(refreshId)) abortSignal.triggerLocal(refreshId)
                refreshes.linksOf(refreshId).map { it.executionId }.distinct().forEach {
                    runCatching { cancellation.cancel(it, AbortReason.CANCELLED) }
                        .onFailure { e ->
                            log.warn(
                                "event=dashboard.refresh_abort_cancel_failed refresh_id={} error={}",
                                refreshId,
                                e.javaClass.simpleName,
                            )
                        }
                }
            }

            // No row (or not abortable): only a truly absent row can still be a start in flight — a finished
            // or foreign-dashboard row is 404 exactly as before, never rescued by a marker (#356).
            record == null && abortStarting(principal, id, workspaceId, refreshId, request) -> {
                abortFlags.request(refreshId, ttlSeconds = abortFlagTtlSeconds())
                // The row may have landed on THIS instance between the row read and the marker read: then the
                // stream is running here and the local trigger spares it the remote poll (356 merge follow-up).
                if (abortSignal.owns(refreshId)) abortSignal.triggerLocal(refreshId)
            }

            else -> {
                throw refreshNotFound(refreshId)
            }
        }
    }

    private fun abortFlagTtlSeconds(): Long = (config.maxRefreshSeconds + FLAG_GRACE_SECONDS).toLong()

    /** True when the caller's own start of this id is still in flight for this dashboard; the intent is then recorded. */
    private fun abortStarting(
        principal: AuthenticatedPrincipal,
        id: UUID,
        workspaceId: UUID,
        refreshId: UUID,
        request: AbortRequest,
    ): Boolean {
        val starting = startMarkers.find(workspaceId, refreshId)?.takeIf { it.dashboardId == id } ?: return false
        return principal.holds(Permission.EXECUTION_CANCEL_ALL) ||
            (starting.principalUserId == principal.userId && starting.instanceId == request.instanceId)
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
        val keyId = principal.keyId
        val readAll = principal.holds(Permission.EXECUTION_READ_ALL)
        // A key's own refreshes are ITS rows (`principal_key_id`); the identity id matches none of
        // them (V43's CHECK keeps exactly one non-null), so a key owner filter is the KEY — every
        // end user behind the key sees the key's budget's refreshes, which is the documented
        // one-key-one-budget shape (auth.md §7.7, dashboards.md §6.5).
        val ownerUserId = principal.userId.takeUnless { readAll || keyId != null }
        val ownerKeyId = keyId.takeUnless { readAll }
        val items =
            refreshes.list(workspaceId, id, ownerUserId, ownerKeyId, size, page).map {
                RuntimeViews.refresh(it, null, showExecutions = false)
            }
        return PagedData(items, Pagination.of(page, size, refreshes.count(workspaceId, id, ownerUserId, ownerKeyId), items.size))
    }

    fun getRefresh(
        principal: AuthenticatedPrincipal,
        id: UUID,
        refreshId: UUID,
    ): ObjectNode {
        val workspaceId = principal.requireWorkspace().id
        requireVisible(principal, id)
        val record = refreshes.find(workspaceId, refreshId)?.takeIf { it.dashboardId == id } ?: throw refreshNotFound(refreshId)
        if (!owns(principal, record) && !principal.holds(Permission.EXECUTION_READ_ALL)) throw refreshNotFound(refreshId)
        val showExecutions = principal.holds(Permission.EXECUTION_READ)
        return RuntimeViews.refresh(record, if (showExecutions) refreshes.linksOf(refreshId) else null, showExecutions)
    }

    // ---- shared --------------------------------------------------------------------------------------------

    /**
     * Whose refresh row is this caller's: a session owns by its person (`principal_user_id`), a
     * key by its credential (`principal_key_id` — V43's CHECK keeps exactly one non-null, so a
     * key's identity id never appears in the user column). The abort's per-instance rule and the
     * refreshes reads both ask here, so a host relaying the runtime routes cannot let one end
     * user of a shared key abort or read another's refresh — but two users of the SAME key DO
     * share the key's rows (§6.5's one-key-one-budget note).
     */
    private fun owns(
        principal: AuthenticatedPrincipal,
        record: RefreshRecord,
    ): Boolean {
        val keyId = principal.keyId
        return if (keyId != null) {
            record.principalKeyId == keyId
        } else {
            record.principalUserId == principal.userId
        }
    }

    private fun resolve(
        principal: AuthenticatedPrincipal,
        id: UUID,
        version: Int? = null,
    ): ResolvedDashboard {
        requireSessionForVersion(principal, version)
        return resolver.resolve(principal.requireWorkspace().id, lens.viewFor(principal).dashboards, id, version)
    }

    /**
     * A version NAMED on a runtime route is the draft preview's context (#369 R2): the page is session-authenticated
     * (§6.3), and a version parameter would hand a key a second, versioned credential for the same principal — so a
     * `dashboard` key naming one is refused here, the kind's own code, before anything is looked up.
     */
    private fun requireSessionForVersion(
        principal: AuthenticatedPrincipal,
        version: Int?,
    ) {
        if (version != null && principal.keyId != null) {
            throw ApiException(
                DashboardErrorCodes.KEY_KIND_REFUSED,
                "A dashboard key may not name a dashboard version — the version routes are the preview page's, a session's.",
                mapOf("reason" to "version_is_session_only"),
            )
        }
    }

    /**
     * The version a route names must resolve for this caller (#369 R2): absent, DISCARDED, or hidden under a
     * narrowing lens is the family's 404 naming the version they named. Null names nothing — today's read.
     */
    private fun requireServedVersion(
        principal: AuthenticatedPrincipal,
        id: UUID,
        version: Int?,
    ) {
        if (version == null) return
        dashboards.findServedVersion(principal.requireWorkspace().id, lens.viewFor(principal).dashboards, id, version)
            ?: throw ArtifactFamily.DASHBOARD.notFound(id.toString(), version)
    }

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
