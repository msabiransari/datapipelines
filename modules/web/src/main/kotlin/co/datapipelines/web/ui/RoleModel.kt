package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Capability
import co.datapipelines.auth.MembershipFlags
import co.datapipelines.auth.Scope
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.Model

/**
 * **The** role question every screen asks — answered once, stamped into the model, and read by
 * the templates as plain booleans (RBAC design §7: *"the role decides what is rendered; the
 * server decides what is allowed"*).
 *
 * Round 1 (112) made the SERVER right: every verb is refused by role through
 * [co.datapipelines.auth.ScopeMatrix]. This class is the other half — a viewer no longer sees
 * Release, Purge, Register or New key at all. Rendering a verb the server will refuse is not a
 * safe default: it teaches a person the product is broken, and the refusal arrives after they
 * have already decided to act.
 *
 * ## Why one object and not a boolean per controller
 * Before this round "can this person author" was written four times — twice as a private
 * `canAuthor()` in the template controllers, once inline in [DatasourceBrowseModel], once as
 * `isAdmin` on the datasource partials — and each copy was a place the answer could drift from
 * the matrix. The predicates themselves live on [AuthenticatedPrincipal] (`isAuthor`,
 * `isPromoter`, `isWorkspaceAdmin`, `isSuperAdmin`), which is where 112 put them; this class
 * adds only what the principal has no opinion about — membership itself, execution, and the
 * LABEL — and puts all seven answers into the model under names the templates agree on.
 *
 * ## The API-key conjunct
 * Every boolean here narrows for an API-key principal exactly as
 * [AuthenticatedPrincipal.isAuthor] does: the credential axis is real (§7.6), so a `read` key
 * must not be shown an author's affordances merely because the person who minted it is an
 * author. A SESSION carries no scopes since D-R1 and is therefore judged on the membership
 * alone.
 *
 * ## Why an `object` and not a bean
 * It has no collaborators — every input arrives as an argument. `ToastHtml`, `AppNav`,
 * `ApiKeyForm` and `RelativeTime` are the same shape in this package, and a bean would make
 * sixteen controller constructors (and every one of their direct-invocation unit tests) grow a
 * parameter that carries no state.
 *
 * ## Hide, don't disable
 * A verb the role cannot perform is NOT rendered — no greyed button with a tooltip. The one
 * exception in the product is the editor's Release, disabled with its reason when the DRAFT is
 * invalid for someone who CAN release: that is a state refusal, not a role refusal, and its
 * reason is actionable.
 */
object RoleModel {
    /**
     * The seven answers for one principal.
     *
     * [canRead] is "is there a workspace to read at all" — false only for a principal with zero
     * reachable memberships, which is the case the no-workspace page (§C.3) exists for.
     */
    data class Roles(
        val canRead: Boolean,
        val canExecute: Boolean,
        val canAuthor: Boolean,
        val canPromote: Boolean,
        val canAdminWorkspace: Boolean,
        val isSuperAdmin: Boolean,
        val roleLabel: String,
    )

    /**
     * The roles [principal] holds in its ACTIVE workspace. A null principal — an unauthenticated
     * render, or a fragment reached before the security context is populated — is the viewer with
     * no workspace: every boolean false, so a template that forgets a guard still renders nothing.
     */
    fun roles(principal: AuthenticatedPrincipal?): Roles {
        if (principal == null) return NONE
        val flags = principal.workspace?.flags ?: return NONE
        // A member is a member: VIEW and EXECUTE are satisfied by every row (D-R3, "viewers
        // execute"), so the only question left on those two rungs is the credential's scope.
        val canAuthor = principal.isAuthor
        val canPromote = principal.isPromoter
        return Roles(
            canRead = Capability.VIEW.satisfiedBy(flags) && principal.scopeReaches(Scope.READ),
            canExecute = Capability.EXECUTE.satisfiedBy(flags) && principal.scopeReaches(Scope.EXECUTE),
            canAuthor = canAuthor,
            canPromote = canPromote,
            // The scope conjunct is added HERE rather than on `isWorkspaceAdmin`, which has
            // none: every §7.6 row a workspace admin drives (`MANAGE_WORKSPACE`,
            // `MANAGE_WORKSPACE_MEMBERS`, `MUTATE_WORKSPACE_DATASOURCES`, `TEST_DATASOURCE`)
            // carries an `author` SCOPE floor, so a `read` key whose issuer administers the
            // workspace must not be shown Register/Edit/Delete/Test — the interceptor would
            // refuse them on the scope arm. `isAuthor` and `isPromoter` already narrow this
            // way; this is the third rung saying the same thing.
            canAdminWorkspace = principal.isWorkspaceAdmin && principal.scopeReaches(Scope.AUTHOR),
            // A key may not hold `admin` scope at all (O-2), so an instance verb is
            // session-only — the matrix says so on the scope axis, and this says the same
            // thing on the screen rather than offering a button the interceptor will refuse.
            isSuperAdmin = principal.isSuperAdmin && principal.scopeReaches(Scope.ADMIN),
            roleLabel =
                label(
                    superAdmin = principal.isSuperAdmin,
                    admin = principal.isWorkspaceAdmin && principal.scopeReaches(Scope.AUTHOR),
                    author = canAuthor,
                    promoter = canPromote,
                ),
        )
    }

    /**
     * Stamps [roles] into [model] under the names every template reads. Called by each screen's
     * controller rather than by a `@ControllerAdvice`, because the UI controllers are unit-tested
     * by DIRECT invocation (no dispatcher, no advice chain) — an advice would put the attributes
     * in production and leave every one of those tests rendering an empty model.
     */
    fun stamp(
        model: Model,
        principal: AuthenticatedPrincipal?,
    ) {
        val roles = roles(principal)
        model.addAttribute("canRead", roles.canRead)
        model.addAttribute("canExecute", roles.canExecute)
        model.addAttribute("canAuthor", roles.canAuthor)
        model.addAttribute("canPromote", roles.canPromote)
        model.addAttribute("canAdminWorkspace", roles.canAdminWorkspace)
        model.addAttribute("isSuperAdmin", roles.isSuperAdmin)
        model.addAttribute("roleLabel", roles.roleLabel)
    }

    /**
     * [stamp] for the common case: the principal of the CURRENT request. Every UI controller
     * already reads the security context for something, and asking it twice is cheaper than
     * threading a nullable principal through sixteen call sites that would each have to get the
     * null-handling right.
     */
    fun stamp(model: Model) = stamp(model, currentPrincipalOrNull())

    /** The request's principal, or null — an unauthenticated render, or a non-app credential. */
    fun currentPrincipalOrNull(): AuthenticatedPrincipal? =
        SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal

    /**
     * The label for a MEMBERSHIP ROW, rather than for the current principal — what the members
     * table and the workspace list print about somebody else.
     *
     * Same vocabulary as [label], derived from the same flags, so the word beside a member's
     * name and the word in that person's own shell badge agree. `superAdmin` is a property of
     * the USER, not of the row, so it never appears here: a row says what the MEMBERSHIP
     * carries, and an instance super admin's authority is not one.
     */
    fun labelOf(flags: MembershipFlags): String =
        when {
            flags.admin -> "admin"
            flags.author && flags.promoter -> "author · promoter"
            flags.author -> "author"
            flags.promoter -> "promoter"
            else -> "viewer"
        }

    /**
     * The word the shell shows next to the workspace name.
     *
     * Derived from the flags, stored nowhere — no single label names an additive row, which is
     * why the membership carries three booleans and not a role column (D-R2). A super admin
     * reads `super admin` in every workspace, including ones they hold no explicit membership
     * in (D-R8), because that is the authority they are acting with.
     *
     * The label follows the EFFECTIVE booleans, not the raw row: an API key whose scope narrows
     * its issuer's authoring reach must not print `author`, or the badge would contradict the
     * buttons beside it. `super admin` is the exception and says so at the branch — it is what
     * the credential's OWNER is, not what this credential may do.
     */
    private fun label(
        superAdmin: Boolean,
        admin: Boolean,
        author: Boolean,
        promoter: Boolean,
    ): String =
        when {
            // The USER's instance authority, and the only rung that is not narrowed by the
            // credential: a key held by a super admin still belongs to one, even though it
            // cannot drive the instance verbs (O-2 — no key holds `admin` scope).
            superAdmin -> "super admin"

            admin -> "admin"

            author && promoter -> "author · promoter"

            author -> "author"

            promoter -> "promoter"

            else -> "viewer"
        }

    /** The credential axis. A session carries no scopes (D-R1) and is judged on the membership alone. */
    private fun AuthenticatedPrincipal.scopeReaches(scope: Scope): Boolean =
        authMethod != AuthMethod.API_KEY || Scope.satisfies(scopes, scope)

    /**
     * The answer for "no principal, or no reachable workspace": nothing is rendered. The label
     * is still `viewer` rather than empty so the shell's badge never renders blank — a badge
     * with no word in it reads as a broken screen, not as an absent role.
     */
    val NONE =
        Roles(
            canRead = false,
            canExecute = false,
            canAuthor = false,
            canPromote = false,
            canAdminWorkspace = false,
            isSuperAdmin = false,
            roleLabel = "viewer",
        )
}
