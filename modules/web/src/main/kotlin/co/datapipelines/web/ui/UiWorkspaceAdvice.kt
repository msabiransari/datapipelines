package co.datapipelines.web.ui

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceMembership
import co.datapipelines.auth.WorkspaceService
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute

/**
 * Fills the layout's workspace switcher for every [Controller] screen (ui-screens.md §3):
 * the caller's memberships and the ACTIVE workspace's name.
 *
 * Reads go through [WorkspaceService]'s 60s liveness cache, so one membership read is
 * shared by the advice and any controller that asks again in the same request.
 *
 * Screens without an authenticated principal (the login page) get nothing: the advice
 * silently omits the attributes rather than erroring, and `layouts/default` hides the
 * switcher when `workspaceOptions` is absent.
 */
@ControllerAdvice(annotations = [Controller::class])
class UiWorkspaceAdvice(
    private val workspaceService: WorkspaceService,
) {
    @ModelAttribute("workspaceOptions")
    fun workspaceOptions(): List<WorkspaceMembership>? = principal()?.let { workspaceService.memberships(it.userId) }

    @ModelAttribute("activeWorkspace")
    fun activeWorkspace(): String? = principal()?.workspace?.name

    /**
     * The `DP-Workspace` entry spliced into the layout's `hx-headers` JSON (ui-screens.md
     * §3): present exactly when an active workspace resolved, so every htmx partial call
     * rides the switched workspace even before the re-stamped session claim takes over on
     * the next full-page load.
     */
    @ModelAttribute("workspaceHeaderFragment")
    fun workspaceHeaderFragment(): String =
        principal()
            ?.workspace
            ?.name
            ?.let { name -> ",&quot;DP-Workspace&quot;:&quot;${name.replace("\"", "")}&quot;" }
            ?: ""

    private fun principal(): AuthenticatedPrincipal? =
        SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal
}
