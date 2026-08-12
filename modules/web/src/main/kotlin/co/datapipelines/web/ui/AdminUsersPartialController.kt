package co.datapipelines.web.ui

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.UserService
import co.datapipelines.web.api.currentPrincipal
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

@Controller
class AdminUsersPartialController(
    private val userService: UserService,
) {
    @GetMapping("/partials/admin/users")
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

    @PatchMapping("/partials/admin/users/{userId}/{action}")
    fun toggle(
        @PathVariable userId: UUID,
        @PathVariable action: String,
    ): ResponseEntity<String> {
        requireAdmin()
        val actor = currentPrincipal().userId
        val updated =
            when (action) {
                "activate" -> {
                    userService.activate(userId, actor)
                    userService.snapshot(userId)
                }

                "deactivate" -> {
                    userService.deactivate(userId, actor)
                    userService.snapshot(userId)
                }

                "promote" -> {
                    userService.grantAdmin(userId, actor)
                    userService.snapshot(userId)
                }

                "demote" -> {
                    userService.revokeAdmin(userId, actor)
                    userService.snapshot(userId)
                }

                else -> {
                    return ResponseEntity.badRequest().body(
                        """<span style="color:var(--accent-danger)">Unknown action: $action</span>""",
                    )
                }
            }
        if (updated == null) {
            return ResponseEntity.notFound().build()
        }
        return ResponseEntity.ok(buildUserRow(updated))
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
        val activeColor = if (user.isActive) COLOR_SUCCESS else COLOR_DANGER
        val roleBg = if (user.isAdmin) COLOR_PRIMARY_BG else COLOR_TERTIARY
        val userIdShort = user.id.toString().take(USER_ID_PREFIX_LEN)

        val toggleBtn =
            if (user.isActive) {
                """<button class="ds-button ds-button-ghost ds-button-sm" """ +
                    """hx-patch="/partials/admin/users/${user.id}/deactivate" """ +
                    """hx-target="#user-row-${user.id}" hx-swap="outerHTML" """ +
                    """style="color:var(--accent-danger)">Deactivate</button>"""
            } else {
                """<button class="ds-button ds-button-ghost ds-button-sm" """ +
                    """hx-patch="/partials/admin/users/${user.id}/activate" """ +
                    """hx-target="#user-row-${user.id}" hx-swap="outerHTML" """ +
                    """style="color:var(--accent-success)">Activate</button>"""
            }

        val roleBtn =
            if (user.isAdmin) {
                """<button class="ds-button ds-button-ghost ds-button-sm" """ +
                    """hx-patch="/partials/admin/users/${user.id}/demote" """ +
                    """hx-target="#user-row-${user.id}" hx-swap="outerHTML" """ +
                    """style="color:var(--accent-warning)">Demote</button>"""
            } else {
                """<button class="ds-button ds-button-ghost ds-button-sm" """ +
                    """hx-patch="/partials/admin/users/${user.id}/promote" """ +
                    """hx-target="#user-row-${user.id}" hx-swap="outerHTML" """ +
                    """style="color:var(--accent-warning)">Promote</button>"""
            }

        return """<tr id="user-row-${user.id}">
          <td style="padding:var(--gap-xs);font-family:var(--font-mono);font-size:var(--text-xs)"
              title="${user.id}">$userIdShort...</td>
          <td style="padding:var(--gap-xs)">${esc(user.displayName)}</td>
          <td style="padding:var(--gap-xs);font-family:var(--font-mono);font-size:var(--text-sm)">${esc(user.email)}</td>
          <td style="padding:var(--gap-xs)">
            <span style="color:$activeColor;font-size:var(--text-xs);background:var(--surface-tertiary);
              padding:2px var(--gap-xs);border-radius:var(--radius-base)">$activeStatus</span>
          </td>
          <td style="padding:var(--gap-xs)">
            <span style="font-size:var(--text-xs);background:$roleBg;
              padding:2px var(--gap-xs);border-radius:var(--radius-base);color:var(--text-secondary)">$role</span>
          </td>
          <td style="padding:var(--gap-xs);white-space:nowrap">$toggleBtn $roleBtn</td>
        </tr>"""
    }

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

    private companion object {
        private val log = LoggerFactory.getLogger(AdminUsersPartialController::class.java)
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 100
        const val USER_ID_PREFIX_LEN = 8
        const val COLOR_SUCCESS = "var(--accent-success)"
        const val COLOR_DANGER = "var(--accent-danger)"
        const val COLOR_PRIMARY_BG = "var(--accent-primary-bg)"
        const val COLOR_TERTIARY = "var(--surface-tertiary)"
        const val EMPTY_ROW_HTML =
            """<tr><td colspan="6" style="padding:var(--gap-md);""" +
                """text-align:center;color:var(--text-secondary);font-size:var(--text-sm)">""" +
                "No users found</td></tr>"
    }
}
