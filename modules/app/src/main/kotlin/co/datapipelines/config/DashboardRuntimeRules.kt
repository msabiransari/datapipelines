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
 *
 * #762 adds the executor's instance ceiling in [checkExecutorCeiling] — its own function and its own shape, not a
 * [Relation] row: the drift test holds [RELATIONS] to the three `DashboardRuntimeConfig` enforces, and the executor
 * key is not a dashboards key.
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
        val parsed = parsed(snapshot, violations)
        RELATIONS.forEach { relation ->
            val smaller = parsed[relation.smaller]
            val larger = parsed[relation.larger]
            if (smaller != null && larger != null && smaller > larger) {
                violations += "${relation.smaller} ($smaller) must not exceed ${relation.larger} ($larger) (§3.34)."
            }
        }
    }

    /**
     * §3.34 / §3.2 (#762) — a refresh reserves one slot of `datapipelines.executor.max-concurrent-executions-per-instance`
     * per distinct execution, all at once, so the two dashboards keys that size a reservation are judged against it:
     *
     * - `max-executions-per-refresh` above it is REFUSED — a maximal valid refresh could never be admitted, every
     *   refresh of such a dashboard would be a permanent 429 (the third relation's rationale, one level down). Production
     *   ran 3 against 16 for six weeks and nothing said so.
     * - `max-concurrent-dashboard-executions-per-instance` above it is a WARNING — that cap can never be reached, so it
     *   bounds nothing; wasteful, not fatal (a deployment running 40 against an executor cap of 20 must still boot).
     *
     * The ceiling in effect is resolved the way the executor binds it: the deprecated `…-global` alias when set (its
     * value runs, `checkExecutorConcurrencyAlias` says so), else the key, else [defaultExecutorCeiling]. A non-integer
     * or non-positive ceiling is not judged here (the binder or the executor's own config refuses it), nor is a
     * dashboards key that does not parse within its bounds ([checkDashboardRuntimeBounds] names it).
     */
    fun checkExecutorCeiling(
        snapshot: ConfigSnapshot,
        defaultExecutorCeiling: Int,
        violations: MutableList<String>,
        warnings: MutableList<String>,
    ) {
        val (executorKey, ceiling) = executorCeiling(snapshot, defaultExecutorCeiling) ?: return
        val parsed = parsed(snapshot, violations = null)
        parsed[MAX_EXECUTIONS_PER_REFRESH]?.takeIf { it > ceiling }?.let { perRefresh ->
            violations +=
                "$MAX_EXECUTIONS_PER_REFRESH ($perRefresh) must not exceed $executorKey ($ceiling) (§3.34): a refresh " +
                "reserves one executor slot per distinct execution, all at once, so a dashboard at the per-refresh bound " +
                "could never be admitted. Raise $executorKey to at least $perRefresh, or lower $MAX_EXECUTIONS_PER_REFRESH."
        }
        parsed[MAX_DASHBOARD_EXECUTIONS_PER_INSTANCE]?.takeIf { it > ceiling }?.let { perInstance ->
            warnings +=
                "event=config.dashboard_executions_above_executor_ceiling " +
                "message=\"$MAX_DASHBOARD_EXECUTIONS_PER_INSTANCE ($perInstance) exceeds $executorKey ($ceiling): dashboard " +
                "refreshes can never hold more than $ceiling executor slots at once on this instance, so the dashboards " +
                "cap bounds nothing above that (§3.34). Lower it to at most $ceiling, or raise $executorKey.\""
        }
    }

    /** The executor ceiling in effect and the key it comes from, or null when it is not a positive integer. */
    private fun executorCeiling(
        snapshot: ConfigSnapshot,
        default: Int,
    ): Pair<String, Long>? {
        val alias = snapshot.executorMaxConcurrentGlobal?.trim()?.takeIf { it.isNotEmpty() }
        val (key, raw) =
            when {
                alias != null -> EXECUTOR_ALIAS_KEY to alias
                snapshot.executorMaxConcurrentPerInstance != null -> EXECUTOR_KEY to snapshot.executorMaxConcurrentPerInstance.trim()
                else -> EXECUTOR_KEY to default.toString()
            }
        return raw.toLongOrNull()?.takeIf { it > 0 }?.let { key to it }
    }

    /** Every dashboards key that parses within its bounds; each one that does not is named into [violations] when given. */
    private fun parsed(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>?,
    ): Map<String, Long> {
        val parsed = mutableMapOf<String, Long>()
        BOUNDS.forEach { bound ->
            val text = snapshot.dashboards[bound.key]?.trim() ?: return@forEach
            val value = text.toLongOrNull()
            when {
                value == null -> violations?.add("${bound.key} is '$text'; §3.34 requires an integer.")
                value !in bound.min..bound.max -> violations?.add("${bound.key} ($value) must be ${bound.min}..${bound.max} (§3.34).")
                else -> parsed[bound.key] = value
            }
        }
        return parsed
    }

    private const val EXECUTOR_KEY = "datapipelines.executor.max-concurrent-executions-per-instance"
    private const val EXECUTOR_ALIAS_KEY = "datapipelines.executor.max-concurrent-executions-global"
    private const val MAX_EXECUTIONS_PER_REFRESH = "datapipelines.dashboards.admission.max-executions-per-refresh"
    private const val MAX_DASHBOARD_EXECUTIONS_PER_INSTANCE =
        "datapipelines.dashboards.admission.max-concurrent-dashboard-executions-per-instance"
}
