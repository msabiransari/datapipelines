package co.datapipelines.web.ui

import co.datapipelines.application.endpoints.EndpointPublishService
import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.ApiKey
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.AuthenticatedPrincipal
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * #585 — the API home's two reads, each under the narrowing its own surface already applies, so
 * the home's numbers are the console's and the Keys page's numbers by construction:
 *
 *  - **Endpoints** through [EndpointPublishService.list] and [EndpointPublishService.listLegacy] —
 *    the console's two lists, behind the promoter lens — and the served version per pipeline
 *    from [PipelineNames.servedVersions], the console's own lookup.
 *  - **Keys** through [keysVisibleTo] — the Keys page's rule: the workspace's keys with
 *    `api_key.read`, otherwise the caller's own `mcp` keys.
 *
 * Nothing here selects a credential: the key rows are the repository's metadata (kind, name,
 * flags, times) and no value of them but those reaches the page.
 */
@Component
class ApiHomeReads(
    private val publishing: EndpointPublishService,
    private val pipelineNames: PipelineNames,
    private val apiKeys: ApiKeyRepository,
    private val lens: PromoterLens,
) {
    /**
     * Whether the endpoint counts are an admitted subset — the pipelines arm of the caller's
     * view, the one [EndpointPublishService.list] filters by. An unreadable view reads as lensed
     * (OverviewController's rule): saying "admitted" about everything is the safe misstatement.
     */
    fun lensed(principal: AuthenticatedPrincipal): Boolean =
        runCatching { !lens.viewFor(principal).pipelines.isEverything }.getOrDefault(true)

    /** The endpoints [principal] may see in their workspace, with what each one serves. */
    fun endpoints(principal: AuthenticatedPrincipal): EndpointInventory {
        val workspaceId = principal.requireWorkspace().id
        val valid = publishing.list(principal)
        val legacy = publishing.listLegacy(principal)
        val ids = valid.map { it.pipelineId }.toSet()
        return EndpointInventory(
            valid = valid,
            legacyCount = legacy.size,
            served = pipelineNames.servedVersions(workspaceId, ids),
            names = pipelineNames.lookup(workspaceId, ids),
        )
    }

    /** The keys [principal] sees — exactly the rows the Keys page lists for them. */
    fun keys(principal: AuthenticatedPrincipal): List<ApiKey> =
        keysVisibleTo(principal, apiKeys.findByWorkspace(principal.requireWorkspace().id))
}

/** One endpoints read: the valid rows, how many legacy rows sit beside them, and each pipeline's served pointer and name. */
data class EndpointInventory(
    val valid: List<PublishedEndpoint>,
    val legacyCount: Int,
    val served: Map<UUID, ServedVersion>,
    val names: Map<UUID, PipelineName>,
) {
    /** What the endpoint at [endpoint] answers with — the console's served cell, as a bucket. */
    fun stateOf(endpoint: PublishedEndpoint): EndpointState =
        when (served[endpoint.pipelineId]?.draft) {
            null -> EndpointState.NOTHING_TO_SERVE
            true -> EndpointState.SERVING_DRAFT
            false -> EndpointState.SERVING_RELEASE
        }

    /** Every published path, legacy ones included, counted once in exactly one state. */
    fun counts(): Map<EndpointState, Long> {
        val byState = valid.groupingBy { stateOf(it) }.eachCount()
        return EndpointState.entries.associateWith { state ->
            if (state == EndpointState.LEGACY) legacyCount.toLong() else (byState[state] ?: 0).toLong()
        }
    }
}

/**
 * The endpoints chart's exclusive buckets, top to bottom: the console's served cell ("v3",
 * "v3 · draft", "nothing to serve") and its legacy section (a stored path today's grammar
 * refuses — it never serves).
 */
enum class EndpointState {
    SERVING_RELEASE,
    SERVING_DRAFT,
    NOTHING_TO_SERVE,
    LEGACY,
}

/**
 * The keys a caller sees, bucketed: [liveByKind] has every [ApiKeyKind] (a zero is shown as a
 * zero), and a key counts in exactly one of live, [expired] or [revoked] — revoked first, as the
 * Keys page sorts it, then expired by [ApiKeyRows.isExpired], the table's own test.
 */
data class KeyCounts(
    val visible: Long,
    val liveByKind: Map<ApiKeyKind, Long>,
    val expired: Long,
    val revoked: Long,
) {
    companion object {
        fun of(
            keys: List<ApiKey>,
            now: Instant,
        ): KeyCounts {
            val revoked = keys.count { it.isRevoked }
            val expired = keys.count { !it.isRevoked && ApiKeyRows.isExpired(it, now) }
            val live = keys.filter { !it.isRevoked && !ApiKeyRows.isExpired(it, now) }.groupingBy { it.kind }.eachCount()
            return KeyCounts(
                visible = keys.size.toLong(),
                liveByKind = ApiKeyKind.entries.associateWith { (live[it] ?: 0).toLong() },
                expired = expired.toLong(),
                revoked = revoked.toLong(),
            )
        }
    }
}
