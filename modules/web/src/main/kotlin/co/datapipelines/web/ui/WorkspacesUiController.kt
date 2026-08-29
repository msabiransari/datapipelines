package co.datapipelines.web.ui

import co.datapipelines.auth.AuthException
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthProperties
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.JwtService
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.auth.UserService
import co.datapipelines.auth.WorkspaceProvisioningMode
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.auth.WorkspaceService
import co.datapipelines.auth.WorkspaceSessionRequiredException
import co.datapipelines.auth.WorkspacesProperties
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
 * The workspace screens' actions (ui-screens.md §4.13): create per provisioning mode,
 * open-join self-service, owner member management, delete, and the shell switcher's
 * re-stamp.
 *
 * Every action delegates to [WorkspaceService] — the same rules the REST surface
 * (§17) enforces; the UI owns only binding and the fragment/redirect choreography.
 * Expected refusals (duplicate name, closed mode, not an owner…) render as an inline
 * error banner on the same screen, via `?error=` query params — the login screen's
 * idiom — instead of the generic error page.
 */
@Controller
class WorkspacesUiController(
    private val workspaceService: WorkspaceService,
    private val userService: UserService,
    private val jwtService: JwtService,
    private val authProperties: AuthProperties,
    private val workspacesProperties: WorkspacesProperties,
    private val themeResolver: ThemeResolver,
) {
    @GetMapping("/workspaces")
    fun screen(
        model: Model,
        request: HttpServletRequest,
    ): String {
        val principal = requirePrincipal()
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        model.addAttribute("own", workspaceService.listOwn(principal))
        model.addAttribute("joinable", workspaceService.joinable(principal))
        model.addAttribute("openJoin", workspacesProperties.openJoin)
        model.addAttribute(
            "canCreate",
            workspacesProperties.provisioningMode != WorkspaceProvisioningMode.CLOSED || principal.isAdmin,
        )
        model.addAttribute("provisioningMode", workspacesProperties.provisioningMode.wire)
        model.addAttribute("isAdmin", principal.isAdmin)
        // The member listing a workspace OWNER manages (the screen's second half); plain
        // members see their own role via the switcher's badge instead.
        model.addAttribute(
            "managed",
            workspaceService
                .listOwn(principal)
                .filter { it.role == WorkspaceRole.OWNER || principal.isAdmin }
                .associate { membership ->
                    membership.workspaceName to
                        runCatching { workspaceService.members(principal, membership.workspaceName) }.getOrDefault(emptyList())
                },
        )
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

    /** The `open-join` self-service join — adding your own email. */
    @PostMapping("/workspaces/{name}/join")
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_WORKSPACE)
    fun join(
        @PathVariable name: String,
    ): String =
        action("joined") {
            val principal = requireSessionPrincipal()
            workspaceService.addMember(principal, name, principal.email)
        }

    /** An owner (or admin) adds a member by email. */
    @PostMapping("/workspaces/{name}/members")
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_WORKSPACE)
    fun addMember(
        @PathVariable name: String,
        @RequestParam email: String,
    ): String = action("member_added") { workspaceService.addMember(requireSessionPrincipal(), name, email) }

    /** An owner (or admin) removes a member; an owner target is the `in_use` refusal. */
    @PostMapping("/workspaces/{name}/members/{userId}/remove")
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_WORKSPACE)
    fun removeMember(
        @PathVariable name: String,
        @PathVariable userId: UUID,
    ): String = action("member_removed") { workspaceService.removeMember(requireSessionPrincipal(), name, userId) }

    /** Workspace delete; `in_use` bounces back with the counts of what blocks. */
    @PostMapping("/workspaces/{name}/delete")
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_WORKSPACE)
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
            response.addCookie(sessionCookie(jwtService.issue(user, target.name), authProperties))
            "redirect:/"
        } catch (_: AuthException) {
            "redirect:/workspaces?error=switch_refused"
        }
    }

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
     * no path test) and is CSRF-exempt (`ApiKeyCredentialMatcher`), while these handlers'
     * `@RequiredScope` floors are `Scope.READ` — so the annotation passes a read key
     * through. The floors themselves are the wider question (the sibling routes are
     * role-gated in-handler, so their exposure is bounded); this gate closes the
     * credential-minting hole outright and is deliberately independent of them.
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
