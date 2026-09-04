package co.datapipelines.application.datasources

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import com.fasterxml.jackson.databind.JsonNode
import java.util.UUID

/**
 * The D8 create binding, as a port.
 *
 * `DatasourceWorkspaceRules` — the one component that answers "who may write what" for the REST
 * surface AND the UI form partial — lives in `web`, which sits ABOVE this module and can never
 * be depended on from here (module-structure §5.10). So the rule is INJECTED rather than
 * imported, exactly as `web` supplies `DatasourceReferences` to `datasources`. One rules
 * instance, one Spring bean, both surfaces: there is no second copy of the permission matrix,
 * which is the whole failure this seam avoids.
 */
fun interface DatasourceCreateBinding {
    /**
     * Resolves the workspace a new datasource binds to, or `null` for a global one, applying
     * the D8 gates (member gate, admin-only `global`, accessible `workspace`).
     */
    fun resolve(
        principal: AuthenticatedPrincipal,
        global: Boolean?,
        workspaceName: String?,
    ): UUID?
}

/**
 * Registering a datasource — the ONE validated path behind `POST /api/v1/datasources`
 * (rest-api.md §9.1) and the `datasources_create` MCP tool (mcp-server.md §6.2.22).
 *
 * The sequence is exactly what `DatasourcesController.create` used to inline, moved here whole:
 *
 * 1. bind the §3.1 payload ([DatasourcePayloadBinder], `password` required);
 * 2. read `readonly`, and resolve the workspace binding through the D8 rules
 *    ([DatasourceCreateBinding] — `global` is admin-only, an explicit `workspace` must be one
 *    the caller can reach);
 * 3. refuse a name already taken — the datasource name namespace is flat and GLOBAL across
 *    workspaces by design (datasources design §3), so this is `datasource.validation.
 *    duplicate_name` even when the existing row belongs to a workspace the caller cannot see;
 * 4. save through the registry, which owns §9 validation, the save-time test pool build, the
 *    AES-GCM encryption of the password and pool invalidation.
 *
 * ## Where it lives, and why
 *
 * `modules/application` — module-structure §5.10's cross-aggregate use-case layer, below `web`
 * and `mcp-server` and above the domain modules. The single-aggregate twin of `PipelineService`
 * would be `modules/datasources`, but this use case needs the `auth` principal and the D8 rules
 * as well as the registry, and `datasources` may depend on `typesystem` alone (§5.4). A use case
 * that needs more than one aggregate lives here — which is exactly what §5.10 says.
 *
 * ## What it deliberately does NOT do
 *
 * No `HttpStatus`, no `ApiResponse`, no MCP wire type; failures are [DatapipelinesException]s
 * carrying a §13 code, and shaping a response is each surface's job. It also does not emit an
 * audit event of its own: the REST create never did (the registry's audit sink is
 * `DatasourceAuditSink.NONE` in the assembled application, and datasources.md §7.4 registers
 * DECRYPTION points, not registrations), and every MCP call — this one declared `mutating` —
 * is audited at `McpToolDispatcher`'s single choke point as `mcp.tool.called` plus
 * `mcp.tool.write`. Adding a third, service-level event here would have been a new audit
 * surface this round did not rule on, spelled as if it were an extraction.
 */
class DatasourceCreateService(
    private val datasources: DatasourceRegistry,
    private val binding: DatasourceCreateBinding,
) {
    /**
     * Registers the datasource described by [body] on behalf of [principal].
     *
     * @return the saved row, exactly as the registry stored it — the caller renders it
     *   (§3.2's `password_set: true`; the password itself is never returned by any surface).
     * @throws DatapipelinesException with a §13 code: a payload-shape problem, the D8 binding
     *   refusal, `datasource.validation.duplicate_name`, or anything §9 validation raises.
     */
    fun create(
        body: JsonNode,
        principal: AuthenticatedPrincipal,
    ): Datasource {
        val datasource =
            DatasourcePayloadBinder.bind(body, requirePassword = true).copy(
                isReadonly = DatasourcePayloadBinder.booleanFlag(body, "readonly") ?: false,
                workspaceId =
                    binding.resolve(
                        principal,
                        DatasourcePayloadBinder.booleanFlag(body, "global"),
                        DatasourcePayloadBinder.workspaceNameOf(body),
                    ),
            )
        if (datasources.exists(datasource.name)) {
            throw DatapipelinesException(
                PipelineErrorCodes.Datasource.DUPLICATE_NAME,
                "A datasource named '${datasource.name}' already exists.",
                mapOf("datasource_name" to datasource.name),
            )
        }
        return datasources.save(datasource, principal.userId)
    }
}
