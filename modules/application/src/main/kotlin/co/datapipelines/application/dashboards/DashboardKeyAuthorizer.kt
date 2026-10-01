package co.datapipelines.application.dashboards

import java.util.UUID

/**
 * "Does this `dashboard` key serve this dashboard?" (auth.md §7.7, ruling R-EP2 verbatim, L5).
 *
 * ## The rule, and why it REPLACES rather than adds
 *
 * Walk the dashboard NAME's ancestor folders from the most specific to the root. The **first**
 * folder carrying any binding at all decides: the presenting key must be among that folder's
 * bound keys, or the dashboard is not served. Folders above it are not consulted.
 *
 * So a binding at `finance/dashboards/private` **hides** the one at `finance/dashboards` for
 * that subtree — the key bound at `finance/dashboards` stops serving there. That is the ruling
 * ("replace", not "add") and it is the more conservative of the two readings: an operator who
 * binds a narrow key deep in the tree is drawing a boundary, and an additive model would
 * silently keep the broad key working across it. The cost is that binding both keys at the
 * deeper node is required when both should work, which is a thing an operator can see and fix;
 * the additive model's failure is invisible.
 *
 * ## The unbound case is where the security lives
 *
 * A dashboard with no binding on any ancestor of its name is served to NO key: **an unbound
 * name authorises nothing.** If an unbound name fell through to "any dashboard key may serve
 * it", binding a NEW key into a workspace would silently widen every existing key's reach the
 * moment the binding landed — the same reasoning that made the endpoint tree's unbound case a
 * refusal (#215 B3), and the reason an unbound `dashboard` key answers the family's ordinary
 * 404 everywhere (a hidden dashboard is indistinguishable from an absent one).
 *
 * ## Pure
 *
 * Bindings are passed in, so every case is a table test with no database
 * ([DashboardKeyAuthorizerTest] — `EndpointAuthorizerTest`'s shape). The repository that
 * fetches the ancestor chain is one query away, in the caller.
 */
class DashboardKeyAuthorizer {
    /** What the authorizer decided, and — when it refused — why, in the runtime's own words. */
    sealed interface Decision {
        data object Allowed : Decision

        data class Refused(
            val reason: Reason,
        ) : Decision
    }

    /** Why a name was not served: never bound anywhere, or bound at a folder that excludes this key. */
    enum class Reason {
        /** No ancestor of the name carries any binding — an unbound name (or an unbound key) serves nothing. */
        UNBOUND,

        /** The nearest bound folder's key set does not include the presenting key (deeper-replace, or someone else's key). */
        NOT_BOUND_AT_DECIDING_FOLDER,
    }

    /**
     * Applies R-EP2 to one dashboard name.
     *
     * @param dashboardName the dashboard's FQN (`finance/dashboards/monthly-revenue`).
     * @param workspaceId the workspace of the dashboard being served — (#191) the ONLY
     *   workspace whose bindings are visible here.
     * @param apiKeyId the presenting key's id (`dpk_…`).
     * @param bindings every binding on any ancestor of [dashboardName]; extra rows are harmless,
     *   because the walk selects by prefix rather than trusting the query. Rows of ANOTHER
     *   workspace are invisible (#191): a foreign binding at a nearer folder neither decides nor
     *   shadows — the walk continues past it as if the folder carried nothing, which is why this
     *   in-memory filter stays even though the repository query already applies the same
     *   predicate. Fail closed: if either layer were dropped, the other still refuses.
     */
    fun authorize(
        dashboardName: String,
        workspaceId: UUID?,
        apiKeyId: String?,
        bindings: Collection<DashboardKeyBinding>,
    ): Decision {
        val byFolder =
            bindings
                .asSequence()
                .filter { it.workspaceId == workspaceId }
                .groupBy { it.namePrefix }
        val deciding = ancestors(dashboardName).firstOrNull { byFolder[it]?.isNotEmpty() == true }
        return if (deciding == null) {
            Decision.Refused(Reason.UNBOUND)
        } else {
            bound(apiKeyId, byFolder.getValue(deciding))
        }
    }

    /** The bound case: the deciding folder's key set is the whole answer. */
    private fun bound(
        apiKeyId: String?,
        atFolder: List<DashboardKeyBinding>,
    ): Decision =
        if (apiKeyId != null && atFolder.any { it.apiKeyId == apiKeyId }) {
            Decision.Allowed
        } else {
            Decision.Refused(Reason.NOT_BOUND_AT_DECIDING_FOLDER)
        }

    companion object {
        /** The root binding — binds the workspace's whole dashboard tree. */
        const val ROOT = "/"

        /**
         * A dashboard name's binding ancestors, most specific first: the name itself (a folder
         * may legally share a name's spelling — `finance/dashboards/monthly` binds both the
         * dashboard NAMED that and everything beneath it, the endpoint tree's rule), then every
         * folder prefix, then the root.
         */
        fun ancestors(dashboardName: String): List<String> {
            val segments = dashboardName.trim('/').split('/').filter { it.isNotEmpty() }
            return (segments.size downTo 1).map { size -> segments.take(size).joinToString("/") } + ROOT
        }
    }
}
