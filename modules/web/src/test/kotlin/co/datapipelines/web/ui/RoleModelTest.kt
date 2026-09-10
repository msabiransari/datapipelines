package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.MembershipFlags
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.ui.ExtendedModelMap
import java.util.UUID

/**
 * The ONE role helper (114 §A), pinned over EVERY flag combination.
 *
 * This is the test the round rests on: sixteen screens read these seven attributes, and a
 * mis-derived boolean either hides a verb from someone who holds it (a product that looks
 * broken) or renders one the server will refuse (the defect this round removes). Enumerating
 * the eight membership rows rather than sampling them is deliberate — the capability axis is
 * NOT a chain (an author may not release, a promoter may not author), so no combination is
 * implied by another and a sampled test would miss exactly the pairs D-R2 exists for.
 */
class RoleModelTest {
    private val workspaceId = UUID.randomUUID()

    // ---------------------------------------------------------------- session, every row

    @Test
    fun `a viewer holds read and execute and nothing else`() {
        val roles = RoleModel.roles(session(MembershipFlags.VIEWER))

        roles.canRead shouldBe true
        // D-R3, the owner's words: "executing them should be fine". This is the row where the
        // two axes disagree — `execute` is the second SCOPE but the bottom CAPABILITY.
        roles.canExecute shouldBe true
        roles.canAuthor shouldBe false
        roles.canPromote shouldBe false
        roles.canAdminWorkspace shouldBe false
        roles.isSuperAdmin shouldBe false
        roles.roleLabel shouldBe "viewer"
    }

    @Test
    fun `an author authors and does not promote`() {
        val roles = RoleModel.roles(session(MembershipFlags(author = true)))

        roles.canAuthor shouldBe true
        // The whole of D-R2: "can edit" and "can release" are two answers.
        roles.canPromote shouldBe false
        roles.canAdminWorkspace shouldBe false
        roles.roleLabel shouldBe "author"
    }

    @Test
    fun `a promoter promotes and does not author`() {
        val roles = RoleModel.roles(session(MembershipFlags(promoter = true)))

        roles.canAuthor shouldBe false
        roles.canPromote shouldBe true
        roles.canAdminWorkspace shouldBe false
        roles.roleLabel shouldBe "promoter"
    }

    @Test
    fun `author plus promoter holds both, and the label names both`() {
        val roles = RoleModel.roles(session(MembershipFlags(author = true, promoter = true)))

        roles.canAuthor shouldBe true
        roles.canPromote shouldBe true
        roles.canAdminWorkspace shouldBe false
        roles.roleLabel shouldBe "author · promoter"
    }

    @Test
    fun `a workspace admin holds every workspace verb - and is not a super admin`() {
        // The V23 constraint `chk_workspace_member_admin_authors` makes `admin` imply `author`
        // on the ROW, which is why every predicate can read `author` straight off it.
        val roles = RoleModel.roles(session(MembershipFlags(author = true, admin = true)))

        roles.canAuthor shouldBe true
        roles.canPromote shouldBe true
        roles.canAdminWorkspace shouldBe true
        roles.isSuperAdmin shouldBe false
        roles.roleLabel shouldBe "admin"
    }

    @Test
    fun `admin plus promoter still reads admin - the label names the highest workspace role`() {
        val roles = RoleModel.roles(session(MembershipFlags(author = true, promoter = true, admin = true)))

        roles.canAdminWorkspace shouldBe true
        roles.roleLabel shouldBe "admin"
    }

    /**
     * A super admin acting in a workspace they hold NO explicit membership in (D-R8). The
     * badge reads `super admin` there — that is the authority they are acting with, and the
     * audit trail records `acting_via=super_admin` for the same reason.
     */
    @Test
    fun `an implicit super admin holds everything and reads super admin`() {
        val roles = RoleModel.roles(session(MembershipFlags.IMPLICIT_SUPER_ADMIN, superAdmin = true))

        roles.canRead shouldBe true
        roles.canExecute shouldBe true
        roles.canAuthor shouldBe true
        roles.canPromote shouldBe true
        roles.canAdminWorkspace shouldBe true
        roles.isSuperAdmin shouldBe true
        roles.roleLabel shouldBe "super admin"
    }

    @Test
    fun `a super admin with an explicit viewer membership still reads super admin`() {
        val roles = RoleModel.roles(session(MembershipFlags.superAdminOver(MembershipFlags.VIEWER), superAdmin = true))

        roles.canAdminWorkspace shouldBe true
        roles.roleLabel shouldBe "super admin"
    }

    // ---------------------------------------------------------------- the credential axis

    /**
     * §7.6's other axis. A key's SCOPE is a ceiling on its issuer's role: a `read` key must not
     * be shown an author's affordances just because the person who minted it is an author, or
     * the screen would offer verbs `ScopeInterceptor` refuses on the scope arm.
     */
    @Test
    fun `a read-scoped key narrows an admin issuer to reading`() {
        val roles = RoleModel.roles(key(MembershipFlags(author = true, admin = true), Scope.READ))

        roles.canRead shouldBe true
        // `execute` is the second scope; a `read` key does not reach it (the mirror of the
        // viewer row above — each axis refuses something the other admits).
        roles.canExecute shouldBe false
        roles.canAuthor shouldBe false
        roles.canPromote shouldBe false
        roles.canAdminWorkspace shouldBe false
        // The LABEL follows the effective booleans, never the raw row: printing `admin` here
        // would contradict every button beside it.
        roles.roleLabel shouldBe "viewer"
    }

    @Test
    fun `an execute-scoped key reaches execution and stops there`() {
        val roles = RoleModel.roles(key(MembershipFlags(author = true), Scope.EXECUTE))

        roles.canExecute shouldBe true
        roles.canAuthor shouldBe false
        roles.roleLabel shouldBe "viewer"
    }

    @Test
    fun `an author-scoped key carries its issuer's author and promoter rows`() {
        // Release/promote are `author` SCOPE (the credential axis has no promoter, design §1),
        // so an author-scoped key whose issuer is a promoter does reach them.
        val roles = RoleModel.roles(key(MembershipFlags(author = true, promoter = true), Scope.AUTHOR))

        roles.canAuthor shouldBe true
        roles.canPromote shouldBe true
        roles.roleLabel shouldBe "author · promoter"
    }

    /**
     * O-2: no key may hold `admin` scope at all, so an INSTANCE verb is session-only. The
     * screen says the same thing the matrix says on the scope arm, rather than rendering a
     * Create/Deactivate button the interceptor will refuse.
     */
    @Test
    fun `a key never renders the instance verbs, even for a super admin owner`() {
        val roles = RoleModel.roles(key(MembershipFlags.IMPLICIT_SUPER_ADMIN, Scope.AUTHOR, superAdmin = true))

        roles.isSuperAdmin shouldBe false
        roles.canAdminWorkspace shouldBe true
        roles.roleLabel shouldBe "super admin"
    }

    // ---------------------------------------------------------------- no workspace at all

    @Test
    fun `a principal with no resolved workspace renders nothing`() {
        val roles = RoleModel.roles(session(flags = null))

        roles shouldBe RoleModel.NONE
        // The badge still has a word in it: an empty badge reads as a broken screen.
        roles.roleLabel shouldBe "viewer"
    }

    @Test
    fun `a null principal renders nothing`() {
        RoleModel.roles(null) shouldBe RoleModel.NONE
    }

    // ---------------------------------------------------------------- the model contract

    /**
     * The seven attribute NAMES are a contract with every template in the app; a rename here
     * is a silent un-rendering there (`th:if` on an absent variable is false, not an error).
     */
    @Test
    fun `stamp puts exactly the seven documented attributes into the model`() {
        val model = ExtendedModelMap()

        RoleModel.stamp(model, session(MembershipFlags(author = true)))

        model.keys shouldBe
            setOf("canRead", "canExecute", "canAuthor", "canPromote", "canAdminWorkspace", "isSuperAdmin", "roleLabel")
        model["canAuthor"] shouldBe true
        model["canPromote"] shouldBe false
        model["roleLabel"] shouldBe "author"
    }

    /** The row label, for somebody ELSE's membership — the members table and the workspace list. */
    @Test
    fun `labelOf names a membership row without consulting any user-level flag`() {
        RoleModel.labelOf(MembershipFlags.VIEWER) shouldBe "viewer"
        RoleModel.labelOf(MembershipFlags(author = true)) shouldBe "author"
        RoleModel.labelOf(MembershipFlags(promoter = true)) shouldBe "promoter"
        RoleModel.labelOf(MembershipFlags(author = true, promoter = true)) shouldBe "author · promoter"
        RoleModel.labelOf(MembershipFlags(author = true, admin = true)) shouldBe "admin"
        // `superAdmin` is a property of the USER, not of the row: a row says what the
        // MEMBERSHIP carries, and instance authority is not one of its columns.
        RoleModel.labelOf(MembershipFlags(superAdmin = true)) shouldBe "viewer"
    }

    // ---------------------------------------------------------------- fixtures

    private fun session(
        flags: MembershipFlags?,
        superAdmin: Boolean = false,
    ): AuthenticatedPrincipal =
        AuthenticatedPrincipal(
            userId = UUID.randomUUID(),
            email = "u@acme.test",
            displayName = "U",
            scopes = emptySet(),
            authMethod = AuthMethod.OIDC,
            workspaceName = "acme",
            workspace = flags?.let { WorkspaceContext(workspaceId, "acme", it) },
            superAdmin = superAdmin,
        )

    private fun key(
        flags: MembershipFlags,
        scope: Scope,
        superAdmin: Boolean = false,
    ): AuthenticatedPrincipal =
        AuthenticatedPrincipal(
            userId = UUID.randomUUID(),
            email = "u@acme.test",
            displayName = "U",
            scopes = setOf(scope),
            authMethod = AuthMethod.API_KEY,
            keyId = "dp_key",
            workspaceName = "acme",
            workspace = WorkspaceContext(workspaceId, "acme", flags),
            superAdmin = superAdmin,
        )
}
