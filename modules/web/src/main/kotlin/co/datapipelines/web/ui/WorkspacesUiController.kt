package co.datapipelines.web.ui

import co.datapipelines.auth.AuthException
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthProperties
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Capability
import co.datapipelines.auth.JwtService
import co.datapipelines.auth.LoginMethod
import co.datapipelines.auth.MembershipFlags
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.auth.UserService
import co.datapipelines.auth.WorkspaceService
import co.datapipelines.auth.WorkspaceSessionRequiredException
import co.datapipelines.auth.sessionCookie
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

/**
 * The workspace screens' actions (ui-screens.md §4.13): create, members and their three role
 * flags, display name, deactivate/reactivate, delete, and the shell switcher's re-stamp.
 *
 * Every action delegates to [WorkspaceService] — the same methods the REST surface (§17) calls,
 * so the last-admin rule, the `admin -> author` normalisation and the membership checks are
 * enforced ONCE. The UI owns only binding and the redirect choreography; expected refusals
 * bounce back as `?error=<code suffix>`, which the layout renders into the §5.1 toast stack.
 *
 * The `open-join` handler is gone with the provisioning modes it served (D-R11): workspaces are
 * created by super admins, there is no joinable list, and the route had been unreachable from
 * any screen since 112 while still declaring a capability it should never have had.
 */
@Controller
class WorkspacesUiController(
    private val workspaceService: WorkspaceService,
    private val userService: UserService,
    private val jwtService: JwtService,
    private val authProperties: AuthProperties,
    private val themeResolver: ThemeResolver,
) {
    @GetMapping("/workspaces")
    @RequiredScope(ScopeMatrix.RestOperation.WORKSPACES_READ)
    fun screen(
        model: Model,
        request: HttpServletRequest,
    ): String {
        val principal = requirePrincipal()
        val activeWorkspace = principal.workspace?.name
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        RoleModel.stamp(model, principal)
        val memberships = workspaceService.listOwn(principal)
        model.addAttribute(
            "own",
            memberships.map { WorkspaceRowView.of(it, activeWorkspace, principal.isSuperAdmin) },
        )
        // D-R11: workspaces are created by super admins. `canCreate` stays a distinct
        // attribute from `isSuperAdmin` because the CREATE FORM is the one thing on this
        // screen a super admin sees on an instance with nothing else to show.
        model.addAttribute("canCreate", principal.isSuperAdmin)
        // The member listing a workspace ADMIN manages (the screen's second half); viewers and
        // authors see their own role via the switcher's badge instead. Deactivated workspaces
        // are not administered from here — reactivate first (auth.md 11A.2).
        model.addAttribute(
            "managed",
            memberships
                .filter { it.workspaceActive }
                .filter { Capability.WS_ADMIN.satisfiedBy(it.flags) || principal.isSuperAdmin }
                .associate { membership ->
                    membership.workspaceName to
                        runCatching { workspaceService.members(principal, membership.workspaceName) }
                            .getOrDefault(emptyList())
                            .map(MemberRowView::of)
                },
        )
        // Section C.3(a) — zero ACTIVE memberships is the no-workspace state. Decided HERE
        // rather than by a redirect so there is one answer and one place to change it.
        //
        // KNOWN GAP, stated out loud rather than pretended away: this branch cannot be
        // reached through HTTP today. `ScopeInterceptor` judges every governed route through
        // `ScopeMatrix.allowed`, whose null-context arm refuses BEFORE any handler runs — so a
        // principal with no reachable workspace gets `404 workspace.not_found` on `/workspaces`
        // itself, as JSON, in the browser. Making the page reachable needs ONE change outside
        // this round's fence: `WORKSPACES_READ` must survive a null context, because "list the
        // workspaces you belong to" is the one operation that is meaningful with none. The
        // branch, the template and their unit tests are here so that change is a one-liner.
        if (memberships.none { it.workspaceActive }) return "workspaces/none"
        return "workspaces/index"
    }

    /** The create action; refusals (mode, name, duplicate) bounce back with the message. */
    @PostMapping("/workspaces/create")
    @RequiredScope(ScopeMatrix.RestOperation.WORKSPACE_CREATE)
    fun create(
        @RequestParam name: String,
        @RequestParam(required = false) displayName: String?,
    ): String =
        action("created") {
            workspaceService.create(
                requireSessionPrincipal(),
                name.trim(),
                displayName?.trim()?.takeIf { it.isNotEmpty() } ?: name.trim(),
            )
        }

    /**
     * A workspace admin adds a member by email, WITH the three role flags.
     *
     * `MANAGE_WORKSPACE_MEMBERS`, not `MANAGE_WORKSPACE`: the two carry the same capability
     * today ([Capability.WS_ADMIN]) but they are not the same OPERATION, and the annotation is
     * how a handler says which section-7.6 row it implements. A display-name editor is not a
     * member manager, and the REST twin has declared the members operation since 112 — the
     * UI's coarser one was drift, not a decision.
     *
     * The flags go through the SAME [WorkspaceService.addMember] the REST surface calls, so the
     * `admin -> author` normalisation and the membership checks are enforced once; a client
     * that posts `admin=true` with no `author` still gets a row satisfying the V23 constraint.
     */
    @PostMapping("/workspaces/{name}/members")
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_WORKSPACE_MEMBERS)
    fun addMember(
        @PathVariable name: String,
        @RequestParam email: String,
        @RequestParam(required = false) author: Boolean?,
        @RequestParam(required = false) promoter: Boolean?,
        @RequestParam(required = false) admin: Boolean?,
    ): String =
        action("member_added") {
            workspaceService.addMember(requireSessionPrincipal(), name, email, flagsOf(author, promoter, admin))
        }

    /**
     * Replaces a member's flags — the same [WorkspaceService.setMemberFlags] the REST
     * `PUT .../members/{userId}` calls, so the last-admin rule (`workspace.last_admin`, 409)
     * and the `admin -> author` normalisation live in ONE place. A second code path is exactly
     * the drift this round exists to remove.
     *
     * A REPLACE, not a merge: an unticked box is a flag being taken away, and a form that posts
     * only what is ticked cannot express that any other way.
     */
    @PostMapping("/workspaces/{name}/members/{userId}/flags")
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_WORKSPACE_MEMBERS)
    fun setMemberFlags(
        @PathVariable name: String,
        @PathVariable userId: UUID,
        @RequestParam(required = false) author: Boolean?,
        @RequestParam(required = false) promoter: Boolean?,
        @RequestParam(required = false) admin: Boolean?,
    ): String =
        action("member_flags") {
            workspaceService.setMemberFlags(requireSessionPrincipal(), name, userId, flagsOf(author, promoter, admin))
        }

    /** A workspace admin removes a member; the last admin is the `workspace.last_admin` refusal. */
    @PostMapping("/workspaces/{name}/members/{userId}/remove")
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_WORKSPACE_MEMBERS)
    fun removeMember(
        @PathVariable name: String,
        @PathVariable userId: UUID,
    ): String = action("member_removed") { workspaceService.removeMember(requireSessionPrincipal(), name, userId) }

    /** The workspace's display name — a workspace admin's verb (section 7.6, `MANAGE_WORKSPACE`). */
    @PostMapping("/workspaces/{name}/display-name")
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_WORKSPACE)
    fun renameDisplay(
        @PathVariable name: String,
        @RequestParam displayName: String,
    ): String =
        action("display_name") {
            workspaceService.updateDisplayName(requireSessionPrincipal(), name, displayName.trim())
        }

    /**
     * Deactivate (D-R10 — deactivate, never delete). A super admin's instance verb, and the
     * annotation says so: `MANAGE_INSTANCE_WORKSPACES`, the same operation the REST twin
     * declares. Nothing is purged; the workspace stops being selectable and its keys, endpoints
     * and schedules stop answering.
     */
    @PostMapping("/workspaces/{name}/deactivate")
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_INSTANCE_WORKSPACES)
    fun deactivate(
        @PathVariable name: String,
    ): String = action("deactivated") { workspaceService.deactivate(requireSessionPrincipal(), name) }

    /** Reactivate — the audited, reversible other half of [deactivate]. */
    @PostMapping("/workspaces/{name}/reactivate")
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_INSTANCE_WORKSPACES)
    fun reactivate(
        @PathVariable name: String,
    ): String = action("reactivated") { workspaceService.reactivate(requireSessionPrincipal(), name) }

    /**
     * Workspace delete; `in_use` bounces back with the counts of what blocks.
     *
     * `MANAGE_INSTANCE_WORKSPACES`, matching the REST twin 112 corrected: D-R10 makes deletion
     * an instance verb and the service already required a super admin — the UI annotation was
     * the last place still claiming a workspace admin could do it.
     */
    @PostMapping("/workspaces/{name}/delete")
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_INSTANCE_WORKSPACES)
    fun delete(
        @PathVariable name: String,
    ): String = action("deleted") { workspaceService.delete(requireSessionPrincipal(), name) }

    /**
     * The shell switcher's action: resolve the target workspace (the SAME membership check
     * `DP-Workspace` gets), then RE-STAMP the session JWT's `active_workspace` claim and
     * re-issue the `dp_session` cookie — so full-page navigations follow the switch, not
     * just htmx calls (which additionally carry `DP-Workspace` from the layout's
     * hx-headers). A refused switch falls back to the workspaces screen with the error.
     */
    @PostMapping("/workspace/switch")
    @RequiredScope(ScopeMatrix.RestOperation.WORKSPACES_READ)
    fun switch(
        response: jakarta.servlet.http.HttpServletResponse,
        @RequestParam name: String,
    ): String {
        return try {
            // Session-only, and INSIDE the try: an API-key caller gets the same refusal
            // bounce as any other refused switch — and, critically, no minted cookie.
            val principal = requireSessionPrincipal()
            val target = workspaceService.resolveSwitch(principal, name.trim())
            val user = userService.snapshot(principal.userId) ?: return "redirect:/workspaces?error=unknown_user"
            // Re-minting keeps the session's login method: a switch must not turn a Google
            // session into a password one (and back into the §5A.4 gate).
            val jwt = jwtService.issue(user, target.name, principal.loginMethod ?: LoginMethod.PWD)
            response.addCookie(sessionCookie(jwt, authProperties))
            // 033: the signed-in landing page moved to /dashboard (`/` is the public site).
            "redirect:/dashboard"
        } catch (_: AuthException) {
            "redirect:/workspaces?error=switch_refused"
        }
    }

    /**
     * The three checkboxes as a [MembershipFlags]. An absent box is `false` — an HTML checkbox
     * sends nothing when unticked, which is exactly "this flag is off" for a REPLACE.
     *
     * `admin` is NOT expanded to `author` here: [WorkspaceService] normalises it and the
     * database constrains it (`chk_workspace_member_admin_authors`), and a third copy of the
     * invariant in a form binder is a third place for it to go wrong.
     */
    private fun flagsOf(
        author: Boolean?,
        promoter: Boolean?,
        admin: Boolean?,
    ): MembershipFlags =
        MembershipFlags(author = author == true, promoter = promoter == true, admin = admin == true)

    /** One shared outcome wrapper: run the action, bounce back with ok/error, never a raw error page. */
    private fun action(
        ok: String,
        block: () -> Any?,
    ): String =
        try {
            block()
            "redirect:/workspaces?ok=$ok"
        } catch (_: WorkspaceService.UnknownMemberEmailException) {
            // The template's `user_not_found` banner (022 review F8) — the exception is an
            // IllegalStateException, so the AuthException-only catch let it escape as a 500.
            "redirect:/workspaces?error=user_not_found"
        } catch (e: AuthException) {
            "redirect:/workspaces?error=${e.code.substringAfterLast('.')}"
        }

    private fun requirePrincipal(): AuthenticatedPrincipal =
        SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal
            ?: error("No authenticated principal")

    /**
     * The session-only gate for every MUTATING action on this controller (D3).
     *
     * These are browser form posts; the REST surface under `/api/v1/workspaces` is the
     * programmatic one. An API-key principal must never drive them, and [switch] is the
     * sharp case: it MINTS a `dp_session` cookie from `scopesFor(user)` — the USER's
     * scopes, not the KEY's — so without this gate a `read`-scoped agent key could trade
     * itself for an author/admin session, and a key pinned to one workspace could mint a
     * session for another. That is exactly the skeleton-key outcome
     * [co.datapipelines.auth.WorkspaceResolutionFilter] refuses `DP-Workspace` to prevent;
     * a key's workspace is pinned at issuance, and scope is a property of the credential,
     * not of its owner.
     *
     * Reachable at all because an API key authenticates on EVERY path (`ApiKeyFilter` has
     * no path test) and is CSRF-exempt (`ApiKeyCredentialMatcher`). [switch] additionally
     * stays floored at `Scope.READ` by design (WORKSPACES_READ, §7.6), so the annotation
     * alone would pass a read key straight into the session mint — this gate is what
     * refuses it. The sibling workspace actions have been floored at `author` since the
     * 025 defect round, so the interceptor stops a read key before them; the gate remains
     * their deliberate second line (scope is a property of the credential, and these are
     * browser form posts a key must never drive, whatever floor the matrix carries).
     *
     * Carries the dedicated `workspace.session_required` (§13.12, 025 A2). The 96240ed
     * hotfix reused `workspace.header_forbidden` because a new code needs the constant,
     * the doc row and the drift counts in one commit — too much for a pre-merge hotfix;
     * this is the recorded follow-up landing it properly.
     */
    private fun requireSessionPrincipal(): AuthenticatedPrincipal {
        val principal = requirePrincipal()
        if (principal.authMethod != AuthMethod.OIDC) {
            throw WorkspaceSessionRequiredException()
        }
        return principal
    }
}
