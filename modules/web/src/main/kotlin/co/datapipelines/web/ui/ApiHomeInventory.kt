package co.datapipelines.web.ui

import co.datapipelines.application.endpoints.EndpointPublishService
import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.ApiKey
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.AuthenticatedPrincipal
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

/**
 * #639 — the API home's v2 words (ui-screens §4.18a): each panel's description and info
 * dialog (§4.18a's counting rules in the reader's words), and Continue your work's rows — the
 * console always, the Keys page only when the keys read answered (the keys tile's own condition).
 */
internal object ApiHomeWords {
    const val CONTINUE = "A clear next step for your endpoints and keys"

    /** Which state bar paints each metric's dot, so a dot and its bar never disagree; the others carry none. */
    val METRIC_BARS = mapOf("release" to "serving-release", "draft" to "serving-draft")

    /** A chart as its panel: a description under its heading and an info button — none for an unread keys chart. */
    fun panel(
        chart: OverviewPage.Chart,
        readable: Boolean,
    ): Panel.Bars {
        val explained = chart.key == ENDPOINTS || readable
        return Panel.Bars.of(chart).copy(
            description = if (explained) DESCRIPTIONS.getValue(chart.key) else null,
            explain = if (explained) chart.key else null,
        )
    }

    fun tasks(keysReadable: Boolean): List<OverviewPage.TaskRow> =
        listOfNotNull(
            OverviewPage.TaskRow(
                "console",
                "activity",
                "Open the console",
                "Every published path, what it serves and how to call it.",
                ApiOverviewController.CONSOLE,
                OverviewPage.TaskTone.RELEASED,
            ),
            OverviewPage
                .TaskRow(
                    "keys",
                    "key",
                    "Manage keys",
                    "See which keys are live, and which paths each one reaches.",
                    ApiOverviewController.KEYS_PAGE,
                    OverviewPage.TaskTone.RELEASED,
                ).takeIf { keysReadable },
        )

    val ENDPOINTS_EXPLAINED =
        OverviewPage.Explanation(
            key = ENDPOINTS,
            title = "How endpoints are counted",
            paragraphs =
                listOf(
                    "Every published path is counted once, legacy paths included, by what a call to it runs now. The " +
                        "four bars add up to Endpoints.",
                    "Serving a release runs the pipeline's released version; serving a draft runs a draft, development " +
                        "posture only; nothing to serve means the pipeline has no version to run. A legacy path was " +
                        "stored before today's path rules and never serves.",
                    "Call counts are not recorded anywhere in this system, so this page shows none.",
                ),
        )

    val KEYS_EXPLAINED =
        OverviewPage.Explanation(
            key = KEYS,
            title = "How keys are counted",
            paragraphs =
                listOf(
                    "The keys are the ones the Keys page lists for you: the whole workspace's when you may read its " +
                        "keys, otherwise your own MCP keys. The keys count includes live, expired and revoked keys.",
                    "The bars count live keys only, one bar per kind; a kind with no live key shows zero. A key is " +
                        "revoked before it is expired: one key, one bucket, and the note under the bars names how many of " +
                        "each were not counted.",
                    "A key is counted by name and kind. Its value is never shown here.",
                ),
        )

    private const val ENDPOINTS = "endpoint-state"
    private const val KEYS = "key-kind"

    private val DESCRIPTIONS =
        mapOf(
            ENDPOINTS to "Every published path once, by what a call to it runs now.",
            KEYS to "Live keys only, one bar per kind.",
        )
}
