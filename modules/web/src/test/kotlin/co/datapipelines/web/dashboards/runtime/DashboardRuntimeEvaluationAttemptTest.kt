package co.datapipelines.web.dashboards.runtime

import co.datapipelines.application.dashboards.RefreshAdmission
import co.datapipelines.application.dashboards.RefreshEngine
import co.datapipelines.application.dashboards.RefreshPlanner
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.executor.ExecutionCancellationService
import co.datapipelines.executor.RefreshAbortFlags
import co.datapipelines.executor.RefreshStartMarkers
import co.datapipelines.executor.StartMarkerRegistration
import co.datapipelines.parameters.EvaluationAttempt
import co.datapipelines.parameters.EvaluationCaller
import co.datapipelines.parameters.ParameterEvaluator
import co.datapipelines.parameters.ParameterSetVersion
import co.datapipelines.visualization.ArtifactRecord
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.DashboardBody
import co.datapipelines.visualization.DashboardRefreshRepository
import co.datapipelines.visualization.DashboardRuntimeConfig
import co.datapipelines.visualization.DashboardService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * #376 — the dashboard runtime's two evaluate call sites name themselves for the durable history: the parameters route
 * is the `DASHBOARD` caller with no correlation id (no refresh exists there), a refresh's evaluation is the `DASHBOARD`
 * caller whose correlation id IS the refresh id (the record and the refresh row join on it), and a key principal is
 * recorded as its key, never its identity. The evaluator double captures the attempt and then refuses, so nothing past
 * the call is exercised here — the runtime's own suites own that.
 */
class DashboardRuntimeEvaluationAttemptTest {
    private val workspaceId = UUID.randomUUID()
    private val dashboardId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val refreshId = UUID.randomUUID()

    private val resolver = mockk<DashboardRuntimeResolver>()
    private val evaluator = mockk<ParameterEvaluator>()
    private val refreshes = mockk<DashboardRefreshRepository>()
    private val streams = mockk<RefreshStreamRegistry>()
    private val startMarkers = mockk<RefreshStartMarkers>(relaxed = true)
    private val set = mockk<ParameterSetVersion>()
    private val captured = slot<EvaluationAttempt>()

    private val runtime =
        DashboardRuntime(
            resolver,
            evaluator,
            refreshes,
            mockk<RefreshAdmission>(),
            mockk<RefreshEngine>(),
            streams,
            mockk(),
            mockk<RefreshAbortSignal>(relaxed = true),
            mockk<RefreshAbortFlags>(relaxed = true),
            startMarkers,
            mockk<ExecutionCancellationService>(relaxed = true),
            mockk<AuditEventSink>(),
            mockk<DashboardService>(),
            mockk<PromoterLens>(relaxed = true),
            DashboardRuntimeConfig(),
            CoroutineScope(Dispatchers.Default),
            mockk(),
            mockk(relaxed = true),
            mockk<RefreshPlanner>(),
        )

    private fun principal(keyId: String? = null): AuthenticatedPrincipal {
        val principal = mockk<AuthenticatedPrincipal>()
        every { principal.userId } returns userId
        every { principal.keyId } returns keyId
        every { principal.requireWorkspace() } returns WorkspaceContext(workspaceId, "acme", WorkspaceRole.VIEWER)
        return principal
    }

    private fun resolvedWithSet() {
        val resolved = mockk<ResolvedDashboard>()
        val served = mockk<ArtifactVersion<DashboardBody>>()
        every { served.record } returns mockk<ArtifactRecord> { every { id } returns dashboardId }
        every { served.body } returns mockk<DashboardBody>()
        every { resolved.served } returns served
        every { resolved.configurationId } returns CONFIGURATION_ID
        every { resolved.set } returns set
        every { resolver.resolve(workspaceId, any(), dashboardId, null) } returns resolved
        every { evaluator.evaluateBlocking(workspaceId, set, any(), capture(captured)) } throws IllegalStateException(CAPTURED)
    }

    @Test
    fun `the parameters route is the DASHBOARD caller with no correlation id - the session's person`() {
        resolvedWithSet()
        val request = ParametersRequest(CONFIGURATION_ID, UUID.randomUUID(), emptyMap(), ParametersRequest.Intent.BOOTSTRAP)

        shouldThrow<IllegalStateException> { runtime.parameters(principal(), dashboardId, request) }.message shouldBe CAPTURED

        captured.captured.caller shouldBe EvaluationCaller.DASHBOARD
        captured.captured.correlationId shouldBe null
        (captured.captured.principalUserId to captured.captured.principalKeyId) shouldBe (userId to null)
    }

    @Test
    fun `a key principal is recorded as its key, never its identity`() {
        resolvedWithSet()
        val request = ParametersRequest(CONFIGURATION_ID, UUID.randomUUID(), emptyMap(), ParametersRequest.Intent.BOOTSTRAP)

        shouldThrow<IllegalStateException> { runtime.parameters(principal(keyId = "dpk_abcdefghijkl"), dashboardId, request) }

        (captured.captured.principalUserId to captured.captured.principalKeyId) shouldBe (null to "dpk_abcdefghijkl")
    }

    @Test
    fun `a refresh's evaluation carries the refresh id as its correlation id`() {
        resolvedWithSet()
        every { refreshes.find(workspaceId, refreshId) } returns null
        every { streams.atStreamLimit(userId) } returns false
        every { streams.maxStreamsPerUser } returns MARKER_BOUND
        every { startMarkers.register(any(), ttlSeconds = any(), perPrincipalLimit = MARKER_BOUND) } returns
            StartMarkerRegistration.REGISTERED
        val request =
            RefreshRequest(
                configurationId = CONFIGURATION_ID,
                instanceId = UUID.randomUUID(),
                refreshId = refreshId,
                parameterRevision = 1,
                selections = emptyMap(),
                selectionsJson = "{}",
                scope = co.datapipelines.visualization.ActionScope.ALL,
                targets = emptyList(),
            )

        shouldThrow<IllegalStateException> { runtime.startRefresh(principal(), dashboardId, request) }.message shouldBe CAPTURED

        captured.captured.caller shouldBe EvaluationCaller.DASHBOARD
        captured.captured.correlationId shouldBe refreshId.toString()
    }

    private companion object {
        const val CONFIGURATION_ID = "cfg-376"
        const val MARKER_BOUND = 4
        const val CAPTURED = "captured the attempt"
    }
}
