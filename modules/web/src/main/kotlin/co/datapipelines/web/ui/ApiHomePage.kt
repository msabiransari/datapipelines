package co.datapipelines.web.ui

import co.datapipelines.auth.ApiKey
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.web.endpoints.PublishedEndpointController
import java.time.Instant

/**
 * #643 — the page builders for [ApiOverviewController].
 * #585 — the API home's panel and recent card, built from the two reads alone so a chart never
 * disagrees with its tiles. #639: the tiles are the strip's metrics, the charts its panels.
 */
internal object ApiHomePage {
    /**
     * The four tiles (three when the keys read failed), the two charts, and the strip they make:
     * the three endpoint metrics carry the page's [lensed] (the console's read is behind the promoter
     * lens), the keys metric never does — the Keys page's read is not lensed (#621) — so a promoter's
     * endpoint metrics each say they are admitted and the keys metric does not.
     */
    fun panel(
        endpoints: EndpointInventory,
        keys: KeyCounts?,
        keysHeading: String,
        lensed: Boolean,
    ): OverviewPage.InventoryPanel {
        val states = endpoints.counts()
        val tiles =
            listOfNotNull(
                OverviewPage.Count(
                    "endpoints",
                    "Endpoints",
                    states.values.sum(),
                    "Every published path, legacy ones included: the four bars below added together.",
                    ApiOverviewController.CONSOLE,
                ),
                OverviewPage.Count(
                    "release",
                    "Serving a release",
                    states.getValue(EndpointState.SERVING_RELEASE),
                    "A call runs the pipeline's released version.",
                    null,
                ),
                OverviewPage.Count(
                    "draft",
                    "Serving a draft",
                    states.getValue(EndpointState.SERVING_DRAFT),
                    "A call runs a draft version — development posture only.",
                    null,
                ),
                keys?.let {
                    OverviewPage.Count(
                        KEYS_TILE,
                        keysHeading,
                        it.visible,
                        "Live, expired and revoked — every key the Keys page lists for you.",
                        ApiOverviewController.KEYS_PAGE,
                    )
                },
            )
        val endpointChart = endpointChart(states)
        val tones = endpointChart.bars.associate { it.key to it.tone }
        val charts = listOf(endpointChart, keysChart(keys, keysHeading))
        return OverviewPage.InventoryPanel(
            counts = tiles,
            charts = charts,
            metrics =
                tiles.map { tile ->
                    OverviewPage.Metric
                        .of(tile, lensed = lensed && tile.key != KEYS_TILE)
                        .copy(tone = ApiHomeWords.METRIC_BARS[tile.key]?.let(tones::get))
                },
            panels = charts.map { ApiHomeWords.panel(it, readable = keys != null) },
            viewAll = states.values.sum(),
            explanations = listOfNotNull(ApiHomeWords.ENDPOINTS_EXPLAINED, keys?.let { ApiHomeWords.KEYS_EXPLAINED }),
        )
    }

    private fun endpointChart(states: Map<EndpointState, Long>): OverviewPage.Chart =
        OverviewPage
            .Chart(
                key = "endpoint-state",
                caption = "Endpoints by state",
                bars =
                    EndpointState.entries.map { state ->
                        val (name, tone) = STATE_WORDS.getValue(state)
                        bar(state.name.lowercase().replace('_', '-'), name, states.getValue(state), tone, ApiOverviewController.CONSOLE)
                    },
                note = "What a call to each path runs now. A legacy path was stored before today's path rules and never serves.",
                headings = listOf("State", "Endpoints"),
            ).withWidths()

    private fun keysChart(
        keys: KeyCounts?,
        keysHeading: String,
    ): OverviewPage.Chart =
        if (keys == null) {
            OverviewPage.Chart(
                key = "key-kind",
                caption = "Keys by kind — unavailable",
                bars = emptyList(),
                note = "The keys could not be read, so no key is counted here.",
                headings = listOf("Kind", "Live keys"),
            )
        } else {
            OverviewPage
                .Chart(
                    key = "key-kind",
                    caption = "Keys by kind",
                    bars =
                        ApiKeyKind.entries.map { kind ->
                            bar(
                                kind.wire,
                                ApiKeyRows.kindLabel(kind),
                                keys.liveByKind.getValue(kind),
                                "released",
                                ApiOverviewController.KEYS_PAGE,
                            )
                        },
                    note =
                        "Live keys only, from ${keysHeading.lowercase()}. Not counted: ${keys.expired} expired, " +
                            "${keys.revoked} revoked.",
                    headings = listOf("Kind", "Live keys"),
                ).withWidths()
        }

    /** Continue your work: the newest endpoints and the newest keys, each its own section and its own failure. */
    fun recent(
        endpoints: EndpointInventory?,
        keys: List<ApiKey>?,
        now: Instant,
    ): OverviewPage.RecentPanel =
        OverviewPage.RecentPanel(
            review = null,
            tasks = ApiHomeWords.tasks(keysReadable = keys != null),
            description = ApiHomeWords.CONTINUE,
            sections =
                listOf(
                    OverviewPage.RecentSection(
                        heading = "Recently published",
                        note = "The newest published paths in this workspace.",
                        entries =
                            endpoints?.let { inventory ->
                                inventory.valid.sortedByDescending { it.createdAt }.take(RECENT_LIMIT).map { endpoint ->
                                    val pipeline = inventory.names[endpoint.pipelineId]
                                    val served =
                                        inventory.served[endpoint.pipelineId]?.let { "v${it.version}${if (it.draft) " · draft" else ""}" }
                                            ?: "nothing to serve"
                                    item(
                                        name =
                                            "${PublishedEndpointController.ROOT}${endpoint.pathPattern} · " +
                                                "${pipeline?.displayName ?: pipeline?.name ?: DELETED_PIPELINE} · $served",
                                        href = ApiOverviewController.CONSOLE,
                                        at = endpoint.createdAt,
                                        now = now,
                                    )
                                }
                            },
                        unavailable = if (endpoints == null) "The endpoints could not be read." else null,
                        emptyNote = "Nothing is published yet. Quick start says how.",
                    ),
                    OverviewPage.RecentSection(
                        heading = "Recently created keys",
                        note = "The newest keys you can see, by name — never a key's value.",
                        entries =
                            keys?.sortedByDescending { it.createdAt }?.take(RECENT_LIMIT)?.map { key ->
                                item(
                                    name = "${key.name} · ${ApiKeyRows.kindLabel(key.kind)} · ${stateWord(key, now)}",
                                    href = ApiOverviewController.KEYS_PAGE,
                                    at = key.createdAt,
                                    now = now,
                                )
                            },
                        unavailable = if (keys == null) "The keys could not be read." else null,
                        emptyNote = "No keys yet. A key is created by a person on the Keys page.",
                    ),
                ),
        )

    private fun stateWord(
        key: ApiKey,
        now: Instant,
    ): String =
        when {
            key.isRevoked -> "revoked"
            ApiKeyRows.isExpired(key, now) -> "expired"
            else -> "live"
        }

    private fun item(
        name: String,
        href: String,
        at: Instant,
        now: Instant,
    ): OverviewPage.RecentItem =
        OverviewPage.RecentItem(
            name = name,
            href = href,
            datetime = at.toString(),
            ago = RelativeTime.since(at, now),
            absolute = RelativeTime.absolute(at),
            hasDraft = false,
        )

    private fun bar(
        key: String,
        name: String,
        value: Long,
        tone: String,
        href: String,
    ): OverviewPage.Bar = OverviewPage.Bar(key, name, value, "0", tone, "$name: $value", href)

    /** Bar widths are shares of the chart's own tallest bar; an all-zero chart is all zero-width. */
    private fun OverviewPage.Chart.withWidths(): OverviewPage.Chart {
        val tallest = bars.maxOfOrNull { it.value } ?: 0L
        return copy(bars = bars.map { it.copy(width = OverviewPage.width(it.value, tallest)) })
    }

    /** The console's state words: warning for a serving draft, never danger red on an ordinary state. */
    private val STATE_WORDS =
        mapOf(
            EndpointState.SERVING_RELEASE to ("Serving a release" to "released"),
            EndpointState.SERVING_DRAFT to ("Serving a draft" to "warning"),
            EndpointState.NOTHING_TO_SERVE to ("Nothing to serve" to "mixed"),
            EndpointState.LEGACY to ("Legacy path" to "discarded"),
        )

    private const val RECENT_LIMIT = 5

    /** The keys tile's key: the one metric the promoter lens never narrows. */
    private const val KEYS_TILE = "keys"

    /** The console's word for a pipeline deleted out from under its endpoint. */
    private const val DELETED_PIPELINE = "(deleted pipeline)"
}
