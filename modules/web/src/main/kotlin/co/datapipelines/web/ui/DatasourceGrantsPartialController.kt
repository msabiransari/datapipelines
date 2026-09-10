package co.datapipelines.web.ui

import co.datapipelines.auth.AuditLogger
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.auth.WorkspaceService
import co.datapipelines.datasources.DatasourceAuditEvents
import co.datapipelines.datasources.DatasourceGrantRepository
import co.datapipelines.datasources.DatasourceRegistry
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.ModelAndView

/**
 * The datasource GRANTS screen (114 §C.2, RBAC design §4 / D-R7): which workspaces can SEE a
 * datasource.
 *
 * 112 shipped the REST surface (`/api/v1/datasources/{name}/grants/{workspace}`) and said out
 * loud what that left behind: *"an instance datasource registered through the UI is visible to
 * nobody until a super admin grants it through the API"*. This is that screen.
 *
 * ## Super admin, not workspace admin
 * The prompt for this round put the grants section on `canAdminWorkspace` and declared
 * `MUTATE_WORKSPACE_DATASOURCES`. Both are contradicted by the authority: auth.md §7.6's
 * "Grant / revoke a datasource to a workspace" row is `admin` scope + `super_admin` role, and
 * `ScopeMatrix.RestOperation.MANAGE_DATASOURCE_GRANTS` carries exactly that. §7.6 wins (the
 * prompt says so itself), and the reasoning is in the REST controller's own KDoc: a grant list
 * names every workspace on the instance holding one, which is the cross-workspace disclosure
 * D-R5 exists to prevent for everyone below super admin. A workspace admin registering a
 * datasource bound to their OWN workspace still gets its grant automatically at registration.
 *
 * ## Where it lives
 * A DIALOG opened from the datasources list row, not a section on `datasources/detail.html`.
 * That page is LAKE-ONLY — `DatasourceDetailUiController` redirects any other dialect straight
 * back to the listing — so a grants section there would be unreachable for every Postgres,
 * MySQL and SQLite datasource on the instance, which is all of the ones an operator needs to
 * grant. The dialog rides the existing `#ds-dialog` container, the same swap contract Edit and
 * Delete use (094 §A/§B).
 */
@Controller
class DatasourceGrantsPartialController(
    private val datasources: DatasourceRegistry,
    private val workspaces: WorkspaceService,
    private val grants: DatasourceGrantRepository,
    private val actorNames: ActorNames,
    private val auditLogger: AuditLogger,
) {
    /** The dialog: the grants this datasource holds, plus the add form. */
    @GetMapping("/partials/datasources/{name}/grants")
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_DATASOURCE_GRANTS)
    fun dialog(
        model: Model,
        @PathVariable name: String,
    ): Any = render(model, name) ?: notFound(name)

    /**
     * Grants [name] to a workspace. Idempotent at the repository, so re-granting is success and
     * keeps the original actor — the first grant is the decision.
     */
    @PostMapping("/partials/datasources/{name}/grants")
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_DATASOURCE_GRANTS)
    fun grant(
        model: Model,
        @PathVariable name: String,
        @RequestParam workspace: String,
    ): Any {
        val principal = requirePrincipal()
        datasources.get(name) ?: return notFound(name)
        val target = workspaces.read(principal, workspace)
        val created = grants.grant(name, target.id, principal.userId)
        audit(principal, DatasourceAuditEvents.GRANTED, name, workspace, mapOf("already_granted" to !created))
        return render(model, name) ?: notFound(name)
    }

    /**
     * Revokes a grant. The datasource is untouched — revoking visibility is not deleting a
     * credential — and the workspace that loses it simply stops being able to see the row.
     */
    @PostMapping("/partials/datasources/{name}/grants/{workspace}/remove")
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_DATASOURCE_GRANTS)
    fun revoke(
        model: Model,
        @PathVariable name: String,
        @PathVariable workspace: String,
    ): Any {
        val principal = requirePrincipal()
        datasources.get(name) ?: return notFound(name)
        val target = workspaces.read(principal, workspace)
        val removed = grants.revoke(name, target.id)
        audit(principal, DatasourceAuditEvents.REVOKED, name, workspace, mapOf("had_grant" to removed))
        return render(model, name) ?: notFound(name)
    }

    /**
     * The dialog's model, or null when the datasource does not exist on the INSTANCE.
     *
     * [DatasourceRegistry.get] is the UNFILTERED read, deliberately: a super admin grants
     * datasources their own active workspace has never been granted, which is the whole point
     * of the verb. `MANAGE_DATASOURCE_GRANTS` on every handler is what makes it safe.
     */
    private fun render(
        model: Model,
        name: String,
    ): String? {
        val datasource = datasources.get(name) ?: return null
        val principal = requirePrincipal()
        val rows = grants.grantsOf(name)
        val actors = actorNames.lookup(rows.map { it.grantedBy }.distinct())
        model.addAttribute("datasource", datasource)
        model.addAttribute(
            "grants",
            rows.map { grant ->
                mapOf(
                    "workspace" to grant.workspaceName,
                    "grantedBy" to (actors[grant.grantedBy] ?: ActorNames.fallback(grant.grantedBy)),
                    "grantedAt" to grant.grantedAt.toString(),
                    // The registering workspace's own grant is MARKED, not protected: the REST
                    // surface lets a super admin revoke it (`DatasourceGrantsController.revoke`
                    // has no owner exemption, and `DatasourceWorkspaceRules` says nothing about
                    // grants at all — it governs the `global` flag and the workspace BINDING).
                    // Hiding the button here would make the screen refuse what the server
                    // allows, which is the mirror of the defect this round removes.
                    "owner" to (datasource.workspaceName == grant.workspaceName),
                )
            },
        )
        // The select's options: every ACTIVE workspace this caller can grant to, minus the ones
        // that already hold a grant. A super admin's `listOwn` IS every workspace (D-R8), which
        // is why the handler is super-admin-only in the first place.
        val granted = rows.map { it.workspaceName }.toSet()
        model.addAttribute(
            "grantableWorkspaces",
            workspaces
                .listOwn(principal)
                .filter { it.workspaceActive && it.workspaceName !in granted }
                .map { it.workspaceName }
                .sorted(),
        )
        RoleModel.stamp(model, principal)
        return "partials/datasource-grants"
    }

    /** Every grant change is audited (D-R7: "every grant is audited") — the same events REST logs. */
    private fun audit(
        principal: AuthenticatedPrincipal,
        event: String,
        datasource: String,
        workspace: String,
        extra: Map<String, Any?>,
    ) = auditLogger.log(
        event = event,
        userId = principal.userId,
        keyId = principal.keyId,
        details = mapOf("datasource" to datasource, "workspace" to workspace) + extra,
    )

    /** The dialog's refusal shape, matching the datasource dialogs beside it (094 §B). */
    private fun notFound(name: String): ModelAndView =
        ModelAndView(
            "partials/inline-refusal",
            mapOf("message" to "Datasource '$name' does not exist on this instance."),
            org.springframework.http.HttpStatus.BAD_REQUEST,
        )

    private fun requirePrincipal(): AuthenticatedPrincipal =
        SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal
            ?: error("No authenticated principal")
}
