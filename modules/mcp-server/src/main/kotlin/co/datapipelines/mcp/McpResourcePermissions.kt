package co.datapipelines.mcp

import co.datapipelines.auth.Permission
import co.datapipelines.pipeline.PipelineErrorCodes

/**
 * What the `resources` methods read under (mcp-server.md §7.3, §13 checklist: "`resources/list`
 * filtered by the caller's scope") — since #215 slice (b), the ROLE's read permission for each
 * resource family, where it used to be the key's `read` scope.
 *
 * The tools get their permission from their catalog entry via the dispatcher; the resource
 * methods have no catalog entry of their own, so each family names the tool-row permission that
 * reads the same data. Every member role holds these but `execution.read` (the promoter reads no
 * executions, D5) — and "every role holds it" is exactly the kind of guarantee that must be
 * asserted rather than assumed, because the day a role stops holding one, the resources must stop
 * serving it on the same day the tools do.
 */
internal object McpResourcePermissions {
    /** The permission each enumerated kind is read under — the listing skips a kind the caller cannot read. */
    private val BY_KIND: Map<String, Permission> =
        mapOf(
            McpResourceUri.DOCS to Permission.DOCS_READ,
            McpResourceUri.PIPELINES to Permission.PIPELINE_READ,
            McpResourceUri.TEMPLATES to Permission.TEMPLATE_READ,
            McpResourceUri.DATASOURCES to Permission.DATASOURCE_READ,
            McpResourceUri.EXECUTIONS to Permission.EXECUTION_READ,
        )

    /** True when [ctx]'s principal may enumerate resources of [kind]; an unknown kind is never listed. */
    fun mayList(
        ctx: McpToolContext,
        kind: String,
    ): Boolean = BY_KIND[kind]?.let(ctx.principal::holds) ?: false

    /** The permission a read of [uri] needs. Exhaustive over [McpResourceUri], so a new family cannot compile without one. */
    fun of(uri: McpResourceUri): Permission =
        when (uri) {
            is McpResourceUri.PipelineLatest, is McpResourceUri.PipelineVersion, is McpResourceUri.PipelineParameters -> {
                Permission.PIPELINE_READ
            }

            is McpResourceUri.TemplateLatest, is McpResourceUri.TemplateVersion -> {
                Permission.TEMPLATE_READ
            }

            is McpResourceUri.DatasourceList, is McpResourceUri.DatasourceByName -> {
                Permission.DATASOURCE_READ
            }

            is McpResourceUri.Execution, is McpResourceUri.ExecutionEvents -> {
                Permission.EXECUTION_READ
            }

            is McpResourceUri.Skill, is McpResourceUri.SkillReference -> {
                Permission.DOCS_READ
            }
        }

    /**
     * Refuses a read of [uri] the caller's role does not hold.
     *
     * @throws io.modelcontextprotocol.spec.McpError `-32003` — the `resources` methods have no
     *   `isError` content channel, so a refusal can only be a JSON-RPC error ([McpArguments.FORBIDDEN]).
     */
    fun requireRead(
        ctx: McpToolContext,
        uri: McpResourceUri,
    ) {
        val permission = of(uri)
        if (!ctx.principal.holds(permission)) {
            throw McpArguments.forbidden(
                "${PipelineErrorCodes.Auth.ROLE_REQUIRED}: this key's role does not hold '${permission.wire}'.",
            )
        }
    }
}
