package co.datapipelines.application.endpoints

import co.datapipelines.auth.AuthenticatedPrincipal
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
 * An endpoint with no binding on any ancestor is NOT public and NOT open to any key: it is
 * unservable until it is bound (#215 B3). Serving takes a key (sessions are refused before this
 * runs), and since B2 the MCP key never reaches a published path, so the only credential that
 * can arrive here is an `api_caller` key — whose entire authority is its bindings. If an unbound
 * path fell through to "any endpoint key may call it", publishing a new endpoint would silently
 * widen every existing key's reach at the moment of publication. **An unbound path authorises
 * nothing.** (Until #215 a `user` key holding `execute` could call an unbound path of its own
 * workspace; that branch went with the scopes.)
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
     * @param requestPath the published path — the part after `/api`, with its leading `/`.
     * @param endpointWorkspaceId the workspace of the endpoint being called — (#191) the ONLY
     *   workspace whose bindings are visible here.
     * @param bindings every binding on any ancestor of [requestPath]; extra rows are harmless,
     *   because the walk selects by prefix rather than trusting the query. Rows of ANOTHER
     *   workspace are invisible (#191): a foreign binding at a nearer node neither decides nor
     *   shadows — the walk continues past it as if the node carried nothing, which is why this
     *   in-memory filter stays even though the repository query already applies the same
     *   predicate. Fail closed: if either layer were dropped, the other still refuses.
     */
    fun authorize(
        requestPath: String,
        principal: AuthenticatedPrincipal,
        endpointWorkspaceId: UUID,
        bindings: Collection<EndpointKeyBinding>,
    ): Decision {
        val byPrefix =
            bindings
                .asSequence()
                .filter { it.workspaceId == endpointWorkspaceId }
                .groupBy { it.pathPrefix }
        val deciding = EndpointPath.ancestors(requestPath).firstOrNull { byPrefix[it]?.isNotEmpty() == true }

        return if (deciding == null) {
            unbound(principal)
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

    /** The unbound case (#215 B3): nothing is admitted — the refusal only says what to do instead. */
    private fun unbound(principal: AuthenticatedPrincipal): Decision =
        if (principal.isEndpointKey) {
            Decision.Refused(
                PipelineErrorCodes.Endpoint.KEY_KIND_REFUSED,
                "No key is bound to any ancestor of this path, and an endpoint key authorises only the endpoints " +
                    "it is bound to. Bind this key to the path.",
            )
        } else {
            Decision.Refused(
                PipelineErrorCodes.Endpoint.KEY_NOT_BOUND,
                "No key is bound to any ancestor of this path; an unbound published path is served to no one " +
                    "until a key is bound to it.",
            )
        }
}
