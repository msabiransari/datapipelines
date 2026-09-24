package co.datapipelines.web.ui

import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.ui.ExtendedModelMap
import java.util.UUID

/**
 * The ONE role helper (114 §A), pinned over EVERY role (D1: four workspace roles, plus super
 * admin, plus "no workspace").
 *
 * This is the test the round rests on: sixteen screens read these attributes, and a
 * mis-derived boolean either hides a verb from someone who holds it (a product that looks
 * broken) or renders one the server will refuse (the defect 114 removed). Every role is
 * enumerated rather than sampled — the roles are NOT a chain (a promoter promotes but does not
 * execute; an author releases but does not promote), so no row is implied by another.
 */
class RoleModelTest {
    private val workspaceId = UUID.randomUUID()

    // ---------------------------------------------------------------- session, every role

    @Test
    fun `a viewer holds read, execute and the executions screens, and nothing else`() {
        val roles = RoleModel.roles(session(WorkspaceRole.VIEWER))

        roles.canRead shouldBe true
        // D3, the owner's words: "executing them should be fine". This is the row where the
        // two axes disagree — `execute` is the second SCOPE but a viewer-level PERMISSION.
        roles.canExecute shouldBe true
        roles.canReadExecutions shouldBe true
        roles.canAuthor shouldBe false
        roles.canReadPromotion shouldBe false
        roles.canPromote shouldBe false
        roles.canAdminWorkspace shouldBe false
        roles.isSuperAdmin shouldBe false
        roles.roleLabel shouldBe "viewer"
    }

    @Test
    fun `an author authors, sees the promotion page, and does not promote`() {
        val roles = RoleModel.roles(session(WorkspaceRole.AUTHOR))

        roles.canAuthor shouldBe true
        roles.canExecute shouldBe true
        roles.canReadExecutions shouldBe true
        // Owner rule 13: the PAGE is the author's too; the promote VERB is not.
        roles.canReadPromotion shouldBe true
        roles.canPromote shouldBe false
        roles.canAdminWorkspace shouldBe false
        roles.roleLabel shouldBe "author"
    }

    /** D5 (2026-09-20): the ops role — promotes, reads; executes nothing, reads no executions, authors nothing. */
    @Test
    fun `a promoter promotes and reads the promotion page, and neither executes nor authors`() {
        val roles = RoleModel.roles(session(WorkspaceRole.PROMOTER))

        roles.canRead shouldBe true
        roles.canExecute shouldBe false
        roles.canReadExecutions shouldBe false
        roles.canAuthor shouldBe false
        roles.canReadPromotion shouldBe true
        roles.canPromote shouldBe true
        roles.canAdminWorkspace shouldBe false
        roles.roleLabel shouldBe "promoter"
    }

    @Test
    fun `a workspace admin holds every workspace verb - and is not a super admin`() {
        val roles = RoleModel.roles(session(WorkspaceRole.WORKSPACE_ADMIN))

        roles.canExecute shouldBe true
        roles.canReadExecutions shouldBe true
        roles.canAuthor shouldBe true
        roles.canReadPromotion shouldBe true
        roles.canPromote shouldBe true
        roles.canAdminWorkspace shouldBe true
        roles.isSuperAdmin shouldBe false
        roles.roleLabel shouldBe "workspace admin"
    }

    /**
     * A super admin acting in a workspace they hold NO explicit membership in (D7). The
     * badge reads `super admin` there — that is the authority they are acting with, and the
     * audit trail records `acting_via=super_admin` for the same reason.
     */
    @Test
    fun `an implicit super admin holds everything and reads super admin`() {
        val roles = RoleModel.roles(superAdminSession(explicitRole = null))

        roles.canRead shouldBe true
        roles.canExecute shouldBe true
        roles.canReadExecutions shouldBe true
        roles.canAuthor shouldBe true
        roles.canReadPromotion shouldBe true
        roles.canPromote shouldBe true
        roles.canAdminWorkspace shouldBe true
        roles.isSuperAdmin shouldBe true
        roles.roleLabel shouldBe "super admin"
    }

    @Test
    fun `a super admin with an explicit viewer membership still reads super admin`() {
        val roles = RoleModel.roles(superAdminSession(explicitRole = WorkspaceRole.VIEWER))

        roles.canAdminWorkspace shouldBe true
        roles.roleLabel shouldBe "super admin"
    }

    // ---------------------------------------------------------------- the credential axis

    /**
     * #215 slice (b): there is no credential axis left for the screen to narrow by — a key's
     * authority is a ROLE, the same the screen reads for a session. (No key renders a screen at
     * all since B2; these pin that `RoleModel` would render one honestly if it did.) A super
     * admin's MCP key is resolved by `ApiKeyService.validate` as the member it acts as — with no
     * membership, an implicit VIEWER, never the super admin (B1).
     */
    @Test
    fun `a super admin's MCP key renders as the viewer it acts as - never the super admin (B1)`() {
        val roles = RoleModel.roles(superAdminKey())

        roles.isSuperAdmin shouldBe false
        roles.canAdminWorkspace shouldBe false
        roles.canAuthor shouldBe false
        roles.roleLabel shouldBe "viewer"
        RoleModel.shell(superAdminKey()).adminUsers shouldBe false
    }

    @Test
    fun `an author's MCP key renders as the author it acts as - nothing narrows it`() {
        val roles = RoleModel.roles(key(WorkspaceRole.AUTHOR))

        roles.canAuthor shouldBe true
        roles.canExecute shouldBe true
        roles.roleLabel shouldBe "author"
    }

    // ---------------------------------------------------------------- no workspace at all

    @Test
    fun `a principal with no resolved workspace renders nothing`() {
        val roles = RoleModel.roles(session(role = null))

        roles shouldBe RoleModel.NONE
        // The badge still has a word in it: an empty badge reads as a broken screen.
        roles.roleLabel shouldBe "viewer"
    }

    @Test
    fun `a null principal renders nothing`() {
        RoleModel.roles(null) shouldBe RoleModel.NONE
    }

    // ---------------------------------------------------------------- the shell (143, D11, D13, rule 13)

    /**
     * 143 (T315) — the rail's Admin entry is decided here, from the same predicates the verb
     * booleans use, and NEVER from the role label. Two destinations, mutually exclusive:
     * instance user administration for a super admin (workspace-independent — the instance
     * authority is the USER's, so a super admin with no active workspace keeps the entry),
     * or the active workspace's member management for a workspace admin. Nobody else has one.
     *
     * 2026-09-20 — three more items follow a §7.6 row: Executions (`READ_EXECUTIONS`),
     * Promotion (`PROMOTION_READ`), Workspaces (`WORKSPACES_READ`).
     */
    @Test
    fun `viewer, author and promoter get no Admin entry, and the rail follows their rows`() {
        RoleModel.shell(session(WorkspaceRole.VIEWER)) shouldBe shell(executions = true)
        RoleModel.shell(session(WorkspaceRole.AUTHOR)) shouldBe shell(executions = true, promotion = true)
        RoleModel.shell(session(WorkspaceRole.PROMOTER)) shouldBe shell(promotion = true)
    }

    @Test
    fun `a workspace admin's Admin entry is the active workspace's members, never the instance`() {
        RoleModel.shell(session(WorkspaceRole.WORKSPACE_ADMIN)) shouldBe
            shell(adminMembers = true, executions = true, promotion = true, workspaces = true, apiKeys = true)
    }

    @Test
    fun `a super admin's Admin entry is instance users, with or without a workspace`() {
        RoleModel.shell(superAdminSession(explicitRole = null)) shouldBe
            shell(adminUsers = true, executions = true, promotion = true, workspaces = true, apiKeys = true)
        // No active workspace: the workspace-bound Roles are NONE, the shell entry survives,
        // and the Workspaces item stays (the no-workspace page is the one screen that explains).
        RoleModel.roles(session(role = null, superAdmin = true)) shouldBe RoleModel.NONE
        RoleModel.shell(session(role = null, superAdmin = true)) shouldBe shell(adminUsers = true, workspaces = true)
    }

    @Test
    fun `no principal renders no rail item - a member with no workspace keeps only the Workspaces link`() {
        RoleModel.shell(null) shouldBe shell()
        RoleModel.shell(session(role = null)) shouldBe shell(workspaces = true)
    }

    // ---------------------------------------------------------------- the model contract

    /**
     * The attribute NAMES are a contract with every template in the app; a rename here
     * is a silent un-rendering there (`th:if` on an absent variable is false, not an error).
     */
    @Test
    fun `stamp puts exactly the nine documented attributes into the model`() {
        val model = ExtendedModelMap()

        RoleModel.stamp(model, session(WorkspaceRole.AUTHOR))

        model.keys shouldBe
            setOf(
                "canRead",
                "canExecute",
                "canReadExecutions",
                "canAuthor",
                "canReadPromotion",
                "canPromote",
                "canAdminWorkspace",
                "isSuperAdmin",
                "roleLabel",
            )
        model["canAuthor"] shouldBe true
        model["canPromote"] shouldBe false
        model["roleLabel"] shouldBe "author"
    }

    /** The row label, for somebody ELSE's membership — the members table and the workspace list. */
    @Test
    fun `labelOf names a membership row by its role`() {
        RoleModel.labelOf(WorkspaceRole.VIEWER) shouldBe "viewer"
        RoleModel.labelOf(WorkspaceRole.AUTHOR) shouldBe "author"
        RoleModel.labelOf(WorkspaceRole.PROMOTER) shouldBe "promoter"
        RoleModel.labelOf(WorkspaceRole.WORKSPACE_ADMIN) shouldBe "workspace admin"
    }

    // ---------------------------------------------------------------- fixtures

    private fun shell(
        adminUsers: Boolean = false,
        adminMembers: Boolean = false,
        executions: Boolean = false,
        promotion: Boolean = false,
        workspaces: Boolean = false,
        apiKeys: Boolean = false,
    ) = RoleModel.Shell(adminUsers, adminMembers, executions, promotion, workspaces, apiKeys)

    private fun session(
        role: WorkspaceRole?,
        superAdmin: Boolean = false,
    ): AuthenticatedPrincipal =
        AuthenticatedPrincipal(
            userId = UUID.randomUUID(),
            email = "u@acme.test",
            displayName = "U",
            authMethod = AuthMethod.OIDC,
            workspaceName = "acme",
            workspace = role?.let { WorkspaceContext(workspaceId, "acme", it) },
            superAdmin = superAdmin,
        )

    private fun superAdminSession(explicitRole: WorkspaceRole?): AuthenticatedPrincipal =
        AuthenticatedPrincipal(
            userId = UUID.randomUUID(),
            email = "root@acme.test",
            displayName = "Root",
            authMethod = AuthMethod.OIDC,
            workspaceName = "acme",
            workspace = WorkspaceContext.superAdminOver(workspaceId, "acme", explicitRole),
            superAdmin = true,
        )

    private fun key(role: WorkspaceRole): AuthenticatedPrincipal =
        AuthenticatedPrincipal(
            userId = UUID.randomUUID(),
            email = "u@acme.test",
            displayName = "U",
            authMethod = AuthMethod.API_KEY,
            keyId = "dp_key",
            workspaceName = "acme",
            workspace = WorkspaceContext(workspaceId, "acme", role),
        )

    /** A super admin's MCP key as `ApiKeyService.validate` resolves it with no membership: an implicit viewer, never super admin (B1). */
    private fun superAdminKey(): AuthenticatedPrincipal =
        AuthenticatedPrincipal(
            userId = UUID.randomUUID(),
            email = "root@acme.test",
            displayName = "Root",
            authMethod = AuthMethod.API_KEY,
            keyId = "dp_key",
            workspaceName = "acme",
            workspace = WorkspaceContext(workspaceId, "acme", WorkspaceRole.VIEWER, implicit = true),
            keyKind = ApiKeyKind.USER,
        )
}
