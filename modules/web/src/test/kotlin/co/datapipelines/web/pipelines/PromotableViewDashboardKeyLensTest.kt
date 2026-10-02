package co.datapipelines.web.pipelines

import co.datapipelines.application.dashboards.DashboardKeyBindingRepository
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.KeyRole
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.visualization.CurrentArtifactVersion
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.VisualizationService
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The `dashboard` key's lens as `PromotableViews` builds it (L5): the workspace's dashboard names
 * run through the R-EP2 walk, and the view's `dashboards` arm is EXACTLY the survivors —
 * `ReadLens.Only`, empty for an unbound key (fail closed: the lens answering nothing). Falsified
 * by planting `ReadLens.Everything` for the key: every "outside the folder" and "unbound" case
 * here goes red.
 */
class PromotableViewDashboardKeyLensTest {
    private val workspace = UUID.randomUUID()
    private val pipelines = mockk<PipelineRepository>(relaxed = true)
    private val templates = mockk<TemplateRepository>(relaxed = true)
    private val client = mockk<PromotionTargetClient>(relaxed = true)
    private val sets = mockk<co.datapipelines.parameters.ParameterSetRepository>(relaxed = true)
    private val visualizations = mockk<VisualizationService>(relaxed = true)
    private val bindings = mockk<DashboardKeyBindingRepository>()
    private val dashboards = mockk<DashboardService>()

    private val view =
        PromotableViews(pipelines, templates, client, sets, dashboards, visualizations, bindings)

    private fun keyPrincipal(keyId: String?): AuthenticatedPrincipal =
        AuthenticatedPrincipal(
            userId = UUID.randomUUID(),
            email = "app@example.test",
            displayName = "host app",
            authMethod = AuthMethod.API_KEY,
            keyId = keyId,
            workspaceName = "ops",
            workspace = co.datapipelines.auth.WorkspaceContext(workspace, "ops", co.datapipelines.auth.WorkspaceRole.VIEWER),
            keyKind = ApiKeyKind.DASHBOARD,
            keyRole = KeyRole.DASHBOARD_VIEWER,
        )

    private fun everyDashboardNamed(): List<CurrentArtifactVersion> =
        listOf(
            CurrentArtifactVersion(UUID.randomUUID(), "ops/boards/public", "Public", 1, "h1"),
            CurrentArtifactVersion(UUID.randomUUID(), "ops/boards/private/secret", "Secret", 1, "h2"),
            CurrentArtifactVersion(UUID.randomUUID(), "ops/other/away", "Away", 1, "h3"),
        )

    @Test
    fun `the key's lens is exactly the dashboards its binding's folder admits`() {
        every { dashboards.currentVersions(workspace) } returns everyDashboardNamed()
        every { bindings.findByWorkspace(workspace) } returns
            listOf(binding("ops/boards", "dpk_HOSTKEY01"))

        val lens = view.viewFor(keyPrincipal("dpk_HOSTKEY01"))

        lens.dashboards shouldBe ReadLens.Only(setOf("ops/boards/public", "ops/boards/private/secret"))
        lens.isLensed shouldBe true
    }

    @Test
    fun `a deeper binding replaces - the shallow key's lens loses the subtree, the deep key's keeps only it`() {
        every { dashboards.currentVersions(workspace) } returns everyDashboardNamed()
        every { bindings.findByWorkspace(workspace) } returns
            listOf(
                binding("ops/boards", "dpk_SHALLOW01"),
                binding("ops/boards/private", "dpk_DEEP00001"),
            )

        view.viewFor(keyPrincipal("dpk_SHALLOW01")).dashboards shouldBe
            ReadLens.Only(setOf("ops/boards/public"))
        view.viewFor(keyPrincipal("dpk_DEEP00001")).dashboards shouldBe
            ReadLens.Only(setOf("ops/boards/private/secret"))
    }

    @Test
    fun `an unbound key's lens admits NOTHING - fail closed, never the whole workspace`() {
        every { dashboards.currentVersions(workspace) } returns everyDashboardNamed()
        every { bindings.findByWorkspace(workspace) } returns emptyList()

        val lens = view.viewFor(keyPrincipal("dpk_UNBOUND001"))

        lens.dashboards shouldBe ReadLens.Only(emptySet())
        lens.dashboards.admits("ops/boards/public") shouldBe false
    }

    @Test
    fun `a key without its workspace resolved answers the empty lens - the runtime's 404, never an error`() {
        val principal = keyPrincipal("dpk_HOSTKEY01").copy(workspace = null)
        val lens = view.viewFor(principal)
        lens.dashboards shouldBe ReadLens.Only(emptySet())
    }

    @Test
    fun `a non-key principal keeps the promoter short-circuit - the key arm never touches a session`() {
        every { client.cachedInventory("ops") } returns
            PromotionTargetClient.CachedInventory.Unreachable("connect_refused", "x")
        every { client.targetBaseUrl } returns "https://uat.example.test"
        every { bindings.findByWorkspace(workspace) } returns emptyList()
        val promoter =
            AuthenticatedPrincipal(
                userId = UUID.randomUUID(),
                email = "p@example.test",
                displayName = "Promoter",
                authMethod = AuthMethod.OIDC,
                workspaceName = "ops",
                workspace = co.datapipelines.auth.WorkspaceContext(workspace, "ops", co.datapipelines.auth.WorkspaceRole.PROMOTER),
            )
        // The promoter walks the inventory path (here: the fail-closed unreachable view); the
        // point is the KEY arm did not fire — the bindings repository is never asked for a
        // non-key principal, and the key arm's own tests above pin the affirmative shape.
        view.viewFor(promoter)
        io.mockk.verify(exactly = 0) { bindings.findByWorkspace(any()) }
    }

    private fun binding(
        prefix: String,
        keyId: String,
    ) = co.datapipelines.application.dashboards.DashboardKeyBinding(
        namePrefix = prefix,
        apiKeyId = keyId,
        workspaceId = workspace,
        createdBy = UUID.randomUUID(),
        createdAt = java.time.Instant.now(),
    )
}
