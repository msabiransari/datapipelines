package co.datapipelines.web.ui

import co.datapipelines.auth.AuthProperties
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Controller
class AdminUsersController(
    private val themeResolver: ThemeResolver,
    private val authProperties: AuthProperties,
    private val browse: AdminUsersBrowseModel,
) {
    /**
     * 096 §C (review finding F3): the admin gate was a hand-written
     * `if (!Scope.satisfies(principal.scopes, Scope.ADMIN)) throw AccessDeniedException(...)`
     * inside the handler — the one page in the product that authorized itself. Being
     * hand-written it was invisible to every guard that reads the §7.6 matrix, it audited
     * nothing (the `auth.scope.denied` event the interceptor emits was never written for
     * this route), and it answered a different envelope than every other scope refusal.
     * `USER_ADMINISTRATION` is the matrix row this page implements; `ScopeInterceptor`
     * enforces it, audits it, and answers `auth.scope.insufficient` like everywhere else.
     *
     * The session-only checks on the PARTIAL controller's credential-minting actions
     * (`requireSessionAdmin`) stay where they are — those are about the KIND of credential,
     * which scope cannot express (auth.md §5A.7).
     */
    @GetMapping("/admin/users")
    @RequiredScope(ScopeMatrix.RestOperation.USER_ADMINISTRATION)
    fun users(
        model: Model,
        request: HttpServletRequest,
    ): String {
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        // The create-local-user form renders only when the method exists (§5A.1).
        model.addAttribute("localEnabled", authProperties.local.enabled)
        // §5 (097 §B): the page renders the shell AND the initial rows, through the same
        // model the partial renders through. The screen used to paint three skeleton rows and
        // fetch the real ones from an inline <script> — a fourth first-paint idiom, and the
        // second inline script in the tree.
        browse.fillList(model, q = null, offset = 0, limit = AdminUsersBrowseModel.DEFAULT_LIMIT)
        return "admin/users"
    }
}
