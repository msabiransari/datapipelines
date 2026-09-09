package co.datapipelines.web.ui

import co.datapipelines.auth.User
import co.datapipelines.auth.UserService
import org.springframework.ui.Model
import java.time.Instant
import java.util.UUID

/**
 * The admin user-management screen's model, in one place for the page controller and the
 * partial controller (097 §B/§C; ui-screens.md §5's BrowseModel rule).
 *
 * The screen had no projection layer at all: [AdminUsersPartialController] built the rows,
 * the `ds-badge` classes, the `hx-patch` attributes and an inline colour style attribute by
 * string concatenation in Kotlin — the pattern 091 deleted for the API-key table
 * (`ApiKeysPartialController` records the deletion). Markup built in Kotlin is invisible to
 * every template audit (`InlineWidthAuditTest` named this hole in its own KDoc), cannot be
 * reviewed beside the screen it renders, and escapes by hand.
 *
 * What crosses into the template is [AdminUserRow] — a plain row model whose every decision
 * (which badge, which buttons, what the local-account column says) is taken HERE, where it
 * can be unit-tested, so `partials/admin-users-rows.html` only iterates.
 *
 * Declared as an explicit `@Bean` in [UiConfig], not by a stereotype (015 / module-structure
 * §8.4; `ArchitectureGuardTest` enforces it).
 */
class AdminUsersBrowseModel(
    private val users: UserService,
) {
    /** Fills [model] with one page of users and returns the rows fragment's view name. */
    fun fillList(
        model: Model,
        q: String?,
        offset: Int,
        limit: Int,
    ): String {
        val page = offset.coerceAtLeast(0)
        val size = limit.coerceIn(MIN_LIMIT, MAX_LIMIT)
        model.addAttribute("userRows", users.search(q.orEmpty(), page, size).map(::row))
        return ROWS_VIEW
    }

    /** Fills [model] with ONE row — the swap every row action answers with. */
    fun fillRow(
        model: Model,
        user: User,
    ): String {
        model.addAttribute("userRow", row(user))
        return ROW_VIEW
    }

    /** The row as the fragment needs it, with every rendering decision already taken. */
    fun row(
        user: User,
        now: Instant = Instant.now(),
    ): AdminUserRow {
        val locked = user.lockedUntil?.isAfter(now) == true
        return AdminUserRow(
            id = user.id,
            idShort = user.id.toString().take(USER_ID_PREFIX_LEN),
            displayName = user.displayName,
            email = user.email,
            isActive = user.isActive,
            isAdmin = user.isAdmin,
            // The local-account column: whether a password exists, and whether the lockout is
            // still running (auth.md §5A.4). An em dash is "this account is OIDC-only".
            localStatus =
                when {
                    user.hasLocalPassword && locked -> "local · locked"
                    user.hasLocalPassword -> "local"
                    else -> "—"
                },
            actions = actions(user, locked),
        )
    }

    /**
     * The row's actions, in the order the screen has always shown them: the active toggle,
     * the role toggle, then the local-account operations (auth.md §5A.1 — reset is also the
     * unlock path, disable makes the account OIDC-only again, unlock clears the lockout only).
     */
    private fun actions(
        user: User,
        locked: Boolean,
    ): List<AdminUserAction> =
        buildList {
            if (user.isActive) {
                add(AdminUserAction("deactivate", "Deactivate", DANGER))
            } else {
                add(AdminUserAction("activate", "Activate", SUCCESS))
            }
            if (user.isAdmin) {
                add(AdminUserAction("demote", "Demote", WARNING))
            } else {
                add(AdminUserAction("promote", "Promote", WARNING))
            }
            if (user.hasLocalPassword) {
                add(AdminUserAction("reset-password", "Reset PW", WARNING))
                add(AdminUserAction("disable-local", "Disable local", DANGER))
            }
            if (locked) add(AdminUserAction("unlock", "Unlock", SUCCESS))
        }

    companion object {
        /** The screen's page size floor and ceiling — the clamps the partial has always applied. */
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 100

        /** The page's own first paint asks for the same page the search box's first refresh would. */
        const val DEFAULT_LIMIT = 20

        const val ROWS_VIEW = "partials/admin-users-rows"
        const val ROW_VIEW = "partials/admin-user-row"

        private const val USER_ID_PREFIX_LEN = 8

        /**
         * The semantic colour utilities (app.css) that replaced the inline colour attribute.
         * A class rather than a declaration: an inline style is invisible to the template
         * audits and is the one thing a style CSP cannot allow.
         */
        private const val DANGER = "u-danger"
        private const val SUCCESS = "u-success"
        private const val WARNING = "u-warning"
    }
}

/** One user, as `partials/admin-users-rows.html` needs it — no domain object reaches the template. */
data class AdminUserRow(
    val id: UUID,
    val idShort: String,
    val displayName: String,
    val email: String,
    val isActive: Boolean,
    val isAdmin: Boolean,
    val localStatus: String,
    val actions: List<AdminUserAction>,
) {
    val statusLabel: String get() = if (isActive) "Active" else "Inactive"
    val statusBadge: String get() = if (isActive) "ds-badge-success" else "ds-badge-danger"
    val roleLabel: String get() = if (isAdmin) "Admin" else "User"
    val roleBadge: String get() = if (isAdmin) "ds-badge-primary" else "ds-badge-default"
    val rowId: String get() = "user-row-$id"
}

/** One button in a row's action cell: the `hx-patch` verb, its label and its semantic colour. */
data class AdminUserAction(
    val action: String,
    val label: String,
    val colourClass: String,
)
