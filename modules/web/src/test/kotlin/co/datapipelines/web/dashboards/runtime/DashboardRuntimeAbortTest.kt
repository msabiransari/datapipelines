package co.datapipelines.web.dashboards.runtime

import co.datapipelines.application.dashboards.Admission
import co.datapipelines.application.dashboards.RefreshAdmission
import co.datapipelines.application.dashboards.RefreshEngine
import co.datapipelines.application.dashboards.RefreshPlan
import co.datapipelines.application.dashboards.RefreshPlanner
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Permission
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.executor.ExecutionCancellationService
import co.datapipelines.executor.RefreshAbortFlags
import co.datapipelines.executor.RefreshStartMarker
import co.datapipelines.executor.RefreshStartMarkers
import co.datapipelines.parameters.ParameterEvaluator
import co.datapipelines.visualization.ArtifactRecord
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.DashboardBody
import co.datapipelines.visualization.DashboardErrorCodes
import co.datapipelines.visualization.DashboardRefreshRepository
import co.datapipelines.visualization.DashboardRuntimeConfig
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.RefreshRecord
import co.datapipelines.visualization.RefreshStatus
import co.datapipelines.web.api.ApiException
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/**
 * The abort route's three cases and the start marker's lifecycle, in isolation (#356): a RUNNING row is today's path
 * unchanged; a start still in flight is honoured through its marker (the caller's own principal AND instance, or
 * `execution.cancel_all`) by recording the abort intent under the id; anything else — no marker, a foreign one, a
 * finished refresh — is the one `dashboard.refresh.not_found`. The marker lives exactly as long as `startRefresh`
 * does: registered once the dashboard is resolved, removed on every exit.
 */
class DashboardRuntimeAbortTest {
    private val workspaceId = UUID.randomUUID()
    private val dashboardId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val instanceId = UUID.randomUUID()
    private val refreshId = UUID.randomUUID()

    private val resolver = mockk<DashboardRuntimeResolver>()
    private val evaluator = mockk<ParameterEvaluator>()
    private val refreshes = mockk<DashboardRefreshRepository>()
    private val admission = mockk<RefreshAdmission>()
    private val engine = mockk<RefreshEngine>()
    private val streams = mockk<RefreshStreamRegistry>()
    private val abortSignal = mockk<RefreshAbortSignal>(relaxed = true)
    private val abortFlags = mockk<RefreshAbortFlags>(relaxed = true)
    private val startMarkers = mockk<RefreshStartMarkers>(relaxed = true)
    private val cancellation = mockk<ExecutionCancellationService>(relaxed = true)
    private val planner = mockk<RefreshPlanner>()
    private val config = DashboardRuntimeConfig()

    private val runtime =
        DashboardRuntime(
            resolver,
            evaluator,
            refreshes,
            admission,
            engine,
            streams,
            mockk(),
            abortSignal,
            abortFlags,
            startMarkers,
            cancellation,
            mockk<AuditEventSink>(),
            mockk<DashboardService>(),
            mockk<PromoterLens>(relaxed = true),
            config,
            CoroutineScope(Dispatchers.Default),
            mockk(),
            mockk(relaxed = true),
            planner,
        )

    private fun principal(role: WorkspaceRole = WorkspaceRole.VIEWER): AuthenticatedPrincipal {
        val principal = mockk<AuthenticatedPrincipal>()
        every { principal.userId } returns userId
        every { principal.requireWorkspace() } returns WorkspaceContext(workspaceId, "acme", role)
        every { principal.holds(Permission.EXECUTION_CANCEL_ALL) } returns false
        return principal
    }

    private fun request(): RefreshRequest =
        RefreshRequest(
            configurationId = CONFIGURATION_ID,
            instanceId = instanceId,
            refreshId = refreshId,
            parameterRevision = 1,
            selections = emptyMap(),
            selectionsJson = "{}",
            scope = co.datapipelines.visualization.ActionScope.ALL,
            targets = emptyList(),
        )

    private fun resolved(): ResolvedDashboard {
        val resolved = mockk<ResolvedDashboard>()
        val served = mockk<ArtifactVersion<DashboardBody>>()
        every { served.record } returns mockk<ArtifactRecord> { every { id } returns dashboardId }
        every { served.body } returns mockk<DashboardBody>()
        every { resolved.served } returns served
        every { resolved.configurationId } returns CONFIGURATION_ID
        every { resolved.set } returns null
        return resolved
    }

    private fun stubStart(resolvedDashboard: ResolvedDashboard = resolved()) {
        every { refreshes.find(workspaceId, refreshId) } returns null
        every { streams.atStreamLimit(userId) } returns false
        every { streams.maxStreamsPerUser } returns MARKER_BOUND
        every { resolver.resolve(workspaceId, any(), dashboardId) } returns resolvedDashboard
        every { startMarkers.register(any(), ttlSeconds = any(), perPrincipalLimit = MARKER_BOUND) } returns true
        every { planner.plan(any(), any(), any(), any()) } returns mockk<RefreshPlan> { every { invocations } returns emptyList() }
        coEvery { admission.admit(workspaceId, 0) } returns mockk<Admission>()
    }

    @Test
    fun `a running row takes today's path - flag, local trigger, cancellations - and never consults the marker`() {
        val record = recordOf(status = RefreshStatus.RUNNING)
        every { refreshes.find(workspaceId, refreshId) } returns record
        every { abortSignal.owns(refreshId) } returns true
        every { refreshes.linksOf(refreshId) } returns emptyList()

        runtime.abort(principal(), dashboardId, refreshId, AbortRequest(instanceId))

        verify { abortFlags.request(refreshId, ttlSeconds = any()) }
        verify { abortSignal.triggerLocal(refreshId) }
        verify(exactly = 0) { startMarkers.find(any(), any()) }
    }

    @Test
    fun `an abort before the row finds the matching marker and records the intent - no trigger, no cancellations`() {
        val marker =
            RefreshStartMarker(workspaceId, refreshId, userId, instanceId, dashboardId)
        every { refreshes.find(workspaceId, refreshId) } returns null
        every { startMarkers.find(workspaceId, refreshId) } returns marker
        every { abortSignal.owns(refreshId) } returns false

        runtime.abort(principal(), dashboardId, refreshId, AbortRequest(instanceId))

        verify { abortFlags.request(refreshId, ttlSeconds = any()) }
        verify(exactly = 0) { abortSignal.triggerLocal(any()) }
        verify(exactly = 0) { cancellation.cancel(any(), any()) }
    }

    @Test
    fun `the marker path is the owner's - another person's abort during the window is the family 404`() {
        val marker =
            RefreshStartMarker(workspaceId, refreshId, UUID.randomUUID(), instanceId, dashboardId)
        every { refreshes.find(workspaceId, refreshId) } returns null
        every { startMarkers.find(workspaceId, refreshId) } returns marker

        val thrown = assertThrows<ApiException> { runtime.abort(principal(), dashboardId, refreshId, AbortRequest(instanceId)) }

        thrown.code shouldBe DashboardErrorCodes.REFRESH_NOT_FOUND
        verify(exactly = 0) { abortFlags.request(any(), ttlSeconds = any()) }
    }

    @Test
    fun `the marker path is the owner's instance - the right person on another client instance is 404`() {
        val marker =
            RefreshStartMarker(workspaceId, refreshId, userId, UUID.randomUUID(), dashboardId)
        every { refreshes.find(workspaceId, refreshId) } returns null
        every { startMarkers.find(workspaceId, refreshId) } returns marker

        assertThrows<ApiException> { runtime.abort(principal(), dashboardId, refreshId, AbortRequest(instanceId)) }

        verify(exactly = 0) { abortFlags.request(any(), ttlSeconds = any()) }
    }

    @Test
    fun `a marker naming another dashboard is 404 - the id does not carry across boards`() {
        val marker =
            RefreshStartMarker(workspaceId, refreshId, userId, instanceId, UUID.randomUUID())
        every { refreshes.find(workspaceId, refreshId) } returns null
        every { startMarkers.find(workspaceId, refreshId) } returns marker

        assertThrows<ApiException> { runtime.abort(principal(), dashboardId, refreshId, AbortRequest(instanceId)) }
    }

    @Test
    fun `no row and no marker is 404`() {
        every { refreshes.find(workspaceId, refreshId) } returns null
        every { startMarkers.find(workspaceId, refreshId) } returns null

        assertThrows<ApiException> { runtime.abort(principal(), dashboardId, refreshId, AbortRequest(instanceId)) }
    }

    @Test
    fun `a finished row is 404 even while a marker exists - the row is the authority, the marker is never consulted`() {
        every { refreshes.find(workspaceId, refreshId) } returns recordOf(status = RefreshStatus.ABORTED)

        assertThrows<ApiException> { runtime.abort(principal(), dashboardId, refreshId, AbortRequest(instanceId)) }

        verify(exactly = 0) { startMarkers.find(any(), any()) }
        verify(exactly = 0) { abortFlags.request(any(), ttlSeconds = any()) }
    }

    @Test
    fun `a start removes its marker on the refused exit - admission found no room`() {
        stubStart()
        every { startMarkers.register(any(), ttlSeconds = any(), perPrincipalLimit = MARKER_BOUND) } returns true
        coEvery { admission.admit(workspaceId, 0) } returns null

        val thrown = assertThrows<ApiException> { runtime.startRefresh(principal(), dashboardId, request()) }

        thrown.code shouldBe DashboardErrorCodes.REFRESH_SATURATED
        verify { startMarkers.clear(workspaceId, userId, refreshId) }
    }

    @Test
    fun `a principal at the marker bound is refused the saturated 429 and registers nothing further`() {
        stubStart()
        every { startMarkers.register(any(), ttlSeconds = any(), perPrincipalLimit = MARKER_BOUND) } returns false

        val thrown = assertThrows<ApiException> { runtime.startRefresh(principal(), dashboardId, request()) }

        thrown.code shouldBe DashboardErrorCodes.REFRESH_SATURATED
        verify(exactly = 0) { startMarkers.clear(any(), any(), any()) }
    }

    @Test
    fun `a reused refresh id is refused before any marker is registered`() {
        every { streams.atStreamLimit(userId) } returns false
        every { refreshes.find(workspaceId, refreshId) } returns recordOf(status = RefreshStatus.RUNNING)

        val thrown = assertThrows<ApiException> { runtime.startRefresh(principal(), dashboardId, request()) }

        thrown.code shouldBe DashboardErrorCodes.BODY_INVALID
        verify(exactly = 0) { startMarkers.register(any(), ttlSeconds = any(), perPrincipalLimit = any()) }
    }

    private fun recordOf(status: RefreshStatus): RefreshRecord =
        RefreshRecord(
            id = refreshId,
            dashboardId = dashboardId,
            dashboardVersion = 1,
            workspaceId = workspaceId,
            instanceId = instanceId,
            principalUserId = userId,
            principalKeyId = null,
            scope = co.datapipelines.visualization.ActionScope.ALL,
            targetsJson = "[]",
            parameterRevision = 1,
            selectionsJson = "{}",
            status = status,
            startedAt = java.time.Instant.now(),
            finishedAt = null,
            summaryJson = "{}",
        )

    private companion object {
        const val CONFIGURATION_ID = "cid"
        const val MARKER_BOUND = 4
    }
}
