package co.datapipelines.web.ui

import co.datapipelines.auth.AuditLogger
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.MembershipFlags
import co.datapipelines.auth.Scope
import co.datapipelines.auth.Workspace
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceMembership
import co.datapipelines.auth.WorkspaceService
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceAuditEvents
import co.datapipelines.datasources.DatasourceGrant
import co.datapipelines.datasources.DatasourceGrantRepository
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import org.springframework.web.servlet.ModelAndView
import java.time.Instant
import java.util.UUID

/**
 * The datasource GRANTS dialog (114 §C.2, D-R7) — the screen 112 said out loud it was leaving
 * behind: *"an instance datasource registered through the UI is visible to nobody until a super
 * admin grants it through the API"*.
 *
 * The 403 for everyone below super admin is NOT asserted here and deliberately: it belongs to
 * `ScopeInterceptor`, which reads `@RequiredScope(MANAGE_DATASOURCE_GRANTS)` off these handlers
 * and refuses before any of them runs. Re-asserting it at the controller would test a second
 * copy of the rule; what this class pins is the ANNOTATION (so the rule is claimed at all) and
 * the behaviour the handlers add on top of it.
 */
class DatasourceGrantsPartialControllerTest {
    private val datasources = mockk<DatasourceRegistry>()
    private val workspaces = mockk<WorkspaceService>()
    private val grants = mockk<DatasourceGrantRepository>()
    private val actorNames = mockk<ActorNames>()
    private val auditLogger = mockk<AuditLogger>(relaxed = true)
    private val controller = DatasourceGrantsPartialController(datasources, workspaces, grants, actorNames, auditLogger)

    private val userId = UUID.randomUUID()
    private val acmeId = UUID.randomUUID()
    private val globexId = UUID.randomUUID()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    /**
     * The operation is `MANAGE_DATASOURCE_GRANTS` (§7.6: `admin` scope, `super_admin` role) —
     * NOT `MUTATE_WORKSPACE_DATASOURCES`, which is the workspace admin's registration verb.
     * The prompt for this round asked for the latter; §7.6 and the REST controller both say
     * the former, and the reason is in that controller's KDoc: a grant list names every
     * workspace on the instance holding one, which is the disclosure D-R5 exists to prevent.
     */
    @Test
    fun `every handler declares the super-admin grants operation`() {
        listOf("dialog", "grant", "revoke").forEach { name ->
            val method = DatasourceGrantsPartialController::class.java.methods.first { it.name == name }
            method
                .getAnnotation(co.datapipelines.auth.RequiredScope::class.java)
                .value shouldBe co.datapipelines.auth.ScopeMatrix.RestOperation.MANAGE_DATASOURCE_GRANTS
        }
    }

    @Test
    fun `the dialog lists the grants, names the registering workspace, and offers the rest`() {
        authenticate()
        every { datasources.get("pg-prod") } returns datasource(owner = acmeId, ownerName = "acme")
        every { grants.grantsOf("pg-prod") } returns listOf(grant("acme", acmeId))
        every { actorNames.lookup(listOf(userId)) } returns mapOf(userId to "Muhammad")
        every { workspaces.listOwn(any()) } returns listOf(membership("acme", acmeId), membership("globex", globexId))

        val model = ExtendedModelMap()
        controller.dialog(model, "pg-prod") shouldBe "partials/datasource-grants"

        @Suppress("UNCHECKED_CAST")
        val rows = model["grants"] as List<Map<String, Any?>>
        rows.single()["workspace"] shouldBe "acme"
        rows.single()["grantedBy"] shouldBe "Muhammad"
        // The registering workspace's own grant is MARKED, not protected: the REST surface
        // lets a super admin revoke it too, and a screen that refuses what the server allows is
        // the mirror of the defect this round removes.
        rows.single()["owner"] shouldBe true
        // The select offers what is NOT already granted — re-granting is a legal no-op, and an
        // option whose only outcome is "no change" is noise.
        model["grantableWorkspaces"] shouldBe listOf("globex")
    }

    /** A deactivated workspace cannot be granted to: it is not selectable for anything (§11A.3). */
    @Test
    fun `a deactivated workspace is not offered as a grant target`() {
        authenticate()
        every { datasources.get("pg-prod") } returns datasource(owner = null, ownerName = null)
        every { grants.grantsOf("pg-prod") } returns emptyList()
        every { actorNames.lookup(emptyList()) } returns emptyMap()
        every { workspaces.listOwn(any()) } returns
            listOf(membership("acme", acmeId), membership("gone", globexId, active = false))

        val model = ExtendedModelMap()
        controller.dialog(model, "pg-prod")

        model["grantableWorkspaces"] shouldBe listOf("acme")
    }

    @Test
    fun `granting writes the row, audits it, and re-renders the dialog`() {
        authenticate()
        every { datasources.get("pg-prod") } returns datasource(owner = null, ownerName = null)
        every { workspaces.read(any(), "globex") } returns workspace("globex", globexId)
        every { grants.grant("pg-prod", globexId, userId) } returns true
        every { grants.grantsOf("pg-prod") } returns listOf(grant("globex", globexId))
        every { actorNames.lookup(listOf(userId)) } returns mapOf(userId to "Muhammad")
        every { workspaces.listOwn(any()) } returns listOf(membership("globex", globexId))

        controller.grant(ExtendedModelMap(), "pg-prod", "globex") shouldBe "partials/datasource-grants"

        verify { grants.grant("pg-prod", globexId, userId) }
        verify {
            auditLogger.log(
                event = DatasourceAuditEvents.GRANTED,
                userId = userId,
                keyId = null,
                details = mapOf("datasource" to "pg-prod", "workspace" to "globex", "already_granted" to false),
            )
        }
    }

    @Test
    fun `revoking removes the row and audits whether there was one`() {
        authenticate()
        every { datasources.get("pg-prod") } returns datasource(owner = null, ownerName = null)
        every { workspaces.read(any(), "globex") } returns workspace("globex", globexId)
        every { grants.revoke("pg-prod", globexId) } returns true
        every { grants.grantsOf("pg-prod") } returns emptyList()
        every { actorNames.lookup(emptyList()) } returns emptyMap()
        every { workspaces.listOwn(any()) } returns listOf(membership("globex", globexId))

        controller.revoke(ExtendedModelMap(), "pg-prod", "globex") shouldBe "partials/datasource-grants"

        verify { grants.revoke("pg-prod", globexId) }
        verify {
            auditLogger.log(
                event = DatasourceAuditEvents.REVOKED,
                userId = userId,
                keyId = null,
                details = mapOf("datasource" to "pg-prod", "workspace" to "globex", "had_grant" to true),
            )
        }
    }

    /**
     * A name that exists on no instance row is the dialog's inline refusal, not a 500 and not
     * an empty dialog. `DatasourceRegistry.get` is the UNFILTERED read here on purpose — a
     * super admin grants datasources their own workspace has never been granted, which is the
     * whole point of the verb.
     */
    @Test
    fun `an unknown datasource is the inline refusal`() {
        authenticate()
        every { datasources.get("ghost") } returns null

        val result = controller.dialog(ExtendedModelMap(), "ghost")

        (result as ModelAndView).viewName shouldBe "partials/inline-refusal"
        result.model["message"].toString().contains("ghost") shouldBe true
    }

    // ------------------------------------------------------------------ fixtures

    private fun authenticate() {
        val principal =
            AuthenticatedPrincipal(
                userId = userId,
                email = "root@acme.test",
                displayName = "Root",
                scopes = setOf(Scope.ADMIN),
                authMethod = AuthMethod.OIDC,
                workspaceName = "acme",
                workspace = WorkspaceContext(acmeId, "acme", MembershipFlags.IMPLICIT_SUPER_ADMIN),
                superAdmin = true,
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private fun datasource(
        owner: UUID?,
        ownerName: String?,
    ) = Datasource(
        name = "pg-prod",
        displayName = "Prod",
        dialect = Dialect.POSTGRES,
        jdbcUrl = "jdbc:postgresql://localhost/db",
        username = "app",
        ownerWorkspaceId = owner,
        workspaceName = ownerName,
    )

    private fun grant(
        workspace: String,
        id: UUID,
    ) = DatasourceGrant("pg-prod", id, workspace, userId, Instant.EPOCH)

    private fun membership(
        name: String,
        id: UUID,
        active: Boolean = true,
    ) = WorkspaceMembership(id, name, MembershipFlags.IMPLICIT_SUPER_ADMIN, Instant.EPOCH, active)

    private fun workspace(
        name: String,
        id: UUID,
    ) = Workspace(
        id = id,
        name = name,
        displayName = name,
        isPersonal = false,
        createdBy = userId,
        isDeleted = false,
        createdAt = Instant.EPOCH,
    )
}
