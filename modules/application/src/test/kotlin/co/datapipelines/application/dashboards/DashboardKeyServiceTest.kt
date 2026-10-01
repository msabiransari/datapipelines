package co.datapipelines.application.dashboards

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.CurrentArtifactVersion
import co.datapipelines.visualization.DashboardService
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/**
 * The `dashboard` bindings' service (L5): the folder grammar, the #191 inside-own-workspace rule,
 * the audited set delta, and the lens expansion. `EndpointKeyServiceTest`'s shape — every
 * refusal its own case, every collaborator a mock, nothing touches a database.
 */
class DashboardKeyServiceTest {
    private val workspace = UUID.randomUUID()
    private val bindings = mockk<DashboardKeyBindingRepository>()
    private val audit = mockk<AuditEventSink>(relaxed = true)
    private val dashboards = mockk<DashboardService>()
    private val service = DashboardKeyService(bindings, audit, dashboards)

    private val admin =
        AuthenticatedPrincipal(
            userId = UUID.randomUUID(),
            email = "admin@x.test",
            displayName = "Admin",
            authMethod = AuthMethod.OIDC,
            workspaceName = "ops",
            workspace = WorkspaceContext(workspace, "ops", WorkspaceRole.WORKSPACE_ADMIN),
        )

    @Test
    fun `bind normalizes the folder, checks it inside the workspace, writes and audits`() {
        every { dashboards.currentVersions(workspace) } returns
            listOf(current("ops/boards/public"), current("ops/boards/private/secret"))
        every { bindings.insert(any()) } returns true
        val written = slot<DashboardKeyBinding>()

        service.bind(admin, "dpk_HOSTKEY01", "ops/boards")

        verify(exactly = 1) { bindings.insert(capture(written)) }
        written.captured.namePrefix shouldBe "ops/boards"
        written.captured.apiKeyId shouldBe "dpk_HOSTKEY01"
        written.captured.workspaceId shouldBe workspace
        verify(exactly = 1) {
            audit.log(event = "dashboard.key_bound", userId = any(), keyId = "dpk_HOSTKEY01", details = any())
        }
    }

    @Test
    fun `bind refuses a prefix off the folder grammar`() {
        val refusal =
            assertThrows<DatapipelinesException> {
                service.bind(admin, "dpk_HOSTKEY01", "Not/A-Folder!")
            }
        refusal.code shouldBe PipelineErrorCodes.Dashboard.BINDING_PATH_INVALID
        verify(exactly = 0) { bindings.insert(any()) }
        verify(exactly = 0) { audit.log(any(), any(), any(), any()) }
    }

    @Test
    fun `bind refuses a folder no dashboard of the CALLER's workspace names - naming only the own tree (#191)`() {
        every { dashboards.currentVersions(workspace) } returns listOf(current("ops/boards/public"))

        val refusal =
            assertThrows<DatapipelinesException> {
                service.bind(admin, "dpk_HOSTKEY01", "elsewhere/secret")
            }
        refusal.code shouldBe PipelineErrorCodes.Dashboard.BINDING_PATH_INVALID
        refusal.message!!.contains("elsewhere/secret") shouldBe true
        refusal.message!!.contains("other workspace") shouldBe false
        verify(exactly = 0) { bindings.insert(any()) }
    }

    @Test
    fun `unbind deletes inside the workspace and audits the removal`() {
        every { bindings.delete("ops/boards", "dpk_HOSTKEY01", workspace) } returns true

        val removed = service.unbind(admin, "dpk_HOSTKEY01", "ops/boards")

        removed shouldBe true
        verify(exactly = 1) { audit.log(event = "dashboard.key_unbound", userId = any(), keyId = "dpk_HOSTKEY01", details = any()) }
    }

    @Test
    fun `the lens expansion is exactly the names the R-EP2 walk admits - empty for an unbound key`() {
        val names = listOf("ops/boards/public", "ops/boards/private/secret", "ops/other/away")
        every { dashboards.currentVersions(workspace) } returns names.map(::current)

        // Bound at the folder: the two boards beneath, nothing else.
        every { bindings.findByWorkspace(workspace) } returns
            listOf(binding("ops/boards", "dpk_HOSTKEY01"))
        service.admittedNames(workspace, "dpk_HOSTKEY01", names) shouldBe
            setOf("ops/boards/public", "ops/boards/private/secret")

        // A deeper binding of another key replaces for its subtree.
        every { bindings.findByWorkspace(workspace) } returns
            listOf(
                binding("ops/boards", "dpk_SHALLOW01"),
                binding("ops/boards/private", "dpk_DEEP0001"),
            )
        service.admittedNames(workspace, "dpk_SHALLOW01", names) shouldBe setOf("ops/boards/public")
        service.admittedNames(workspace, "dpk_DEEP0001", names) shouldBe setOf("ops/boards/private/secret")

        // Unbound = unservable: the EMPTY set is the answer, never the workspace.
        every { bindings.findByWorkspace(workspace) } returns emptyList()
        service.admittedNames(workspace, "dpk_HOSTKEY01", names) shouldBe emptySet()
    }

    private fun current(name: String) = CurrentArtifactVersion(UUID.randomUUID(), name, name, 1, "h")

    private fun binding(
        prefix: String,
        keyId: String,
    ) = DashboardKeyBinding(prefix, keyId, workspace, UUID.randomUUID(), java.time.Instant.now())
}
