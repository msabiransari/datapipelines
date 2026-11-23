package co.datapipelines.application.endpoints

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.pipeline.AuthoringGuard
import java.util.UUID

/**
 * The ONE effective grant decision of a published route (#1111; auth.md §7.7, published-mutation-apis §2/§6) —
 * shared by the serve path's method visibility (which every fresh call AND every idempotent retry passes before
 * admission or replay) and by mutation-origin polling, so a revoked, downgraded or suspended grant stops all three on
 * the next call.
 *
 * A mutation is admitted by the union of:
 * - **(a) an exact route grant** — a mutation row naming this route ENTITY and method (#1037). Independent of the
 *   deployment's posture: exact grants work on a promotion receiver too.
 * - **(b) a namespace grant** — on a deployment whose authoring capability is on ([AuthoringGuard.developmentPosture],
 *   read at request time), a key of the route's OWN workspace whose GET row at the nearest node carrying any GET
 *   binding is marked ([EndpointAuthorizer.allowsNamespaceWrites]).
 *
 * [at] answers a per-request [Snapshot] that reads at most two queries however many methods the path serves: the
 * ancestor chain's GET rows (all keys, workspace-scoped in SQL) and the key's exact grants, each loaded once and only
 * when asked.
 */
class EndpointGrants(
    private val bindings: EndpointKeyBindingRepository,
    private val authorizer: EndpointAuthorizer,
    private val authoring: AuthoringGuard,
) {
    /** The grants [principal] holds on the concrete [path] of a route family in [workspaceId]. */
    fun at(
        path: String,
        principal: AuthenticatedPrincipal,
        workspaceId: UUID,
    ): Snapshot = Snapshot(path, principal, workspaceId)

    /** One request's view; not shared across requests (a grant change must reach the NEXT call). */
    inner class Snapshot internal constructor(
        private val path: String,
        private val principal: AuthenticatedPrincipal,
        private val workspaceId: UUID,
    ) {
        private val nodeBindings by lazy(LazyThreadSafetyMode.NONE) {
            bindings.findByPrefixes(EndpointPath.ancestors(path), workspaceId)
        }

        private val exactGrants by lazy(LazyThreadSafetyMode.NONE) {
            principal.keyId?.let { bindings.findMutationGrantsByKey(it, workspaceId) }.orEmpty()
        }

        /** The GET rule (§5.2): the ancestor walk over every key's GET rows. */
        fun read(): EndpointAuthorizer.Decision = authorizer.authorize(path, principal, workspaceId, nodeBindings)

        /** May the key call [method] on the route ENTITY [endpointId] at this path — (a) or (b) above. */
        fun mayMutate(
            endpointId: UUID,
            method: EndpointMethod,
        ): Boolean = exactGrants.any { it.endpointId == endpointId && it.method == method } || namespaceWrites()

        /** (b): posture at request time, the key's own workspace, and its marked row at the deciding node. */
        private fun namespaceWrites(): Boolean {
            val keyId = principal.keyId ?: return false
            return authoring.developmentPosture &&
                principal.workspace?.id == workspaceId &&
                authorizer.allowsNamespaceWrites(path, keyId, workspaceId, nodeBindings)
        }
    }
}
