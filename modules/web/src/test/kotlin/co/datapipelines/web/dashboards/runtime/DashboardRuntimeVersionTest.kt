package co.datapipelines.web.dashboards.runtime

import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.DashboardBody
import co.datapipelines.visualization.DashboardErrorCodes
import co.datapipelines.visualization.DashboardService
import co.datapipelines.web.api.ApiException
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/**
 * The version threading on the runtime's four routes (#369 R2): a named version travels to the resolver; absent,
 * today's calls byte-for-byte (`resolve(…, null)`); a `dashboard` key naming a version is refused — the version
 * routes are the session page's; and an abort naming a version proves the named version resolves for the caller
 * before anything about the refresh row is asked. The resolver's own DRAFT semantics live in
 * [DashboardRuntimeResolverVersionTest] and the service lens truth in the visualization module.
 */
class DashboardRuntimeVersionTest {
    private val workspaceId = UUID.randomUUID()
    private val dashboardId = UUID.randomUUID()
    private val refreshId = UUID.randomUUID()

    private val resolver = mockk<DashboardRuntimeResolver>()
    private val dashboards = mockk<DashboardService>()
    private val lens = PromoterLens { LensedView.EVERYTHING }

    private val runtime =
        DashboardRuntime(
            resolver,
            mockk(),
            mockk(),
            mockk(),
            mockk(),
            mockk(),
            mockk(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk<AuditEventSink>(),
            dashboards,
            lens,
            co.datapipelines.visualization.DashboardRuntimeConfig(),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            mockk(),
            mockk(relaxed = true),
            mockk(),
        )

    private fun sessionPrincipal(): AuthenticatedPrincipal =
        mockk {
            every { userId } returns UUID.randomUUID()
            every { keyId } returns null
            every { requireWorkspace() } returns WorkspaceContext(workspaceId, "acme", WorkspaceRole.VIEWER)
            every { holds(any()) } returns false
        }

    private fun keyPrincipal(): AuthenticatedPrincipal =
        mockk {
            every { userId } returns UUID.randomUUID()
            every { keyId } returns UUID.randomUUID().toString()
            every { requireWorkspace() } returns WorkspaceContext(workspaceId, "acme", WorkspaceRole.VIEWER)
            every { holds(any()) } returns false
        }

    private fun resolved(): ResolvedDashboard {
        val record =
            co.datapipelines.visualization.ArtifactRecord(
                id = dashboardId,
                workspaceId = workspaceId,
                name = "finance/dashboards/board",
                displayName = "board",
                description = "",
                currentVersion = null,
                createdAt = java.time.Instant.EPOCH,
                updatedAt = java.time.Instant.EPOCH,
                createdBy = UUID.randomUUID(),
            )
        val detail =
            co.datapipelines.visualization.ArtifactVersionDetail(
                artifactId = dashboardId,
                version = 2,
                status = co.datapipelines.pipeline.PipelineVersionStatus.DRAFT,
                bodyHash = "hash",
                createdAt = java.time.Instant.EPOCH,
                createdBy = UUID.randomUUID(),
            )
        val body = DashboardBody(displayName = "board", visualizations = emptyList(), layout = co.datapipelines.visualization.DashboardLayout())
        val resolved = mockk<ResolvedDashboard>()
        every { resolved.served } returns ArtifactVersion(record, detail, body)
        every { resolved.visualizations } returns emptyMap()
        every { resolved.configurationId } returns CONFIGURATION_ID
        every { resolved.set } returns null
        return resolved
    }

    @Test
    fun `a named version reaches the resolver on the config route`() {
        every { resolver.resolve(workspaceId, any(), dashboardId, 2) } returns resolved()
        runtime.config(sessionPrincipal(), dashboardId, 2)
        verify(exactly = 1) { resolver.resolve(workspaceId, any(), dashboardId, 2) }
    }

    @Test
    fun `absent is today's call - the resolver sees null, never a derived default`() {
        every { resolver.resolve(workspaceId, any(), dashboardId, null) } returns resolved()
        runtime.config(sessionPrincipal(), dashboardId)
        // Exactly one resolve, and its version argument is the literal null: a call with a number
        // would not match this verification, so the count pins the absent case byte-for-byte.
        verify(exactly = 1) { resolver.resolve(workspaceId, any(), dashboardId, null) }
    }

    @Test
    fun `a dashboard key naming a version is refused on every route, before anything is looked up`() {
        val key = keyPrincipal()
        listOf(
            { runtime.config(key, dashboardId, 2) },
            { runtime.parameters(key, dashboardId, parametersRequest(), 2) },
            { runtime.startRefresh(key, dashboardId, refreshRequest(), 2) },
            { runtime.abort(key, dashboardId, refreshId, abortRequest(), 2) },
        ).forEach { call ->
            val thrown = assertThrows<ApiException> { call() }
            thrown.code shouldBe DashboardErrorCodes.KEY_KIND_REFUSED
        }
        verify(exactly = 0) { resolver.resolve(any(), any(), any(), any()) }
        verify(exactly = 0) { dashboards.findServedVersion(any(), any(), any(), any()) }
    }

    @Test
    fun `an abort naming a version refuses the family 404 when the version does not resolve for the caller`() {
        every { dashboards.findServedVersion(workspaceId, any(), dashboardId, 9) } returns null
        val thrown =
            assertThrows<ApiException> { runtime.abort(sessionPrincipal(), dashboardId, refreshId, abortRequest(), 9) }
        thrown.code shouldBe co.datapipelines.visualization.DashboardErrorCodes.NOT_FOUND
        thrown.details["version"] shouldBe 9
    }

    @Test
    fun `an abort without a version is today's path - the named-version probe never runs`() {
        every { dashboards.findServedVersion(any(), any(), any(), any<Int>()) } throws IllegalStateException("must not be called")
        // No RUNNING row, no marker: today's one dashboard.refresh.not_found, no version probe.
        val refreshes =
            io.mockk.mockk<co.datapipelines.visualization.DashboardRefreshRepository> {
                every { find(workspaceId, refreshId) } returns null
            }
        val startMarkers =
            io.mockk.mockk<co.datapipelines.executor.RefreshStartMarkers> {
                every { find(workspaceId, refreshId) } returns null
            }
        val untouchable =
            DashboardRuntime(
                resolver,
                mockk(),
                refreshes,
                mockk(),
                mockk(),
                mockk(),
                mockk(),
                mockk(relaxed = true),
                mockk(relaxed = true),
                startMarkers,
                mockk(relaxed = true),
                mockk<AuditEventSink>(),
                dashboards,
                lens,
                co.datapipelines.visualization.DashboardRuntimeConfig(),
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
                mockk(),
                mockk(relaxed = true),
                mockk(),
            )
        val thrown =
            assertThrows<ApiException> { untouchable.abort(sessionPrincipal(), dashboardId, refreshId, abortRequest()) }
        thrown.code shouldBe co.datapipelines.visualization.DashboardErrorCodes.REFRESH_NOT_FOUND
    }

    @Test
    fun `the session principal's view is the lens the named version is read through`() {
        val promoter = PromoterLens { LensedView(ReadLens.Everything, ReadLens.Everything, dashboards = ReadLens.NOTHING) }
        val promoterRuntime =
            DashboardRuntime(
                resolver,
                mockk(),
                mockk(),
                mockk(),
                mockk(),
                mockk(),
                mockk(),
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockk<AuditEventSink>(),
                dashboards,
                promoter,
                co.datapipelines.visualization.DashboardRuntimeConfig(),
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
                mockk(),
                mockk(relaxed = true),
                mockk(),
            )
        every { dashboards.findServedVersion(workspaceId, ReadLens.NOTHING, dashboardId, 2) } returns null
        val thrown =
            assertThrows<ApiException> { promoterRuntime.abort(sessionPrincipal(), dashboardId, refreshId, abortRequest(), 2) }
        thrown.code shouldBe co.datapipelines.visualization.DashboardErrorCodes.NOT_FOUND
        verify { dashboards.findServedVersion(workspaceId, ReadLens.NOTHING, dashboardId, 2) }
    }

    private fun parametersRequest(): ParametersRequest =
        ParametersRequest(CONFIGURATION_ID, UUID.randomUUID(), emptyMap(), ParametersRequest.Intent.BOOTSTRAP)

    private fun refreshRequest(): RefreshRequest =
        RefreshRequest(
            configurationId = CONFIGURATION_ID,
            instanceId = UUID.randomUUID(),
            refreshId = refreshId,
            parameterRevision = 0,
            selections = emptyMap(),
            selectionsJson = "{}",
            scope = co.datapipelines.visualization.ActionScope.ALL,
            targets = emptyList(),
        )

    private fun abortRequest(): AbortRequest = AbortRequest(UUID.randomUUID())

    private companion object {
        const val CONFIGURATION_ID = "cfg"
    }
}
