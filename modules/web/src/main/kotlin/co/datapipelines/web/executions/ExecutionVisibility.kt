package co.datapipelines.web.executions

import co.datapipelines.application.endpoints.EndpointServeAudit
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.web.api.visibleTo
import java.util.UUID

/**
 * "May this principal see this execution?" — the one answer every execution read shares
 * (rest-api §7.2, §10.2; auth.md §7.7).
 *
 * ## Why this is a collaborator and not an extension function
 *
 * `ExecutionRecord.visibleTo` is pure and stays pure: owner-or-admin, decidable from the record
 * and the principal alone. The endpoint-key arm is not decidable that way — it needs the serve
 * audit row that pairs a KEY id with an EXECUTION id — so it needs a repository, and a repository
 * cannot live on an extension function. Putting it here rather than inside `ResultCursor` keeps
 * the metadata read (`GET /executions/{id}`) from having to depend on the RESULT cursor to answer
 * a question about visibility.
 *
 * Both reads must answer identically: an endpoint key that could fetch a result but not see that
 * its execution exists is a contradiction a client trips over immediately.
 */
class ExecutionVisibility(
    /**
     * Nullable so a deployment (or a unit test) without the published-endpoint feature keeps
     * today's behaviour exactly: no serve audit, no endpoint keys, nothing to consult.
     */
    private val serveAudit: EndpointServeAudit? = null,
) {
    /**
     * True when [principal] may see [record].
     *
     * A false here is reported by callers as **not found**, never `403` — §7.2's "the URL is not
     * a capability": a caller must not be able to discover that someone else's execution exists.
     */
    fun visible(
        record: ExecutionRecord,
        principal: AuthenticatedPrincipal,
        executionId: UUID,
    ): Boolean =
        when {
            record.visibleTo(principal) -> true

            // §7.7 — an endpoint key sees exactly what its OWN serve started. `triggered_by` is
            // the key's owner, so it would make two endpoint keys of one person interchangeable;
            // the serve audit row is the only place the key id and the execution id meet.
            principal.isEndpointKey -> principal.keyId?.let { serveAudit?.servedByKey(executionId, it) } ?: false

            else -> false
        }
}
