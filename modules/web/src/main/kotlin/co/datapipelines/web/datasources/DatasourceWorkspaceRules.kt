package co.datapipelines.web.datasources

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceService
import co.datapipelines.auth.WorkspacesProperties
import co.datapipelines.datasources.Datasource
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.web.api.ApiException
import java.util.UUID

/**
 * The D8 rules every datasource WRITE crosses (workspaces design §8/§6 last paragraph) —
 * extracted so the REST surface and the UI's form partial enforce IDENTICAL gates; two
 * hand-copies of a permission matrix is the classic drift defect.
 *
 * The rules, in one place:
 * - `member-datasources-enabled` off → every non-admin write is the 400.
 * - `global` (create, flip, or mutating a global datasource) → admin only.
 * - `readonly` on a GLOBAL datasource → admin only (editable by whoever may edit otherwise).
 * - an explicit `workspace` binding must name a workspace the caller can access (member or
 *   admin); a binding to an unreachable workspace is the 400, not the switch path's 403 —
 *   §8 catalogues it as `datasource.validation.workspace_forbidden`.
 *
 * The caller still owns VISIBILITY (read the target through `getVisible` first) and the
 * registry save (pool invalidation); these rules answer "who may write what".
 */
class DatasourceWorkspaceRules(
    private val workspaceService: WorkspaceService,
    private val workspacesProperties: WorkspacesProperties,
) {
    /** The D8 member gate: when off, every non-admin write on this surface is refused. */
    fun requireMemberDatasourcesGate(principal: AuthenticatedPrincipal) {
        if (!principal.isAdmin && !workspacesProperties.memberDatasourcesEnabled) {
            throw workspaceForbidden(
                "member datasource management is disabled on this server (member-datasources-enabled=false)",
                extraDetails = mapOf("member_datasources_enabled" to false),
            )
        }
    }

    /** A member mutating a GLOBAL datasource is the 400 — global CUD is admin-only (D8). */
    fun requireGlobalMutationAllowed(
        principal: AuthenticatedPrincipal,
        existing: Datasource,
        name: String,
    ) {
        if (existing.workspaceId == null && !principal.isAdmin) {
            throw workspaceForbidden("mutating the global datasource '$name' requires admin")
        }
    }

    /** The `global` flag write (either direction) is admin-only (D8) — a member must not send it at all. */
    fun requireGlobalFlagWriteAllowed(
        principal: AuthenticatedPrincipal,
        globalRequested: Boolean?,
    ) {
        if (globalRequested != null && !principal.isAdmin) {
            throw workspaceForbidden("the global flag requires admin")
        }
    }

    /** `readonly` on a GLOBAL datasource is admin-only (design §6 last paragraph). */
    fun requireReadonlyWriteAllowed(
        principal: AuthenticatedPrincipal,
        existing: Datasource,
        readonlyRequested: Boolean?,
    ) {
        if (existing.workspaceId == null && readonlyRequested != null && !principal.isAdmin) {
            throw workspaceForbidden("flipping readonly on a global datasource requires admin")
        }
    }

    /**
     * The create binding (D8): `global` → null workspace (admin-only); `workspace` name
     * → accessible workspace; else the ACTIVE workspace.
     */
    fun resolveCreateBinding(
        principal: AuthenticatedPrincipal,
        global: Boolean?,
        workspaceName: String?,
    ): UUID? {
        requireMemberDatasourcesGate(principal)
        val isGlobal = global ?: false
        if (isGlobal && workspaceName != null) {
            throw workspaceForbidden("a datasource is either global or bound to one workspace, not both")
        }
        if (isGlobal) {
            if (!principal.isAdmin) throw workspaceForbidden("creating a global datasource requires admin")
            return null
        }
        if (workspaceName != null) return resolveAccessibleWorkspace(principal, workspaceName).id
        return principal.requireWorkspace().id
    }

    /**
     * The update binding: absent flags keep the stored binding; `global:false` re-binds
     * (named workspace, else ACTIVE); `workspace` re-binds to an accessible one.
     */
    fun resolveUpdateBinding(
        principal: AuthenticatedPrincipal,
        existing: Datasource,
        global: Boolean?,
        workspaceName: String?,
    ): UUID? {
        if (workspaceName != null && global != null) {
            throw workspaceForbidden("a datasource is either global or bound to one workspace, not both")
        }
        if (global != null) {
            if (global) return null
            return workspaceName?.let { resolveAccessibleWorkspace(principal, it).id } ?: principal.requireWorkspace().id
        }
        if (workspaceName != null) return resolveAccessibleWorkspace(principal, workspaceName).id
        return existing.workspaceId
    }

    /**
     * The workspace named [name], when [principal] may operate in it (member or global
     * admin, D4) — anything else is the D8 400, NOT the switch path's 403: §8 catalogues
     * "workspace binding to a workspace they're not in" as
     * `datasource.validation.workspace_forbidden`.
     */
    private fun resolveAccessibleWorkspace(
        principal: AuthenticatedPrincipal,
        name: String,
    ): WorkspaceContext =
        try {
            workspaceService.resolveSwitch(principal, name)
        } catch (_: co.datapipelines.auth.WorkspaceMembershipRequiredException) {
            // The 403 carries identity; the D8 400 must not — the no-oracle rule.
            throw workspaceForbidden("workspace '$name' is not available to this caller")
        }

    private fun workspaceForbidden(
        why: String,
        extraDetails: Map<String, Any?> = emptyMap(),
    ): ApiException =
        ApiException(
            PipelineErrorCodes.Datasource.WORKSPACE_FORBIDDEN,
            "Datasource workspace binding refused: $why.",
            extraDetails,
        )
}
