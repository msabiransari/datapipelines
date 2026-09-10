package co.datapipelines.application.datasources

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import java.util.UUID

/**
 * The D8 update gates, as a port.
 *
 * `DatasourceWorkspaceRules` — the one component that answers "who may write what" for the
 * REST surface AND the UI's form partial — lives in `web`, which sits ABOVE this module and
 * can never be depended on from here (module-structure §5.10). So the rules are INJECTED,
 * exactly as [DatasourceCreateBinding] injects the create binding. One rules instance, one
 * Spring bean, both surfaces.
 *
 * Four methods rather than one "do the gates" call on purpose: the ORDER they run in is the
 * contract (`DatasourceD8MatrixTest` is that order's proof), and the order belongs to
 * [DatasourceUpdateService] — the thing both surfaces share — not to whichever adapter
 * happens to implement the port.
 */
interface DatasourceUpdateRules {
    /** A member mutating a GLOBAL datasource is refused — global CUD is admin-only (D8). */
    fun requireGlobalMutationAllowed(
        principal: AuthenticatedPrincipal,
        existing: Datasource,
        name: String,
    )

    /** When `member-datasources-enabled` is off, every non-admin write on this surface is refused. */
    fun requireMemberDatasourcesGate(principal: AuthenticatedPrincipal)

    /** The `global` flag write (either direction) is admin-only; a member must not send it at all. */
    fun requireGlobalFlagWriteAllowed(
        principal: AuthenticatedPrincipal,
        globalRequested: Boolean?,
    )

    /** Absent flags keep the stored binding; `global:false` re-binds; `workspace` re-binds to an accessible one. */
    fun resolveUpdateBinding(
        principal: AuthenticatedPrincipal,
        existing: Datasource,
        global: Boolean?,
        workspaceName: String?,
    ): UUID?
}

/**
 * Updating a datasource — the ONE gated path behind `PUT /api/v1/datasources/{name}`
 * (rest-api.md §9.4) and the §4.5 edit dialog's `POST /partials/datasources/{name}`.
 *
 * The sequence had been written longhand on both surfaces — `requireGlobalMutationAllowed` →
 * `requireMemberDatasourcesGate` → `requireGlobalFlagWriteAllowed` → `resolveUpdateBinding` →
 * `registry.save` — which is a permission matrix maintained in two places, the exact defect
 * the rules component itself was extracted to prevent one level down.
 * 097 §A moved it here, beside [DatasourceCreateService], for the same reason 068 moved
 * create: two entry points, one validated path (049).
 *
 * ## What each surface keeps
 *
 * The INPUT SHAPE, and only that. REST binds a §3.1 JSON payload through
 * [DatasourcePayloadBinder]; the UI binds form fields through `DatasourcePoolForm`. Both then
 * hand the same [Datasource] to the same method — [bind] is called AFTER the three gates have
 * run, so a caller who may not perform this write is refused before their payload is
 * interpreted at all, which is the order the REST controller already had.
 *
 * ## What it deliberately does NOT do
 *
 * No `HttpStatus`, no `ApiResponse`, no HTML: failures are the exceptions the rules and the
 * registry raise, and shaping a response is each surface's job. Reading the target through
 * `getVisible` stays with the caller too — visibility (§5.3) decides 404 vs refusal, and that
 * is an HTTP decision.
 */
class DatasourceUpdateService(
    private val datasources: DatasourceRegistry,
    private val rules: DatasourceUpdateRules,
) {
    /**
     * Applies the D8 gates to [principal]'s update of [existing], resolves the new workspace
     * binding, and saves.
     *
     * @param name the path name, for the refusal messages.
     * @param globalRequested the `global` flag when the surface saw one written, else null.
     * @param workspaceName an explicit re-bind target, else null.
     * @param bind the surface's bound row, built once the gates have passed; its `workspaceId`
     *   is replaced by the resolved binding, so a surface cannot bind around the rules.
     * @return the saved row, exactly as the registry stored it (pool eviction included).
     */
    fun update(
        name: String,
        existing: Datasource,
        principal: AuthenticatedPrincipal,
        globalRequested: Boolean?,
        workspaceName: String?,
        bind: () -> Datasource,
    ): Datasource {
        rules.requireGlobalMutationAllowed(principal, existing, name)
        rules.requireMemberDatasourcesGate(principal)
        rules.requireGlobalFlagWriteAllowed(principal, globalRequested)
        val bound = bind().copy(ownerWorkspaceId = rules.resolveUpdateBinding(principal, existing, globalRequested, workspaceName))
        return datasources.save(bound, principal.userId)
    }
}
