package co.datapipelines.config

/**
 * §7 / §3.30 (#194) — the parameter engine's limits (the design record §11): every
 * `datapipelines.parameters.*` key an integer within its bounds, and one selector statement's timeout
 * no longer than its evaluate's. A value arrives as a raw STRING, so a malformed one is a NAMED
 * violation here rather than a binder crash; an unset block is not a violation (application.yml always
 * supplies all fifteen).
 *
 * The bounds are the twins of `ParametersKey` in `modules/parameters` (`app` compiles against `web`
 * only, so it cannot import them); `ParametersConfigKeysSpecDriftTest` there parses THIS file and holds
 * every key and bound equal to the enum, the doc and `application.yml`. Owns its file like
 * `TransformRules` — the validator's companion is the registry, not the home, of the §3.x families.
 */
internal object ParametersRules {
    private const val PREFIX = "datapipelines.parameters."

    /** One key's inclusive bounds; [max] null means the bound is another key (the cross-key rule below). */
    private data class Bound(
        val key: String,
        val min: Long,
        val max: Long?,
    )

    private val BOUNDS =
        listOf(
            Bound(key = "datapipelines.parameters.max-parameters-per-set", min = 1, max = 256),
            Bound(key = "datapipelines.parameters.max-options-per-selector", min = 1, max = 10_000),
            Bound(key = "datapipelines.parameters.max-multi-bind-values", min = 1, max = 1_000),
            Bound(key = "datapipelines.parameters.max-binds-per-statement", min = 1, max = 2_000),
            Bound(key = "datapipelines.parameters.max-option-value-chars", min = 1, max = 65_536),
            Bound(key = "datapipelines.parameters.max-option-label-chars", min = 1, max = 4_096),
            Bound(key = "datapipelines.parameters.max-input-length", min = 1, max = 65_536),
            Bound(key = "datapipelines.parameters.max-expression-depth", min = 1, max = 64),
            Bound(key = "datapipelines.parameters.max-expression-nodes", min = 1, max = 1_024),
            Bound(key = "datapipelines.parameters.max-regex-steps", min = 1_000, max = 10_000_000),
            Bound(key = "datapipelines.parameters.evaluate-timeout-seconds", min = 1, max = 300),
            Bound(key = "datapipelines.parameters.selector-query-timeout-seconds", min = 1, max = null),
            Bound(key = "datapipelines.parameters.max-concurrent-selector-queries", min = 1, max = 32),
            Bound(key = "datapipelines.parameters.max-waiting-selector-queries", min = 0, max = 1_024),
            Bound(key = "datapipelines.parameters.max-evaluate-response-bytes", min = 65_536, max = 67_108_864),
        )

    /** Every key this rule reads — `ConfigValidator.snapshotFrom` reads exactly these off the environment. */
    val KEYS: List<String> = BOUNDS.map { it.key }

    fun checkParametersBounds(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
    ) {
        val raw = snapshot.parameters
        if (raw.values.all { it == null }) return
        val values = HashMap<String, Long>()
        BOUNDS.forEach { bound ->
            val text = raw[bound.key]?.trim() ?: return@forEach
            val value = text.toLongOrNull()
            val upper = bound.max
            when {
                value == null -> {
                    violations += "${bound.key} is '$text'; §3.30 requires an integer."
                }

                value < bound.min || (upper != null && value > upper) -> {
                    violations += "${bound.key} ($value) must be ${bound.min}..${upper ?: "evaluate-timeout-seconds"} (§3.30)."
                }

                else -> {
                    values[bound.key] = value
                }
            }
        }
        val evaluate = values["${PREFIX}evaluate-timeout-seconds"]
        val selector = values["${PREFIX}selector-query-timeout-seconds"]
        if (evaluate != null && selector != null && selector > evaluate) {
            violations +=
                "${PREFIX}selector-query-timeout-seconds ($selector) must not exceed ${PREFIX}evaluate-timeout-seconds " +
                "($evaluate) — one selector statement must fit inside its evaluate (§3.30)."
        }
    }
}
