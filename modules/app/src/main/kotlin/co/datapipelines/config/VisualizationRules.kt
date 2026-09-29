package co.datapipelines.config

/**
 * §7 / §3.33 (#10) — the dashboard documents' collection bounds: every `datapipelines.visualization.*` key an
 * integer within its bounds. A value arrives as a raw STRING, so a malformed one is a NAMED violation here
 * rather than a binder crash; an unset block is not a violation (application.yml always supplies all five).
 *
 * The bounds are the twins of `VisualizationKey` in `modules/visualization` (`app` compiles against `web`
 * only, so it cannot import them); `VisualizationConfigKeysSpecDriftTest` there parses THIS file and holds
 * every key and bound equal to the enum, the doc and `application.yml`. Owns its file like `ParametersRules`.
 */
internal object VisualizationRules {
    /** One key's inclusive bounds. */
    private data class Bound(
        val key: String,
        val min: Long,
        val max: Long,
    )

    private val BOUNDS =
        listOf(
            Bound(key = "datapipelines.visualization.max-visualizations-per-dashboard", min = 1, max = 500),
            Bound(key = "datapipelines.visualization.max-cases-per-visualization", min = 1, max = 200),
            Bound(key = "datapipelines.visualization.max-fixture-rows-per-case", min = 1, max = 100_000),
            Bound(key = "datapipelines.visualization.max-config-bytes", min = 1_024, max = 2_097_152),
            Bound(key = "datapipelines.visualization.max-bindings-per-visualization", min = 1, max = 1_024),
        )

    /** Every key this rule reads — `ConfigValidator.snapshotFrom` reads exactly these off the environment. */
    val KEYS: List<String> = BOUNDS.map { it.key }

    fun checkVisualizationBounds(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
    ) {
        BOUNDS.forEach { bound ->
            val text = snapshot.visualization[bound.key]?.trim() ?: return@forEach
            val value = text.toLongOrNull()
            when {
                value == null -> violations += "${bound.key} is '$text'; §3.33 requires an integer."
                value !in bound.min..bound.max -> violations += "${bound.key} ($value) must be ${bound.min}..${bound.max} (§3.33)."
            }
        }
    }
}
