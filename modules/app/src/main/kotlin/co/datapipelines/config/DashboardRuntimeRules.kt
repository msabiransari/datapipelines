package co.datapipelines.config

/**
 * §7 / §3.34 (#10 L2) — the dashboard runtime's numbers at boot: every `datapipelines.dashboards.*` key an
 * integer within its bounds, plus the three relations the runtime's arithmetic needs (default deadline ≤ cap,
 * source byte cap ≤ refresh byte cap, one refresh's execution ceiling ≤ the instance-wide dashboard cap — else a
 * maximal refresh could never be admitted). A value arrives as a raw STRING, so a malformed one is a NAMED
 * violation here rather than a binder crash; an unset block is not a violation (application.yml supplies all ten).
 *
 * The bounds are the twins of `DashboardRuntimeKey` in `modules/visualization` (`app` compiles against `web`
 * only, so it cannot import them); `DashboardRuntimeConfigKeysSpecDriftTest` there parses THIS file and holds
 * every key and bound equal to the enum, the doc and `application.yml`. Owns its file like `VisualizationRules`.
 */
internal object DashboardRuntimeRules {
    /** One key's inclusive bounds. */
    private data class Bound(
        val key: String,
        val min: Long,
        val max: Long,
    )

    private val BOUNDS =
        listOf(
            Bound(key = "datapipelines.dashboards.admission.max-concurrent-refreshes-per-workspace", min = 1, max = 100),
            Bound(key = "datapipelines.dashboards.admission.max-executions-per-refresh", min = 1, max = 200),
            Bound(key = "datapipelines.dashboards.admission.max-concurrent-dashboard-executions-per-instance", min = 1, max = 1_000),
            Bound(key = "datapipelines.dashboards.admission.max-wait-seconds", min = 0, max = 120),
            Bound(key = "datapipelines.dashboards.results.max-bytes-per-source", min = 1_024, max = 268_435_456),
            Bound(key = "datapipelines.dashboards.results.max-bytes-per-refresh", min = 1_024, max = 1_073_741_824),
            Bound(key = "datapipelines.dashboards.timeouts.default-refresh-seconds", min = 1, max = 3_600),
            Bound(key = "datapipelines.dashboards.timeouts.max-refresh-seconds", min = 1, max = 7_200),
            Bound(key = "datapipelines.dashboards.timeouts.parameter-lock-seconds", min = 1, max = 600),
            Bound(key = "datapipelines.dashboards.timeouts.render-seconds", min = 1, max = 600),
        )

    /** Every key this rule reads — `ConfigValidator.snapshotFrom` reads exactly these off the environment. */
    val KEYS: List<String> = BOUNDS.map { it.key }

    /** A relation: [smaller] may not exceed [larger]; judged only when both parse. */
    private data class Relation(
        val smaller: String,
        val larger: String,
    )

    private val RELATIONS =
        listOf(
            Relation("datapipelines.dashboards.timeouts.default-refresh-seconds", "datapipelines.dashboards.timeouts.max-refresh-seconds"),
            Relation("datapipelines.dashboards.results.max-bytes-per-source", "datapipelines.dashboards.results.max-bytes-per-refresh"),
            Relation(
                "datapipelines.dashboards.admission.max-executions-per-refresh",
                "datapipelines.dashboards.admission.max-concurrent-dashboard-executions-per-instance",
            ),
        )

    fun checkDashboardRuntimeBounds(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
    ) {
        val parsed = mutableMapOf<String, Long>()
        BOUNDS.forEach { bound ->
            val text = snapshot.dashboards[bound.key]?.trim() ?: return@forEach
            val value = text.toLongOrNull()
            when {
                value == null -> violations += "${bound.key} is '$text'; §3.34 requires an integer."
                value !in bound.min..bound.max -> violations += "${bound.key} ($value) must be ${bound.min}..${bound.max} (§3.34)."
                else -> parsed[bound.key] = value
            }
        }
        RELATIONS.forEach { relation ->
            val smaller = parsed[relation.smaller]
            val larger = parsed[relation.larger]
            if (smaller != null && larger != null && smaller > larger) {
                violations += "${relation.smaller} ($smaller) must not exceed ${relation.larger} ($larger) (§3.34)."
            }
        }
    }
}
