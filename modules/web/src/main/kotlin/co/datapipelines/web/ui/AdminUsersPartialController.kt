package co.datapipelines.web.ui

import co.datapipelines.auth.AuthException
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.LocalPasswordService
import co.datapipelines.auth.MembershipFlags
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.Scope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.auth.SessionRequiredException
import co.datapipelines.auth.UserService
import co.datapipelines.auth.WorkspaceService
import co.datapipelines.web.api.currentPrincipal
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

/**
 * The admin user-management screen's htmx partials (ui-screens.md §4.12).
 *
 * Every response that carries a row renders `partials/admin-users-rows`' fragments from the
 * one [AdminUsersBrowseModel] the page also renders through (097 §C). This controller built
 * its own `<tr>`s as Kotlin strings until then — the pattern 091 deleted for the API-key
 * table.
 */
@Controller
class AdminUsersPartialController(
    private val userService: UserService,
    private val localPasswordService: LocalPasswordService,
    private val browse: AdminUsersBrowseModel,
    private val workspaces: WorkspaceService,
) {
    @GetMapping("/partials/admin/users")
    @RequiredScope(ScopeMatrix.RestOperation.USER_ADMINISTRATION)
    fun search(
        model: Model,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false, defaultValue = "0") offset: Int,
        @RequestParam(required = false, defaultValue = "${AdminUsersBrowseModel.DEFAULT_LIMIT}") limit: Int,
    ): String {
        requireAdmin()
        return browse.fillList(model, q, offset, limit)
    }

    /**
     * Create a local account (auth.md §5A.1 — admin-created only; there is no
     * self-registration). The one-time password is shown to the admin exactly
     * once, out-of-band into `#admin-notice`; the new row prepends to the table.
     *
     * The optional workspace + flags (113 §B.3) put the new account into a workspace
     * IN THE SAME ACT: no invitation is needed because the row exists by the time the
     * membership is written — [WorkspaceService.addMember] resolves the freshly created
     * row directly. The flags ride the workspace's own rules (a super admin caller
     * passes the capability gate via D-R8; `admin` normalises to admin+author in the
     * service). An unknown or unreachable workspace is the workspace surface's own
     * catalogued refusal, rendered as a toast — the USER ROW still exists, and the
     * admin can add it to a workspace afterwards from the workspaces screen.
     */
    @PostMapping("/partials/admin/users")
    @RequiredScope(ScopeMatrix.RestOperation.USER_ADMINISTRATION)
    fun createLocalUser(
        model: Model,
        @RequestParam email: String,
        @RequestParam(required = false, defaultValue = "") displayName: String = "",
        @RequestParam(required = false, defaultValue = "") workspace: String = "",
        @RequestParam(required = false, defaultValue = "false") author: Boolean = false,
        @RequestParam(required = false, defaultValue = "false") promoter: Boolean = false,
        @RequestParam(required = false, defaultValue = "false") admin: Boolean = false,
    ): Any {
        requireSessionAdmin("create-local-user")
        if (email.isBlank() || !email.contains('@')) {
            return refusedToast(HttpStatus.BAD_REQUEST, "User not created", "A valid email address is required")
        }
        return when (val result = localPasswordService.createLocalUser(email, displayName, currentPrincipal().userId)) {
            is LocalPasswordService.CreateResult.EmailTaken -> {
                refusedToast(HttpStatus.CONFLICT, "User not created", "An account with that email already exists")
            }

            is LocalPasswordService.CreateResult.Success -> {
                // Shape A: the row prepends, the one-time password stays in its PERSISTENT
                // inline notice (§5.1's hard rule), and the toast only POINTS at it.
                saved(
                    model,
                    result.user,
                    oneTimePassword = result.oneTimePassword,
                    variant = "success",
                    title = "Local user created",
                    message =
                        result.user.email +
                            " — the one-time password is shown once on this screen; pass it to the user out-of-band." +
                            membershipNote(result.user.email, workspace.trim(), author, promoter, admin),
                )
            }
        }
    }

    /**
     * The optional half of [createLocalUser]: add the freshly created account to
     * [workspace], and SAY SO in the toast. Null when no workspace was requested.
     *
     * An unknown or unreachable workspace is the workspace surface's own catalogued
     * refusal — logged, and reported in the note with its code — while the USER ROW
     * still exists: the admin's repair is the workspaces screen, not a retry here.
     */
    private fun membershipNote(
        userEmail: String,
        workspace: String,
        author: Boolean,
        promoter: Boolean,
        admin: Boolean,
    ): String? {
        if (workspace.isBlank()) return null
        return try {
            when (
                val outcome =
                    workspaces.addMember(
                        currentPrincipal(),
                        workspace,
                        userEmail,
                        MembershipFlags(author = author, promoter = promoter, admin = admin),
                    )
            ) {
                // The row was JUST created, so the invitation branch is unreachable here —
                // a race between two creators could land here once in a product's lifetime,
                // and the honest message still tells the admin what exists.
                is WorkspaceService.AddMemberOutcome.Invited -> {
                    " — invited to '$workspace' (an account with that email appeared meanwhile)."
                }

                is WorkspaceService.AddMemberOutcome.Added -> {
                    " — also a member of '$workspace'."
                }
            }
        } catch (e: AuthException) {
            log.warn(
                "event=admin_user_workspace_add_refused user={} workspace={} code={}",
                userEmail,
                workspace,
                e.code,
            )
            " — but adding to '$workspace' was refused (${e.code}); add them from the workspaces screen."
        }
    }

    @PatchMapping("/partials/admin/users/{userId}/{action}")
    @RequiredScope(ScopeMatrix.RestOperation.USER_ADMINISTRATION)
    fun toggle(
        model: Model,
        @PathVariable userId: UUID,
        @PathVariable action: String,
    ): Any {
        requireAdmin()
        val actor = currentPrincipal().userId

        // reset-password has its own response shape (the row PLUS the one-time
        // password notice) — handled outside the row-swap when below.
        if (action == "reset-password") {
            requireSessionAdmin(action)
            return resetPassword(model, userId, actor)
        }
        // The credential-minting subset of the row-swap actions. `unlock` clears a lockout
        // and `disable-local` removes the local credential — both change who can hold an
        // interactive session, so neither is drivable by a key. See [requireSessionAdmin].
        if (action in CREDENTIAL_ACTIONS) requireSessionAdmin(action)

        when (action) {
            "activate" -> {
                userService.activate(userId, actor)
            }

            "deactivate" -> {
                userService.deactivate(userId, actor)
            }

            "promote" -> {
                userService.grantAdmin(userId, actor)
            }

            "demote" -> {
                userService.revokeAdmin(userId, actor)
            }

            "disable-local" -> {
                localPasswordService.disableLocalAccess(userId, actor)
            }

            "unlock" -> {
                localPasswordService.unlock(userId, actor)
            }

            else -> {
                return refusedToast(HttpStatus.BAD_REQUEST, "Action refused", "Unknown action: $action")
            }
        }
        val updated = userService.snapshot(userId) ?: return ResponseEntity.notFound().build<String>()
        // Shape A: the row keeps its #user-row outerHTML swap; the toast names the
        // action and the user it happened to.
        val (title, outcome) = actionOutcome(action)
        return saved(model, updated, oneTimePassword = null, variant = "success", title = title, message = "${updated.email} $outcome")
    }

    /**
     * A row action's response: the refreshed row, the one-time password notice when the action
     * minted one, and the toast — composed by `partials/admin-user-saved`, which renders the
     * PAGE's row fragment rather than a second copy of it.
     */
    @Suppress("LongParameterList") // one parameter per model attribute the response template reads
    private fun saved(
        model: Model,
        user: co.datapipelines.auth.User,
        oneTimePassword: String?,
        variant: String,
        title: String,
        message: String,
    ): String {
        browse.fillRow(model, user)
        model.addAttribute("oneTimePassword", oneTimePassword)
        model.addAttribute("toastVariant", variant)
        model.addAttribute("toastTitle", title)
        model.addAttribute("toastMessage", message)
        return "partials/admin-user-saved"
    }

    /** The toast copy for a completed row action — what happened, in the user's words. */
    private fun actionOutcome(action: String): Pair<String, String> =
        when (action) {
            "activate" -> "User activated" to "can sign in again."
            "deactivate" -> "User deactivated" to "can no longer sign in."
            "promote" -> "Admin granted" to "is now an administrator."
            "demote" -> "Admin revoked" to "is no longer an administrator."
            "disable-local" -> "Local access disabled" to "now signs in via the identity provider only."
            "unlock" -> "Account unlocked" to "the lockout is cleared."
            else -> error("unreachable — unknown actions returned above")
        }

    /** Admin reset: the refreshed row plus the one-time password shown exactly once. */
    private fun resetPassword(
        model: Model,
        userId: UUID,
        actor: UUID,
    ): Any {
        val oneTime = localPasswordService.resetPassword(userId, actor) ?: return ResponseEntity.notFound().build<String>()
        val updated = userService.snapshot(userId) ?: return ResponseEntity.notFound().build<String>()
        return saved(
            model,
            updated,
            oneTimePassword = oneTime,
            variant = "info",
            title = "Password reset",
            message = "The one-time password is shown once on this screen — pass it to the user out-of-band.",
        )
    }

    /**
     * Shape C (§5.1): the refusal keeps its real 4xx and its body is the toast — the
     * headers retarget it at the stack, and toast.js's bridgeErrors is what lets htmx
     * swap it at all (htmx never swaps 4xx on its own). Before the bridge, every one
     * of these refusals was INVISIBLE: htmx dropped the body and no listener existed.
     */
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

    /**
     * The instance-administration gate.
     *
     * It tested `Scope.satisfies(scopes, ADMIN)` until RBAC round 1, and that stopped meaning
     * anything: a session carries no scopes (D-R1) and no key may hold `admin` (O-2), so the
     * check refused EVERY caller — including the signed-in super admin these screens exist
     * for. Found by `LocalAdminSeedE2eTest`, whose subject is a zero-setup deployment's first
     * admin creating the second user.
     *
     * The authority is `users.is_admin`, read live off the principal (§11A.2).
     */
    private fun requireAdmin() {
        val principal = currentPrincipal()
        if (!principal.isSuperAdmin) {
            log.info("Role denied: user {} reached an admin partial without super admin", principal.userId)
            throw org.springframework.security.access
                .AccessDeniedException("Super admin required")
        }
    }

    /**
     * Admin scope PLUS an interactive session, for the operations that mint or rotate a
     * usable credential: `createLocalUser`, `reset-password`, `disable-local`, `unlock`.
     *
     * [requireAdmin] alone cannot gate these. It asks WHO the caller is, not what credential
     * they hold, so it sees a `dpk_` key and a browser session as the same principal — and
     * `ApiKeyFilter` has no path test while `ApiKeyCredentialMatcher` makes key requests
     * CSRF-exempt, so a key whose owner is a super admin reaches these partials with one
     * header. It could then create a local admin, read the one-time password out of the
     * response body ([oneTimeNotice]), sign in, and hold a `dp_session` that is not workspace-
     * pinned and survives revocation of the key that made it.
     *
     * Round 1 narrowed the credential half — no key holds `admin` SCOPE any more — but not
     * this one: the escalation rides on the OWNER's `is_admin`, which a key still carries.
     *
     * Deliberately NOT applied to `activate`/`deactivate`/`promote`/`demote`: those are
     * pre-026 behaviour, already ratified for keys through the documented
     * `/api/v1/auth/users` REST twin (§7.6 USER_ADMINISTRATION), and none of them emits a
     * credential. The line this draws is credential-minting, not privilege.
     */
    private fun requireSessionAdmin(operation: String) {
        requireAdmin()
        val principal = currentPrincipal()
        if (principal.authMethod != AuthMethod.OIDC) {
            log.info(
                "Credential-minting operation {} refused for non-session principal user={} method={}",
                operation,
                principal.userId,
                principal.authMethod,
            )
            throw SessionRequiredException(operation)
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(AdminUsersPartialController::class.java)

        /**
         * The `toggle` actions that mint or rotate an interactive credential and are
         * therefore session-only. `reset-password` is gated at its own early return above
         * (it has a different response shape), so it is deliberately absent here — the
         * companion test asserts the FULL session-only set to keep the two in step.
         */
        val CREDENTIAL_ACTIONS = setOf("disable-local", "unlock")
    }
}
