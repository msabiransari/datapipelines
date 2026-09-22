package co.datapipelines.web.ui

import co.datapipelines.auth.AuthException
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthProperties
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.JwtService
import co.datapipelines.auth.LoginMethod
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.auth.UserService
import co.datapipelines.auth.WorkspaceLastAdminException
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.auth.WorkspaceSelfMembershipException
import co.datapipelines.auth.WorkspaceService
import co.datapipelines.auth.WorkspaceSessionRequiredException
import co.datapipelines.auth.sessionCookie
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

/**
 * The workspace screens' actions (ui-screens.md §4.13): create, members and their ONE role
 * (D22), display name, deactivate/reactivate, delete, and the shell switcher's re-stamp.
 *
 * Every action delegates to [WorkspaceService] — the same methods the REST surface (§17) calls,
 * so the last-admin rule and the membership checks are enforced ONCE. The UI owns only binding
 * and the redirect choreography; expected refusals bounce back as `?error=<code suffix>`, which
 * the layout renders into the §5.1 toast stack. The one htmx partial — the member's role
 * dropdown's Save ([setMemberRole]) — swaps the row in place and toasts out-of-band instead.
 *
 * The page itself is a workspace admin's (D13, `WORKSPACES_READ` → `ws_admin`); the switcher
 * in the chrome stays every member's and posts to [switch] under its own operation.
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
        // D22: the role dropdown's options — the four workspace roles, in doc order. Listed by
        // the server so the template never spells a role name.
        model.addAttribute("workspaceRoles", WorkspaceRole.entries)
        // #208: the members row hides its verbs on the caller's OWN row (the service refuses them too).
        model.addAttribute("currentUserId", principal.userId)
        val memberships = workspaceService.listOwn(principal)
        model.addAttribute(
            "own",
            memberships.map { WorkspaceRowView.of(it, activeWorkspace, principal.isSuperAdmin) },
        )
        // D-R11: workspaces are created by super admins. `canCreate` stays a distinct
        // attribute from `isSuperAdmin` because the CREATE FORM is the one thing on this
        // screen a super admin sees on an instance with nothing else to show.
        model.addAttribute("canCreate", principal.isSuperAdmin)
        // The member table — for the ACTIVE workspace only, and 114 narrowed it to that on
        // evidence rather than taste.
        //
        // `ScopeInterceptor` judges `MANAGE_WORKSPACE_MEMBERS` against `principal.workspace`,
        // the ACTIVE context, never against the workspace named in the path. So a workspace
        // admin of X who is currently working in Y had every member form for X rendered and
        // every one of them refused with `auth.role_required` — a 403 the screen could not
        // even explain, because the interceptor answers before the handler and htmx does not
        // swap a 4xx (the click simply did nothing). Found by the browser suite in this round;
        // it has been true since 112 put the member verbs on the capability axis.
        //
        // Rendering only what the server will accept is this round's whole rule, so the
        // sections follow the active workspace and the others get a Switch, one row up in
        // "Your workspaces". Deactivated workspaces are not administered from here at all —
        // reactivate first (auth.md 11A.2).
        val administered =
            memberships.filter {
                it.workspaceActive && Permission.WS_ADMIN.satisfiedBy(it.role, principal.isSuperAdmin)
            }
        val listings =
            administered
                .filter { it.workspaceName == activeWorkspace }
                .associate { membership ->
                    membership.workspaceName to
                        runCatching { workspaceService.membersWithInvitations(principal, membership.workspaceName) }
                            .getOrNull()
                }
        // #200 — which members hold a live login-minted key: the row's "has a key" state and
        // the revoke verb's affordance. Owner ids only (no key id, no prefix — the members row
        // is not a key listing), resolved through the same service the verbs call.
        val keyOwners =
            listings.mapValues { (name, listing) ->
                if (listing == null) {
                    emptySet()
                } else {
                    runCatching { workspaceService.liveUserKeyOwnerIds(principal, name) }.getOrDefault(emptySet())
                }
            }
        model.addAttribute(
            "managed",
            listings.mapValues { (name, l) ->
                l?.members?.map { MemberRowView.of(it, hasKey = it.userId in keyOwners.getValue(name)) } ?: emptyList()
            },
        )
        // 113 — invitations are ghost rows under the members, keyed the same way, never merged
        // into `managed`: a template that counts members must not count people who have not
        // signed in yet.
        model.addAttribute("pending", listings.mapValues { (_, l) -> l?.invitations?.map(InvitationRowView::of) ?: emptyList() })
        // The ones this caller administers but is not IN — named so the screen can say "switch
        // to manage" instead of silently showing nothing where a section used to be.
        //
        // EMPTY for a super admin. They administer every workspace on the instance (D-R8), so
        // the answer is "all of them" — which is not information, and on a real instance is an
        // unbounded line of names (it ran 276px off a 390px screen in the overflow sweep the
        // first time round). The switcher above already lists them, which is the same journey
        // this note describes.
        model.addAttribute(
            "manageableElsewhere",
            if (principal.isSuperAdmin) {
                emptyList()
            } else {
                memberships
                    .filter {
                        it.workspaceActive &&
                            it.workspaceName != activeWorkspace &&
                            Permission.WS_ADMIN.satisfiedBy(it.role, superAdmin = false)
                    }.map { it.workspaceName }
            },
        )
        // Section C.3(a) — zero ACTIVE memberships is the no-workspace state. Decided HERE
        // rather than by a redirect so there is one answer and one place to change it.
        //
        // Reachable because `ScopeMatrix.allowed` lets a SESSION through `WORKSPACES_READ`
        // with no workspace context (auth.md §11A.1) — "list the workspaces you belong to" is
        // one of two things meaningful with none. The other is #113's recovery carve-out: a
        // SUPER ADMIN with no reachable workspace keeps the instance verbs (this screen's
        // create form among them), while every workspace-scoped route still answers the 404
        // below any handler.
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
     * A workspace admin adds a member by email, WITH their one role (D22: the same dropdown the
     * members table uses; an unknown value bounces as `?error=unknown_role`).
     *
     * `MANAGE_WORKSPACE_MEMBERS`, not `MANAGE_WORKSPACE`: the two carry the same permission
     * today ([Permission.WS_ADMIN]) but they are not the same OPERATION, and the annotation is
     * how a handler says which section-7.6 row it implements. A display-name editor is not a
     * member manager, and the REST twin has declared the members operation since 112 — the
     * UI's coarser one was drift, not a decision.
     *
     * The role goes through the SAME [WorkspaceService.addMember] the REST surface calls, so
     * the membership checks are enforced once.
     */
    @PostMapping("/workspaces/{name}/members")
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_WORKSPACE_MEMBERS)
    fun addMember(
        @PathVariable name: String,
        @RequestParam email: String,
        @RequestParam(required = false, defaultValue = "viewer") role: String,
    ): String {
        val workspaceRole = WorkspaceRole.fromWireOrNull(role) ?: return "redirect:/workspaces?error=unknown_role"
        return outcome {
            // 113 — an email with no user row is an INVITATION, not a failure; the toast says
            // which one happened, because "added" for a person who cannot sign in yet is a lie.
            when (workspaceService.addMember(requireSessionPrincipal(), name, email, workspaceRole)) {
                is WorkspaceService.AddMemberOutcome.Added -> "member_added"
                is WorkspaceService.AddMemberOutcome.Invited -> "member_invited"
            }
        }
    }

    /**
     * Revokes a pending invitation (113) — the same [WorkspaceService.revokeInvitation] the REST
     * `DELETE .../invitations/{email}` calls. The email travels as a form field, not a path
     * segment: an address is dotted and case-folded on the way in, and a path variable would
     * make both the router's business.
     */
    @PostMapping("/workspaces/{name}/invitations/revoke")
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_WORKSPACE_MEMBERS)
    fun revokeInvitation(
        @PathVariable name: String,
        @RequestParam email: String,
    ): String = action("invitation_revoked") { workspaceService.revokeInvitation(requireSessionPrincipal(), name, email) }

    /**
     * D22 — the member row's role dropdown, saved: the ONE htmx partial on this screen.
     * Replaces the member's role through the same [WorkspaceService.setMemberRole] the REST
     * `PUT .../members/{userId}` calls, so the last-admin rule (`workspace.last_admin`, 409)
     * lives in ONE place, then answers with the re-rendered row (swapped in place of the one
     * that posted) and a toast out-of-band. A refusal is a toast alone, retargeted at the
     * stack, and the row keeps its previous selection — the browser never shows a role the
     * server refused.
     *
     * A partial rather than the redirect choreography the other verbs use because a role change
     * is the verb an admin repeats down a table: a full-page reload per row loses the scroll
     * position and re-fetches every section for one cell that changed.
     */
    @PostMapping("/partials/workspaces/{name}/members/{userId}/role")
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_WORKSPACE_MEMBERS)
    fun setMemberRole(
        model: Model,
        @PathVariable name: String,
        @PathVariable userId: UUID,
        @RequestParam role: String,
    ): Any {
        val workspaceRole =
            WorkspaceRole.fromWireOrNull(role)
                ?: return refusedToast(HttpStatus.BAD_REQUEST, "Role not changed", "Unknown workspace role '$role'.")
        return try {
            val principal = requireSessionPrincipal()
            val row = workspaceService.setMemberRole(principal, name, userId, workspaceRole)
            RoleModel.stamp(model)
            model.addAttribute("workspaceName", name)
            model.addAttribute("workspaceRoles", WorkspaceRole.entries)
            model.addAttribute("currentUserId", principal.userId)
            // The swapped-in row shows the SAME key state the page drew (#200) — the role
            // change cannot have moved it, but the fragment needs the attribute to render it.
            val hasKey = userId in workspaceService.liveUserKeyOwnerIds(principal, name)
            model.addAttribute("member", MemberRowView.of(row, hasKey = hasKey))
            model.addAttribute("toastVariant", "success")
            model.addAttribute("toastTitle", "Role changed")
            model.addAttribute("toastMessage", "${row.email} is now ${row.role.label} in $name.")
            "partials/workspace-member-row :: saved"
        } catch (e: WorkspaceSelfMembershipException) {
            refusedToast(HttpStatus.CONFLICT, "Role not changed", e.userMessage)
        } catch (_: WorkspaceLastAdminException) {
            refusedToast(
                HttpStatus.CONFLICT,
                "Role not changed",
                "This is the last workspace admin. Give someone else the workspace admin role first.",
            )
        } catch (e: AuthException) {
            refusedToast(HttpStatus.valueOf(e.status), "Role not changed", e.userMessage)
        }
    }

    /** A workspace admin removes a member; the last admin is the `workspace.last_admin` refusal. */
    @PostMapping("/workspaces/{name}/members/{userId}/remove")
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_WORKSPACE_MEMBERS)
    fun removeMember(
        @PathVariable name: String,
        @PathVariable userId: UUID,
    ): String = action("member_removed") { workspaceService.removeMember(requireSessionPrincipal(), name, userId) }

    /**
     * A workspace admin revokes a member's login-minted key (#200; roles record §3.7, ruling
     * 3) — the members-row verb beside Remove, through the SAME [WorkspaceService.revokeMemberKey]
     * the REST `DELETE .../members/{userId}/key` calls. Removal is a different act with a
     * different toast: the member stays, their key stops answering (`auth.api_key.invalid`),
     * and the next sign-in mints a fresh one.
     */
    @PostMapping("/workspaces/{name}/members/{userId}/key/revoke")
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_WORKSPACE_MEMBERS)
    fun revokeMemberKey(
        @PathVariable name: String,
        @PathVariable userId: UUID,
    ): String = action("member_key_revoked") { workspaceService.revokeMemberKey(requireSessionPrincipal(), name, userId) }

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
    @RequiredScope(ScopeMatrix.RestOperation.WORKSPACE_SWITCH)
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
            // D16: a successful switch IS an entry into that workspace — mint the user's one
            // MCP key there exactly as login does (no-op when one is already live).
            workspaceService.mintMcpKeyOnEntry(user, target)
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

    /** A partial's refusal: the toast alone, retargeted at the stack; the row that posted is left as it was. */
    private fun refusedToast(
        status: HttpStatus,
        title: String,
        message: String,
    ): ResponseEntity<String> =
        ResponseEntity
            .status(status)
            .header("HX-Retarget", "#toast")
            .header("HX-Reswap", "beforeend")
            .body(ToastHtml.oob("danger", title, ToastHtml.esc(message)))

    /** One shared outcome wrapper: run the action, bounce back with ok/error, never a raw error page. */
    private fun action(
        ok: String,
        block: () -> Any?,
    ): String =
        outcome {
            block()
            ok
        }

    /** [action] for a verb whose `ok` code depends on what the service did (113's add-or-invite). */
    private fun outcome(block: () -> String): String =
        try {
            "redirect:/workspaces?ok=${block()}"
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
