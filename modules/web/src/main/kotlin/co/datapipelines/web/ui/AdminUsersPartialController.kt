package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.LocalPasswordService
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.Scope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.auth.SessionRequiredException
import co.datapipelines.auth.UserService
import co.datapipelines.web.api.currentPrincipal
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.Instant
import java.util.UUID

@Controller
class AdminUsersPartialController(
    private val userService: UserService,
    private val localPasswordService: LocalPasswordService,
) {
    @GetMapping("/partials/admin/users")
    @RequiredScope(ScopeMatrix.RestOperation.USER_ADMINISTRATION)
    fun search(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false, defaultValue = "0") offset: Int,
        @RequestParam(required = false, defaultValue = "20") limit: Int,
    ): ResponseEntity<String> {
        requireAdmin()
        val clampedOffset = offset.coerceAtLeast(0)
        val clampedLimit = limit.coerceIn(MIN_LIMIT, MAX_LIMIT)
        val results = userService.search(q.orEmpty(), clampedOffset, clampedLimit)
        val html = buildUserTable(results)
        return ResponseEntity.ok(html)
    }

    /**
     * Create a local account (auth.md §5A.1 — admin-created only; there is no
     * self-registration). The one-time password is shown to the admin exactly
     * once, out-of-band into `#admin-notice`; the new row prepends to the table.
     */
    @PostMapping("/partials/admin/users")
    @RequiredScope(ScopeMatrix.RestOperation.USER_ADMINISTRATION)
    fun createLocalUser(
        @RequestParam email: String,
        @RequestParam(required = false, defaultValue = "") displayName: String,
    ): ResponseEntity<String> {
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
                ResponseEntity.ok(
                    buildUserRow(result.user) +
                        oneTimeNotice(result.oneTimePassword, result.user.email) +
                        ToastHtml.oob(
                            "success",
                            "Local user created",
                            ToastHtml.esc(result.user.email) +
                                " — the one-time password is shown once on this screen; pass it to the user out-of-band.",
                        ),
                )
            }
        }
    }

    @PatchMapping("/partials/admin/users/{userId}/{action}")
    @RequiredScope(ScopeMatrix.RestOperation.USER_ADMINISTRATION)
    fun toggle(
        @PathVariable userId: UUID,
        @PathVariable action: String,
    ): ResponseEntity<String> {
        requireAdmin()
        val actor = currentPrincipal().userId

        // reset-password has its own response shape (the row PLUS the one-time
        // password notice) — handled outside the row-swap when below.
        if (action == "reset-password") {
            requireSessionAdmin(action)
            return resetPassword(userId, actor)
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
        val updated = userService.snapshot(userId) ?: return ResponseEntity.notFound().build()
        // Shape A: the row keeps its #user-row outerHTML swap; the toast names the
        // action and the user it happened to.
        val (title, outcome) = actionOutcome(action)
        return ResponseEntity.ok(
            buildUserRow(updated) +
                ToastHtml.oob("success", title, "${ToastHtml.esc(updated.email)} $outcome"),
        )
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
        userId: UUID,
        actor: UUID,
    ): ResponseEntity<String> {
        val oneTime = localPasswordService.resetPassword(userId, actor) ?: return ResponseEntity.notFound().build()
        val updated = userService.snapshot(userId) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(
            buildUserRow(updated) +
                oneTimeNotice(oneTime, updated.email) +
                ToastHtml.oob(
                    "info",
                    "Password reset",
                    "The one-time password is shown once on this screen — pass it to the user out-of-band.",
                ),
        )
    }

    private fun buildUserTable(users: List<co.datapipelines.auth.User>): String {
        if (users.isEmpty()) {
            return EMPTY_ROW_HTML
        }
        return users.joinToString("\n") { buildUserRow(it) }
    }

    private fun buildUserRow(user: co.datapipelines.auth.User): String {
        val activeStatus = if (user.isActive) "Active" else "Inactive"
        val role = if (user.isAdmin) "Admin" else "User"
        // Status and role render as design-system badges (029): a restriction/state gets the
        // semantic variant — success/danger for active, primary/default for the role.
        val activeBadge = if (user.isActive) "ds-badge-success" else "ds-badge-danger"
        val roleBadge = if (user.isAdmin) "ds-badge-primary" else "ds-badge-default"
        val userIdShort = user.id.toString().take(USER_ID_PREFIX_LEN)
        val locked = user.lockedUntil?.isAfter(Instant.now()) == true
        val localStatus =
            when {
                user.hasLocalPassword && locked -> "local · locked"
                user.hasLocalPassword -> "local"
                else -> "—"
            }

        return """<tr id="user-row-${user.id}">
          <td title="${user.id}">$userIdShort...</td>
          <td>${esc(user.displayName)}</td>
          <td>${esc(user.email)}</td>
          <td><span class="ds-badge $activeBadge">$activeStatus</span></td>
          <td><span class="ds-badge $roleBadge">$role</span></td>
          <td>$localStatus</td>
          <td style="white-space:nowrap">${toggleButton(user)} ${roleButton(user)}${localButtons(user, locked)}</td>
        </tr>"""
    }

    private fun actionButton(
        user: co.datapipelines.auth.User,
        action: String,
        label: String,
        color: String,
    ): String =
        """<button class="ds-button ds-button-ghost ds-button-sm" """ +
            """hx-patch="/partials/admin/users/${user.id}/$action" """ +
            """hx-target="#user-row-${user.id}" hx-swap="outerHTML" """ +
            """style="color:$color">$label</button>"""

    private fun toggleButton(user: co.datapipelines.auth.User): String =
        if (user.isActive) {
            actionButton(user, "deactivate", "Deactivate", COLOR_DANGER)
        } else {
            actionButton(user, "activate", "Activate", COLOR_SUCCESS)
        }

    private fun roleButton(user: co.datapipelines.auth.User): String =
        if (user.isAdmin) {
            actionButton(user, "demote", "Demote", COLOR_WARNING)
        } else {
            actionButton(user, "promote", "Promote", COLOR_WARNING)
        }

    /**
     * Local-account operations (auth.md §5A.1): reset is also the unlock path;
     * disable makes the account OIDC-only again; unlock clears the lockout only.
     */
    private fun localButtons(
        user: co.datapipelines.auth.User,
        locked: Boolean,
    ): String =
        buildString {
            if (user.hasLocalPassword) {
                append(" " + actionButton(user, "reset-password", "Reset PW", COLOR_WARNING))
                append(" " + actionButton(user, "disable-local", "Disable local", COLOR_DANGER))
            }
            if (locked) {
                append(" " + actionButton(user, "unlock", "Unlock", COLOR_SUCCESS))
            }
        }

    /** The one-time credential, shown to the admin ONCE via out-of-band swap — never stored retrievably. */
    private fun oneTimeNotice(
        oneTimePassword: String,
        email: String,
    ): String =
        """<div id="admin-notice" hx-swap-oob="true" style="background:var(--accent-warning-bg);color:var(--accent-warning);""" +
            """padding:var(--gap-sm);border-radius:var(--radius-base);margin-bottom:var(--gap-md);font-size:var(--text-sm)">""" +
            "One-time password for ${esc(email)}: <strong style=\"font-family:var(--font-mono)\">${esc(oneTimePassword)}</strong>" +
            " — shown ONCE; pass it to the user out-of-band. They must set a new password at first login (auth.md §5A.4).</div>"

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

    private fun esc(text: String?): String =
        (text ?: "")
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private fun requireAdmin() {
        val principal = currentPrincipal()
        if (!Scope.satisfies(principal.scopes, Scope.ADMIN)) {
            log.info(
                "Scope denied: user {} accessing admin partial with scopes {}",
                principal.userId,
                principal.scopes,
            )
            throw org.springframework.security.access
                .AccessDeniedException("Admin scope required")
        }
    }

    /**
     * Admin scope PLUS an interactive session, for the operations that mint or rotate a
     * usable credential: `createLocalUser`, `reset-password`, `disable-local`, `unlock`.
     *
     * [requireAdmin] alone cannot gate these. `AuthenticatedPrincipal.isAdmin` is *defined
     * as* holding [Scope.ADMIN], so a scope test sees a `dpk_` key and a browser session as
     * the same principal — and `ApiKeyFilter` has no path test while `ApiKeyCredentialMatcher`
     * makes key requests CSRF-exempt, so an admin-scoped key reaches these partials with one
     * header. It could then create a local admin, read the one-time password out of the
     * response body ([oneTimeNotice]), sign in, and hold a `dp_session` that is not workspace-
     * pinned and survives revocation of the key that made it.
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
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 100
        const val USER_ID_PREFIX_LEN = 8
        const val COLOR_SUCCESS = "var(--accent-success)"
        const val COLOR_DANGER = "var(--accent-danger)"
        const val COLOR_WARNING = "var(--accent-warning)"
        const val EMPTY_ROW_HTML =
            """<tr><td colspan="7" style="padding:var(--gap-md);""" +
                """text-align:center;color:var(--text-secondary);font-size:var(--text-sm)">""" +
                "No users found</td></tr>"
    }
}
