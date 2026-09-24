package co.datapipelines.web.ui

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Permission
import co.datapipelines.auth.WorkspaceRole
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
 * adds only what the principal has no opinion about — membership itself, execution, the two
 * page-wide reads (executions, promotion) and the LABEL — and puts the answers into the model
 * under names the templates agree on.
 *
 * ## No credential axis
 * Until #215 slice (b) every boolean here also narrowed for an API-key principal by its scope.
 * Scopes are gone and no key reaches a screen (the MCP key is confined to `/mcp`, B2), so the
 * membership role is the whole answer.
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
     * The answers for one principal.
     *
     * [canRead] is "is there a workspace to read at all" — false only for a principal with zero
     * reachable memberships, which is the case the no-workspace page (§C.3) exists for.
     *
     * [canReadExecutions] (D11, 2026-09-20) is the `READ_EXECUTIONS` row: the same roles as
     * [canExecute] — the promoter reads no executions — so the rail's Executions item, the
     * dashboard's runs and every runs panel render by it. [canReadPromotion] is the
     * `PROMOTION_READ` row (owner rule 13): the author who released sees the promotion page,
     * the promote verb on it still renders by [canPromote].
     */
    data class Roles(
        val canRead: Boolean,
        val canExecute: Boolean,
        val canReadExecutions: Boolean,
        val canAuthor: Boolean,
        val canReadPromotion: Boolean,
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
        val context = principal.workspace ?: return NONE
        // #215: each boolean asks ONE representative catalog permission — `pipeline.read` (every
        // role), `pipeline.execute` and `execution.read` (every role but the promoter, D5),
        // `template.update` for authoring (through `isAuthor`), `promotion.read`,
        // `promotion.promote` (through `isPromoter`), `workspace.members.manage` (through
        // `isWorkspaceAdmin`). The role is the whole answer since slice (b): scopes are gone, and
        // no key renders a screen — the MCP key is confined to `/mcp` (B2), the other kinds to
        // their own routes — so every principal here is a signed-in person.
        return Roles(
            canRead = context.permits(Permission.PIPELINE_READ),
            canExecute = context.permits(Permission.PIPELINE_EXECUTE),
            canReadExecutions = context.permits(Permission.EXECUTION_READ),
            canAuthor = principal.isAuthor,
            canReadPromotion = context.permits(Permission.PROMOTION_READ),
            canPromote = principal.isPromoter,
            canAdminWorkspace = principal.isWorkspaceAdmin,
            isSuperAdmin = principal.isSuperAdmin,
            roleLabel = label(principal.isSuperAdmin, context.role),
        )
    }

    /**
     * 143 (T315) — the SHELL's Admin entry: which destination, if any, the rail's Admin item
     * points at for this principal. Decided here, beside the verb booleans and from the same
     * predicates, never from the role LABEL (a string comparison would be a second
     * authorization matrix). Exactly one of the two is true, or neither:
     *
     * - [adminUsers] — instance user administration (`/admin/users`, `USER_ADMINISTRATION`,
     *   super admin only). The instance authority is the USER's, not the membership's, so
     *   it is judged WITHOUT the workspace: a super admin whose active workspace is gone
     *   still owns the instance. No key is ever a super admin (#215 B1).
     * - [adminMembers] — the ACTIVE workspace's member management (the Workspaces screen's
     *   members section, `MANAGE_WORKSPACE_MEMBERS`) for a workspace admin. This one IS
     *   workspace-bound (§4.13: member verbs follow the active workspace) and is
     *   [Roles.canAdminWorkspace] verbatim; a super admin takes the instance entry instead.
     *
     * Viewers, authors and pure promoters get neither: an Admin item that leads to a screen
     * the interceptor refuses is exactly the dead link 114 §A exists to remove.
     *
     * Three more rail items follow a §7.6 row since 2026-09-20 and are decided here for the
     * same reason: [executions] is `READ_EXECUTIONS` (the promoter has no Executions item),
     * [promotion] is `PROMOTION_READ` (authors gain it, viewers never had a reason to see it),
     * [workspaces] is `WORKSPACES_READ` (D13 — the page is a workspace admin's; a principal
     * with NO workspace keeps the link, because the no-workspace page is the one screen that
     * explains their state, ui-screens §4.13). [apiKeys] (179, D17) is `MANAGE_API_KEYS`:
     * the avatar menu's "API keys" link leads to `/api-keys`, a workspace admin's page, and
     * renders for nobody else — the top bar's chip is everyone else's key surface.
     */
    data class Shell(
        val adminUsers: Boolean,
        val adminMembers: Boolean,
        val executions: Boolean,
        val promotion: Boolean,
        val workspaces: Boolean,
        val apiKeys: Boolean,
    )

    fun shell(principal: AuthenticatedPrincipal?): Shell {
        if (principal == null) return NO_SHELL
        val adminUsers = principal.isSuperAdmin
        val roles = roles(principal)
        return Shell(
            adminUsers = adminUsers,
            adminMembers = !adminUsers && roles.canAdminWorkspace,
            executions = roles.canReadExecutions,
            promotion = roles.canReadPromotion,
            workspaces = roles.canAdminWorkspace || principal.isSuperAdmin || principal.workspace == null,
            // `/api-keys` resolves the ACTIVE workspace, so a super admin with none keeps the
            // instance entry only — a link whose page must refuse is the dead link this model
            // exists to prevent.
            apiKeys = roles.canAdminWorkspace || (adminUsers && principal.workspace != null),
        )
    }

    private val NO_SHELL =
        Shell(adminUsers = false, adminMembers = false, executions = false, promotion = false, workspaces = false, apiKeys = false)

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
        model.addAttribute("canReadExecutions", roles.canReadExecutions)
        model.addAttribute("canAuthor", roles.canAuthor)
        model.addAttribute("canReadPromotion", roles.canReadPromotion)
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
     * table and the workspace list print about somebody else: the role's own word
     * ([WorkspaceRole.label]). `superAdmin` is a property of the USER, not of the row, so it
     * never appears here: a row says what the MEMBERSHIP carries, and an instance super admin's
     * authority is not one.
     */
    fun labelOf(role: WorkspaceRole): String = role.label

    /**
     * The word the shell shows next to the workspace name. A super admin reads `super admin`
     * in every workspace, including ones they hold no explicit membership in (D7), because
     * that is the authority they are acting with; everyone else reads their role.
     *
     */
    private fun label(
        superAdmin: Boolean,
        role: WorkspaceRole,
    ): String = if (superAdmin) "super admin" else role.label

    /**
     * The answer for "no principal, or no reachable workspace": nothing is rendered. The label
     * is still `viewer` rather than empty so the shell's badge never renders blank — a badge
     * with no word in it reads as a broken screen, not as an absent role.
     */
    val NONE =
        Roles(
            canRead = false,
            canExecute = false,
            canReadExecutions = false,
            canAuthor = false,
            canReadPromotion = false,
            canPromote = false,
            canAdminWorkspace = false,
            isSuperAdmin = false,
            roleLabel = "viewer",
        )
}
