package co.datapipelines.web.ui

import co.datapipelines.auth.AuthException
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.LocalPasswordService
import co.datapipelines.auth.MailKind
import co.datapipelines.auth.MailProperties
import co.datapipelines.auth.MailSend
import co.datapipelines.auth.MailSendRepository
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.SessionRequiredException
import co.datapipelines.auth.UserService
import co.datapipelines.auth.WorkspaceRole
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
 *
 * ## What the screen shows when mail is on (137, auth.md §5A.8)
 * With `datapipelines.mail` configured ([MailProperties.enabled]) the create and reset
 * responses no longer carry the one-time password: it went to the user, and two copies of a
 * credential are one too many. The notice says "Emailed to <address>" with the claim row's
 * outcome — `sending…` (polled through [mailStatus] until terminal), `sent`, or `failed` with
 * the error. With mail off, the password is shown exactly as before. One branch, in the
 * `partials/admin-user-saved` template.
 */
@Controller
class AdminUsersPartialController(
    private val userService: UserService,
    private val localPasswordService: LocalPasswordService,
    private val browse: AdminUsersBrowseModel,
    private val workspaces: WorkspaceService,
    private val mailProperties: MailProperties,
    private val mailSends: MailSendRepository,
) {
    @GetMapping("/partials/admin/users")
    @RequiredScope(Permission.USER_MANAGE)
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
     * The optional workspace + role (113 §B.3, D22) put the new account into a workspace
     * IN THE SAME ACT: no invitation is needed because the row exists by the time the
     * membership is written — [WorkspaceService.addMember] resolves the freshly created
     * row directly. The role rides the workspace's own rules (a super admin caller
     * passes the permission gate via D-R8). An unknown or unreachable workspace is the workspace surface's own
     * catalogued refusal, rendered as a toast — the USER ROW still exists, and the
     * admin can add it to a workspace afterwards from the workspaces screen.
     */
    @PostMapping("/partials/admin/users")
    @RequiredScope(Permission.USER_MANAGE)
    fun createLocalUser(
        model: Model,
        @RequestParam email: String,
        @RequestParam(required = false, defaultValue = "") displayName: String = "",
        @RequestParam(required = false, defaultValue = "") workspace: String = "",
        @RequestParam(required = false, defaultValue = "viewer") role: String = "viewer",
    ): Any {
        requireSessionAdmin("create-local-user")
        if (email.isBlank() || !email.contains('@')) {
            return refusedToast(HttpStatus.BAD_REQUEST, "User not created", "A valid email address is required")
        }
        val requestedWorkspace = workspace.trim().ifEmpty { null }
        // Refused BEFORE the account is created: a role outside the four is a form nobody
        // rendered, and creating the user first would leave a row behind a 400.
        val workspaceRole =
            WorkspaceRole.fromWireOrNull(role)
                ?: return refusedToast(HttpStatus.BAD_REQUEST, "User not created", "Unknown workspace role '$role'")
        val result = localPasswordService.createLocalUser(email, displayName, currentPrincipal().userId, requestedWorkspace)
        return when (result) {
            is LocalPasswordService.CreateResult.EmailTaken -> {
                refusedToast(HttpStatus.CONFLICT, "User not created", "An account with that email already exists")
            }

            is LocalPasswordService.CreateResult.Success -> {
                val note = membershipNote(result.user.email, workspace.trim(), workspaceRole)
                if (mailProperties.enabled) {
                    // 137: the credential went to the user. The notice carries the send's outcome
                    // (the claim row exists already — it is written on the request thread).
                    saved(
                        model,
                        result.user,
                        oneTimePassword = null,
                        mailNotice =
                            mailNotice(
                                result.user,
                                MailKind.WELCOME,
                                mailSends.find(result.user.id, MailKind.WELCOME, result.user.id),
                            ),
                        variant = "success",
                        title = "Local user created",
                        message = result.user.email + " — the one-time password was emailed to them." + note,
                    )
                } else {
                    // Shape A: the row prepends, the one-time password stays in its PERSISTENT
                    // inline notice (§5.1's hard rule), and the toast only POINTS at it.
                    saved(
                        model,
                        result.user,
                        oneTimePassword = result.oneTimePassword,
                        mailNotice = null,
                        variant = "success",
                        title = "Local user created",
                        message =
                            result.user.email +
                                " — the one-time password is shown once on this screen; pass it to the user out-of-band." +
                                note,
                    )
                }
            }
        }
    }

    /**
     * 137: the outcome of one notice, for the admin screen — polled by the `admin-user-mail`
     * fragment while the claim is still pending. Read-only and super-admin gated like every
     * partial here; it reveals whether a mail went, never what it said.
     */
    @GetMapping("/partials/admin/users/{userId}/mail/{kind}")
    @RequiredScope(Permission.USER_MANAGE)
    fun mailStatus(
        model: Model,
        @PathVariable userId: UUID,
        @PathVariable kind: String,
        @RequestParam act: UUID,
    ): Any {
        requireAdmin()
        val mailKind =
            MailKind.entries.firstOrNull { it.wire == kind }
                ?: return refusedToast(HttpStatus.BAD_REQUEST, "Unknown notice", "Unknown mail kind: $kind")
        val user = userService.administrableUser(userId) ?: return ResponseEntity.notFound().build<String>()
        model.addAttribute("mailNotice", mailNotice(user, mailKind, mailSends.find(userId, mailKind, act)))
        return "partials/admin-user-mail :: notice"
    }

    /** What the notice fragment renders: the recipient, the state, the error, and the poll URL while pending. */
    data class MailNoticeView(
        val to: String,
        val kind: String,
        val status: String,
        val error: String?,
        /** Non-null while the send is still pending — the fragment polls it. */
        val pollUrl: String?,
    )

    private fun mailNotice(
        user: co.datapipelines.auth.User,
        kind: MailKind,
        claim: MailSend?,
    ): MailNoticeView {
        val status = claim?.status
        return MailNoticeView(
            to = user.email,
            kind = kind.wire,
            status =
                when (status) {
                    null -> "none"
                    MailSend.Status.PENDING -> "sending"
                    MailSend.Status.SENT -> "sent"
                    MailSend.Status.FAILED -> "failed"
                },
            error = claim?.error,
            pollUrl =
                claim?.takeIf { it.status == MailSend.Status.PENDING }?.let {
                    "/partials/admin/users/${user.id}/mail/${kind.wire}?act=${it.actId}"
                },
        )
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
        role: WorkspaceRole,
    ): String? {
        if (workspace.isBlank()) return null
        return try {
            when (
                val outcome =
                    workspaces.addMember(
                        currentPrincipal(),
                        workspace,
                        userEmail,
                        role,
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

    /**
     * The row's action endpoint: the admin-users page's existing verb family (route prefix,
     * the `PATCH` + row-swap shape, CSRF from the layout). The identity reset (#187) has its
     * OWN literal route below — a new `RestOperation` — but renders through the same
     * [saved] response the family shares.
     */
    @PatchMapping("/partials/admin/users/{userId}/{action}")
    @RequiredScope(Permission.USER_MANAGE)
    @Suppress("ReturnCount") // each guard is an early answer BEFORE any mutation; the order is the contract (A.6)
    fun toggle(
        model: Model,
        @PathVariable userId: UUID,
        @PathVariable action: String,
    ): Any {
        requireAdmin()
        val actor = currentPrincipal().userId
        // #215 A.6/A3 — the lookup comes BEFORE any mutation: an unknown row, the System account
        // and a key's identity are all "no such user" here. An identity is managed only through
        // its key; granting one admin would have made its key a super admin.
        if (userService.administrableUser(userId) == null) return ResponseEntity.notFound().build<String>()

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
        val updated = userService.administrableUser(userId) ?: return ResponseEntity.notFound().build<String>()
        // Shape A: the row keeps its #user-row outerHTML swap; the toast names the
        // action and the user it happened to.
        val (title, outcome) = actionOutcome(action)
        return saved(
            model,
            updated,
            oneTimePassword = null,
            mailNotice = null,
            variant = "success",
            title = title,
            message = "${updated.email} $outcome",
        )
    }

    /**
     * #187 — reset a user's linked sign-in identity to the bootstrap placeholder, so the
     * NEXT OIDC sign-in with that email claims the row. The explicit answer to
     * `auth.login.identity_mismatch`: a login never re-links silently, so moving an account
     * between sign-in identities is this super-admin decision, audited
     * (`auth.user.identity_reset`). Its own literal route and its own operation
     * (`USER_IDENTITY_RESET` — see the matrix) beside the `{action}` family above, whose
     * response shape it shares. Liveness, admin flag, password and memberships are
     * untouched: a deactivated user stays deactivated (180).
     */
    @PatchMapping("/partials/admin/users/{userId}/identity-reset")
    @RequiredScope(Permission.USER_IDENTITY_RESET)
    fun resetIdentity(
        model: Model,
        @PathVariable userId: UUID,
    ): Any {
        requireAdmin()
        val actor = currentPrincipal().userId
        // #215 A.6 — looked up BEFORE the reset: a non-person row is not found here, so the
        // `bootstrap` flip can never make the System account or a key's identity claimable.
        if (userService.administrableUser(userId) == null) return ResponseEntity.notFound().build<String>()
        userService.resetIdentity(userId, actor)
        val updated = userService.administrableUser(userId) ?: return ResponseEntity.notFound().build<String>()
        return saved(
            model,
            updated,
            oneTimePassword = null,
            mailNotice = null,
            variant = "success",
            title = "Identity reset",
            message = "${updated.email} can be claimed at the next sign-in with that email.",
        )
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
        mailNotice: MailNoticeView?,
        variant: String,
        title: String,
        message: String,
    ): String {
        browse.fillRow(model, user)
        model.addAttribute("oneTimePassword", oneTimePassword)
        model.addAttribute("mailNotice", mailNotice)
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
        val updated = userService.administrableUser(userId) ?: return ResponseEntity.notFound().build<String>()
        if (mailProperties.enabled) {
            // 137: the reset's claim is the LATEST password_reset row — claimed on this very
            // request thread, so it is the one the reset above minted.
            return saved(
                model,
                updated,
                oneTimePassword = null,
                mailNotice = mailNotice(updated, MailKind.PASSWORD_RESET, mailSends.latest(userId, MailKind.PASSWORD_RESET)),
                variant = "info",
                title = "Password reset",
                message = "The new one-time password was emailed to ${updated.email}.",
            )
        }
        return saved(
            model,
            updated,
            oneTimePassword = oneTime,
            mailNotice = null,
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
