package co.datapipelines.visualization

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `datapipelines.visualization.*` — one row per key (configuration.md §3.33, the one definition — D8): the YAML
 * key, its default and its inclusive bounds. Both [VisualizationConfig] and [VisualizationProperties] take their
 * defaults from here and [VisualizationConfig] enforces the bounds, so no literal lives in two places;
 * `VisualizationConfigKeysSpecDriftTest` holds this table, the doc, `application.yml` and `ConfigValidator`
 * together.
 *
 * The five bounds are the readers' collection caps, each checked BEFORE the collection's members are walked so
 * a document cannot make the read work in proportion to its own size. Their defaults are L1a's PROPOSAL
 * (50 / 20 / 1,000 / 256 KiB / 64) — the orchestrator confirms them at review.
 */
enum class VisualizationKey(
    val key: String,
    val default: Long,
    val min: Long,
    val max: Long,
) {
    MAX_VISUALIZATIONS_PER_DASHBOARD(key = "max-visualizations-per-dashboard", default = 50, min = 1, max = 500),
    MAX_CASES_PER_VISUALIZATION(key = "max-cases-per-visualization", default = 20, min = 1, max = 200),
    MAX_FIXTURE_ROWS_PER_CASE(key = "max-fixture-rows-per-case", default = 1_000, min = 1, max = 100_000),
    MAX_CONFIG_BYTES(key = "max-config-bytes", default = 262_144, min = 1_024, max = 2_097_152),
    MAX_BINDINGS_PER_VISUALIZATION(key = "max-bindings-per-visualization", default = 64, min = 1, max = 1_024),
    ;

    /** The full YAML path. */
    val path: String get() = "$PREFIX.$key"

    /** Refuses [value] outside this key's bounds, naming the key (the `ParametersKey.check` shape). */
    fun check(value: Long) {
        require(value in min..max) { "$path must be $min..$max, was $value" }
    }

    companion object {
        const val PREFIX: String = "datapipelines.visualization"
    }
}

/**
 * The module's bounds as the readers take them — plain, validated at construction. [VisualizationProperties]
 * is its Spring binding twin.
 */
data class VisualizationConfig(
    val maxVisualizationsPerDashboard: Int = VisualizationKey.MAX_VISUALIZATIONS_PER_DASHBOARD.default.toInt(),
    val maxCasesPerVisualization: Int = VisualizationKey.MAX_CASES_PER_VISUALIZATION.default.toInt(),
    val maxFixtureRowsPerCase: Int = VisualizationKey.MAX_FIXTURE_ROWS_PER_CASE.default.toInt(),
    val maxConfigBytes: Int = VisualizationKey.MAX_CONFIG_BYTES.default.toInt(),
    val maxBindingsPerVisualization: Int = VisualizationKey.MAX_BINDINGS_PER_VISUALIZATION.default.toInt(),
) {
    init {
        valuesByKey().forEach { (key, value) -> key.check(value) }
    }

    /** Every key with its value — the bounds check's and the drift test's one enumeration. */
    fun valuesByKey(): Map<VisualizationKey, Long> =
        linkedMapOf(
            VisualizationKey.MAX_VISUALIZATIONS_PER_DASHBOARD to maxVisualizationsPerDashboard.toLong(),
            VisualizationKey.MAX_CASES_PER_VISUALIZATION to maxCasesPerVisualization.toLong(),
            VisualizationKey.MAX_FIXTURE_ROWS_PER_CASE to maxFixtureRowsPerCase.toLong(),
            VisualizationKey.MAX_CONFIG_BYTES to maxConfigBytes.toLong(),
            VisualizationKey.MAX_BINDINGS_PER_VISUALIZATION to maxBindingsPerVisualization.toLong(),
        )
}

/**
 * The Spring binding of `datapipelines.visualization.*` (module-structure §8.3) — the same fields and defaults
 * as [VisualizationConfig], which [toConfig] builds (and so bound-checks). Registered by the surfaces' wiring
 * when the module gains one (L1b); until then the keys are documented, shipped in `application.yml` and
 * bounds-checked at boot by `ConfigValidator`.
 */
@ConfigurationProperties(prefix = VisualizationKey.PREFIX)
data class VisualizationProperties(
    val maxVisualizationsPerDashboard: Int = VisualizationKey.MAX_VISUALIZATIONS_PER_DASHBOARD.default.toInt(),
    val maxCasesPerVisualization: Int = VisualizationKey.MAX_CASES_PER_VISUALIZATION.default.toInt(),
    val maxFixtureRowsPerCase: Int = VisualizationKey.MAX_FIXTURE_ROWS_PER_CASE.default.toInt(),
    val maxConfigBytes: Int = VisualizationKey.MAX_CONFIG_BYTES.default.toInt(),
    val maxBindingsPerVisualization: Int = VisualizationKey.MAX_BINDINGS_PER_VISUALIZATION.default.toInt(),
) {
    /** The domain config — constructing it enforces every bound, naming the key. */
    fun toConfig(): VisualizationConfig =
        VisualizationConfig(
            maxVisualizationsPerDashboard = maxVisualizationsPerDashboard,
            maxCasesPerVisualization = maxCasesPerVisualization,
            maxFixtureRowsPerCase = maxFixtureRowsPerCase,
            maxConfigBytes = maxConfigBytes,
            maxBindingsPerVisualization = maxBindingsPerVisualization,
        )
}
