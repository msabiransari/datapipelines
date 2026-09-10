package co.datapipelines.web.datasources

import co.datapipelines.auth.AuditLogger
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.auth.WorkspaceService
import co.datapipelines.datasources.DatasourceAuditEvents
import co.datapipelines.datasources.DatasourceErrorCodes
import co.datapipelines.datasources.DatasourceGrant
import co.datapipelines.datasources.DatasourceGrantRepository
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.api.ApiResponse
import co.datapipelines.web.api.currentPrincipal
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Datasource grants (RBAC design §4, D-R7): which workspaces can SEE a datasource.
 *
 * A datasource is registered once — credentials are instance secrets — and granted to N
 * workspaces. "Global" is gone: nothing is visible by default, and the V23 migration turned
 * every former global datasource into one explicit grant row per workspace so nothing that
 * worked yesterday stopped today.
 *
 * Super admin only ([ScopeMatrix.RestOperation.MANAGE_DATASOURCE_GRANTS]). A workspace admin
 * registering a datasource bound to their OWN workspace gets its grant automatically at
 * registration ([DatasourceGrantRepository.grantOnRegistration]); handing a datasource to
 * somebody else's workspace is this surface, and it is the one verb that decides who can reach
 * a live database credential.
 *
 * The read is here too, not on the datasource detail payload, and deliberately: a grant list
 * names every workspace on the instance that holds one, which is exactly the cross-workspace
 * disclosure D-R5 exists to prevent for everyone below super admin.
 */
@RestController
@RequestMapping("/api/v1/datasources/{name}/grants")
class DatasourceGrantsController(
    private val datasources: DatasourceRegistry,
    private val workspaces: WorkspaceService,
    private val grants: DatasourceGrantRepository,
    private val auditLogger: AuditLogger,
) {
    /** Every workspace [name] is granted to. Super admin. */
    @GetMapping
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_DATASOURCE_GRANTS)
    fun list(
        @PathVariable name: String,
    ): ApiResponse<List<Map<String, Any?>>> {
        requireDatasource(name)
        return ApiResponse.of(grants.grantsOf(name).map { it.toResponse() })
    }

    /**
     * Grants [name] to [workspace]. Idempotent — re-granting is success and keeps the original
     * actor, because the first grant is the decision and a no-op is not a new one.
     */
    @PostMapping("/{workspace}")
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_DATASOURCE_GRANTS)
    fun grant(
        @PathVariable name: String,
        @PathVariable workspace: String,
    ): ApiResponse<Map<String, Any?>> {
        requireDatasource(name)
        val principal = currentPrincipal()
        val target = workspaces.read(principal, workspace)
        val created = grants.grant(name, target.id, principal.userId)
        audit(principal, DatasourceAuditEvents.GRANTED, name, workspace, mapOf("already_granted" to !created))
        return ApiResponse.of(mapOf("datasource" to name, "workspace" to workspace, "granted" to true))
    }

    /**
     * Revokes the grant. The datasource itself is untouched — revoking visibility is not
     * deleting a credential, and a workspace that loses a grant loses only its ability to SEE
     * it. Pipelines in that workspace that referenced it will then fail at execution with the
     * ordinary not-found, which is the honest answer: the datasource no longer exists for them.
     */
    @DeleteMapping("/{workspace}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_DATASOURCE_GRANTS)
    fun revoke(
        @PathVariable name: String,
        @PathVariable workspace: String,
    ) {
        requireDatasource(name)
        val principal = currentPrincipal()
        val target = workspaces.read(principal, workspace)
        val removed = grants.revoke(name, target.id)
        audit(principal, DatasourceAuditEvents.REVOKED, name, workspace, mapOf("had_grant" to removed))
    }

    /**
     * The datasource must exist on the INSTANCE, not merely be visible here: a super admin
     * grants datasources their own active workspace has never been granted, which is the whole
     * point of the verb. [DatasourceRegistry.get] is the unfiltered read; the SUPER_ADMIN
     * capability on every handler is what makes using it safe.
     */
    private fun requireDatasource(name: String) {
        datasources.get(name)
            ?: throw DatapipelinesException(
                DatasourceErrorCodes.NOT_FOUND,
                "Datasource '$name' not found.",
                mapOf("datasource_name" to name),
            )
    }

    /** Every grant change is audited (D-R7: "every grant is audited"). */
    private fun audit(
        principal: AuthenticatedPrincipal,
        event: String,
        datasource: String,
        workspace: String,
        extra: Map<String, Any?>,
    ) {
        auditLogger.log(
            event = event,
            userId = principal.userId,
            keyId = principal.keyId,
            details = mapOf("datasource" to datasource, "workspace" to workspace) + extra,
        )
    }

    private fun DatasourceGrant.toResponse(): Map<String, Any?> =
        mapOf(
            "workspace" to workspaceName,
            "granted_by" to grantedBy.toString(),
            "granted_at" to grantedAt.toString(),
        )
}
