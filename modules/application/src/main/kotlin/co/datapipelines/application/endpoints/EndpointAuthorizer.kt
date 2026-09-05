package co.datapipelines.application.endpoints

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.pipeline.PipelineErrorCodes
import java.util.UUID

/**
 * "May this credential call this endpoint?" (published-endpoints design §5.2, ruling R-EP2).
 *
 * ## The rule, and why it REPLACES rather than adds
 *
 * Walk the request path's ancestors from the most specific to the root. The **first** node
 * carrying any binding at all decides: the presenting key must be among that node's bound keys,
 * or the request is refused. Nodes above it are not consulted.
 *
 * So a binding at `/lending/private` **hides** the one at `/lending` for that subtree — the key
 * bound at `/lending` stops working there. That is the ruling ("replace", not "add") and it is
 * the more conservative of the two readings: an operator who binds a narrow key deep in the tree
 * is drawing a boundary, and an additive model would silently keep the broad key working across
 * it. The cost is that binding both keys at the deeper node is required when both should work,
 * which is a thing an operator can see and fix; the additive model's failure is invisible.
 *
 * ## The unbound case is where the security lives
 *
 * An endpoint with no binding on any ancestor is NOT public and NOT open to any key. It accepts
 * `user` keys of the endpoint's own workspace holding `execute` — so operators and agents keep
 * working on endpoints nobody has bound yet — and refuses `endpoint` keys outright.
 *
 * That asymmetry is deliberate. An endpoint key's entire authority is its bindings; if an unbound
 * path fell through to "any endpoint key may call it", then publishing a new endpoint would
 * silently widen every existing endpoint key's reach at the moment of publication. **An unbound
 * endpoint key authorises nothing.**
 *
 * ## Pure
 *
 * Bindings and the endpoint row are passed in, so every case in §5.2 is a table test with no
 * database. The repository that fetches the ancestor chain is one query away, in the caller.
 */
class EndpointAuthorizer {
    /** What the authorizer decided, and — when it refused — the catalogued reason. */
    sealed interface Decision {
        data object Allowed : Decision

        data class Refused(
            val code: String,
            val message: String,
        ) : Decision
    }

    /**
     * Applies §5.2 to one request.
     *
     * @param requestPath the path under `/api/x`, with its leading `/`.
     * @param endpointWorkspaceId the workspace of the endpoint being called — the one a `user`
     *   key must be pinned to on an unbound path.
     * @param bindings every binding on any ancestor of [requestPath]; extra rows are harmless,
     *   because the walk selects by prefix rather than trusting the query.
     */
    fun authorize(
        requestPath: String,
        principal: AuthenticatedPrincipal,
        endpointWorkspaceId: UUID,
        bindings: Collection<EndpointKeyBinding>,
    ): Decision {
        val byPrefix = bindings.groupBy { it.pathPrefix }
        val deciding = EndpointPath.ancestors(requestPath).firstOrNull { byPrefix[it]?.isNotEmpty() == true }

        return if (deciding == null) {
            unbound(principal, endpointWorkspaceId)
        } else {
            bound(principal, deciding, byPrefix.getValue(deciding))
        }
    }

    /** The bound case: the deciding node's key set is the whole answer. */
    private fun bound(
        principal: AuthenticatedPrincipal,
        decidingNode: String,
        atNode: List<EndpointKeyBinding>,
    ): Decision {
        val keyId = principal.keyId
        return if (keyId != null && atNode.any { it.apiKeyId == keyId }) {
            Decision.Allowed
        } else {
            Decision.Refused(
                PipelineErrorCodes.Endpoint.KEY_NOT_BOUND,
                "The presented credential is not bound at '$decidingNode', the nearest node of the endpoint tree " +
                    "that carries any binding. A binding at a deeper node replaces the ones above it.",
            )
        }
    }

    /** The unbound case: `user` keys of this workspace with `execute`, and nothing else. */
    private fun unbound(
        principal: AuthenticatedPrincipal,
        endpointWorkspaceId: UUID,
    ): Decision =
        when {
            principal.isEndpointKey -> {
                Decision.Refused(
                    PipelineErrorCodes.Endpoint.KEY_KIND_REFUSED,
                    "No key is bound to any ancestor of this path, and an endpoint key authorises only the endpoints " +
                        "it is bound to. Bind this key to the path, or call it with a user key.",
                )
            }

            principal.workspace?.id != endpointWorkspaceId -> {
                Decision.Refused(
                    PipelineErrorCodes.Endpoint.KEY_NOT_BOUND,
                    "This credential is pinned to a different workspace than the endpoint's.",
                )
            }

            !Scope.satisfies(principal.scopes, Scope.EXECUTE) -> {
                Decision.Refused(
                    PipelineErrorCodes.Endpoint.KEY_NOT_BOUND,
                    "Calling an unbound endpoint takes a user key with the 'execute' scope.",
                )
            }

            else -> {
                Decision.Allowed
            }
        }
}
