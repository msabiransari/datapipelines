package co.datapipelines.parameters

import co.datapipelines.typesystem.ParameterValueLimits
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `datapipelines.parameters.*` — one row per key of record §11 (configuration.md §3.30, the one
 * definition — D8): the YAML key, its default and its inclusive bounds. Both [ParametersConfig] and
 * [ParametersProperties] take their defaults from here and [ParametersConfig] enforces the bounds, so
 * no literal lives in two places (MISTAKES: the `mapOf(… to 180)` twin-class trap);
 * `ParametersConfigKeysSpecDriftTest` holds this table, the doc, `application.yml` and
 * `ConfigValidator` together.
 *
 * [max] null means the bound is another key's value (`selector-query-timeout-seconds` ≤
 * `evaluate-timeout-seconds`), checked by [ParametersConfig]'s cross-key rule.
 */
enum class ParametersKey(
    val key: String,
    val default: Long,
    val min: Long,
    val max: Long?,
) {
    MAX_PARAMETERS_PER_SET(key = "max-parameters-per-set", default = 64, min = 1, max = 256),
    MAX_OPTIONS_PER_SELECTOR(key = "max-options-per-selector", default = 200, min = 1, max = 10_000),
    MAX_MULTI_BIND_VALUES(key = "max-multi-bind-values", default = 1_000, min = 1, max = 1_000),
    MAX_BINDS_PER_STATEMENT(key = "max-binds-per-statement", default = 2_000, min = 1, max = 2_000),
    MAX_OPTION_VALUE_CHARS(key = "max-option-value-chars", default = 1_024, min = 1, max = 65_536),
    MAX_OPTION_LABEL_CHARS(key = "max-option-label-chars", default = 256, min = 1, max = 4_096),
    MAX_INPUT_LENGTH(key = "max-input-length", default = 4_096, min = 1, max = 65_536),
    MAX_EXPRESSION_DEPTH(key = "max-expression-depth", default = 16, min = 1, max = 64),
    MAX_EXPRESSION_NODES(key = "max-expression-nodes", default = 128, min = 1, max = 1_024),
    MAX_REGEX_STEPS(key = "max-regex-steps", default = 100_000, min = 1_000, max = 10_000_000),
    EVALUATE_TIMEOUT_SECONDS(key = "evaluate-timeout-seconds", default = 30, min = 1, max = 300),
    SELECTOR_QUERY_TIMEOUT_SECONDS(key = "selector-query-timeout-seconds", default = 10, min = 1, max = null),
    MAX_CONCURRENT_SELECTOR_QUERIES(key = "max-concurrent-selector-queries", default = 4, min = 1, max = 32),
    MAX_WAITING_SELECTOR_QUERIES(key = "max-waiting-selector-queries", default = 64, min = 0, max = 1_024),
    MAX_EVALUATE_RESPONSE_BYTES(key = "max-evaluate-response-bytes", default = 4_194_304, min = 65_536, max = 67_108_864),
    ;

    /** The full YAML path. */
    val path: String get() = "$PREFIX.$key"

    /** Refuses [value] outside this key's bounds, naming the key (the `SchedulerProperties.init` shape). */
    fun check(value: Long) {
        val upper = max
        require(value >= min && (upper == null || value <= upper)) {
            "$path must be $min..${upper ?: "(see configuration.md §3.30)"}, was $value"
        }
    }

    companion object {
        const val PREFIX: String = "datapipelines.parameters"
    }
}

/**
 * The engine's limits as the domain reads them (record §11) — plain, validated at construction, the
 * shape the validator and the services take. [ParametersProperties] is its Spring binding twin.
 */
data class ParametersConfig(
    val maxParametersPerSet: Int = ParametersKey.MAX_PARAMETERS_PER_SET.default.toInt(),
    val maxOptionsPerSelector: Int = ParametersKey.MAX_OPTIONS_PER_SELECTOR.default.toInt(),
    val maxMultiBindValues: Int = ParametersKey.MAX_MULTI_BIND_VALUES.default.toInt(),
    val maxBindsPerStatement: Int = ParametersKey.MAX_BINDS_PER_STATEMENT.default.toInt(),
    val maxOptionValueChars: Int = ParametersKey.MAX_OPTION_VALUE_CHARS.default.toInt(),
    val maxOptionLabelChars: Int = ParametersKey.MAX_OPTION_LABEL_CHARS.default.toInt(),
    val maxInputLength: Int = ParametersKey.MAX_INPUT_LENGTH.default.toInt(),
    val maxExpressionDepth: Int = ParametersKey.MAX_EXPRESSION_DEPTH.default.toInt(),
    val maxExpressionNodes: Int = ParametersKey.MAX_EXPRESSION_NODES.default.toInt(),
    val maxRegexSteps: Long = ParametersKey.MAX_REGEX_STEPS.default,
    val evaluateTimeoutSeconds: Long = ParametersKey.EVALUATE_TIMEOUT_SECONDS.default,
    val selectorQueryTimeoutSeconds: Long = ParametersKey.SELECTOR_QUERY_TIMEOUT_SECONDS.default,
    val maxConcurrentSelectorQueries: Int = ParametersKey.MAX_CONCURRENT_SELECTOR_QUERIES.default.toInt(),
    val maxWaitingSelectorQueries: Int = ParametersKey.MAX_WAITING_SELECTOR_QUERIES.default.toInt(),
    val maxEvaluateResponseBytes: Long = ParametersKey.MAX_EVALUATE_RESPONSE_BYTES.default,
) {
    init {
        valuesByKey().forEach { (key, value) -> key.check(value) }
        require(selectorQueryTimeoutSeconds <= evaluateTimeoutSeconds) {
            "${ParametersKey.SELECTOR_QUERY_TIMEOUT_SECONDS.path} ($selectorQueryTimeoutSeconds) must not exceed " +
                "${ParametersKey.EVALUATE_TIMEOUT_SECONDS.path} ($evaluateTimeoutSeconds) — one statement must fit inside its evaluate"
        }
    }

    /**
     * The shared value validator's bounds (record P31, P34): the regex read budget, and the engine's
     * `max_length` default for an `INPUT` that states none — an unbounded string is never accepted BY
     * THE ENGINE (a pipeline parameter keeps none).
     */
    val valueLimits: ParameterValueLimits
        get() = ParameterValueLimits(maxRegexReads = maxRegexSteps, defaultMaxLength = maxInputLength)

    /** Every key with its value — the bounds check's and the drift test's one enumeration. */
    fun valuesByKey(): Map<ParametersKey, Long> =
        linkedMapOf(
            ParametersKey.MAX_PARAMETERS_PER_SET to maxParametersPerSet.toLong(),
            ParametersKey.MAX_OPTIONS_PER_SELECTOR to maxOptionsPerSelector.toLong(),
            ParametersKey.MAX_MULTI_BIND_VALUES to maxMultiBindValues.toLong(),
            ParametersKey.MAX_BINDS_PER_STATEMENT to maxBindsPerStatement.toLong(),
            ParametersKey.MAX_OPTION_VALUE_CHARS to maxOptionValueChars.toLong(),
            ParametersKey.MAX_OPTION_LABEL_CHARS to maxOptionLabelChars.toLong(),
            ParametersKey.MAX_INPUT_LENGTH to maxInputLength.toLong(),
            ParametersKey.MAX_EXPRESSION_DEPTH to maxExpressionDepth.toLong(),
            ParametersKey.MAX_EXPRESSION_NODES to maxExpressionNodes.toLong(),
            ParametersKey.MAX_REGEX_STEPS to maxRegexSteps,
            ParametersKey.EVALUATE_TIMEOUT_SECONDS to evaluateTimeoutSeconds,
            ParametersKey.SELECTOR_QUERY_TIMEOUT_SECONDS to selectorQueryTimeoutSeconds,
            ParametersKey.MAX_CONCURRENT_SELECTOR_QUERIES to maxConcurrentSelectorQueries.toLong(),
            ParametersKey.MAX_WAITING_SELECTOR_QUERIES to maxWaitingSelectorQueries.toLong(),
            ParametersKey.MAX_EVALUATE_RESPONSE_BYTES to maxEvaluateResponseBytes,
        )
}

/**
 * The Spring binding of `datapipelines.parameters.*` (module-structure §8.3) — the same fields and
 * defaults as [ParametersConfig], which [toConfig] builds (and so bound-checks). Registered by the
 * surfaces' wiring when the engine gains one (lane D); until then the keys are documented, shipped
 * in `application.yml` and bounds-checked at boot by `ConfigValidator`.
 */
@ConfigurationProperties(prefix = ParametersKey.PREFIX)
data class ParametersProperties(
    val maxParametersPerSet: Int = ParametersKey.MAX_PARAMETERS_PER_SET.default.toInt(),
    val maxOptionsPerSelector: Int = ParametersKey.MAX_OPTIONS_PER_SELECTOR.default.toInt(),
    val maxMultiBindValues: Int = ParametersKey.MAX_MULTI_BIND_VALUES.default.toInt(),
    val maxBindsPerStatement: Int = ParametersKey.MAX_BINDS_PER_STATEMENT.default.toInt(),
    val maxOptionValueChars: Int = ParametersKey.MAX_OPTION_VALUE_CHARS.default.toInt(),
    val maxOptionLabelChars: Int = ParametersKey.MAX_OPTION_LABEL_CHARS.default.toInt(),
    val maxInputLength: Int = ParametersKey.MAX_INPUT_LENGTH.default.toInt(),
    val maxExpressionDepth: Int = ParametersKey.MAX_EXPRESSION_DEPTH.default.toInt(),
    val maxExpressionNodes: Int = ParametersKey.MAX_EXPRESSION_NODES.default.toInt(),
    val maxRegexSteps: Long = ParametersKey.MAX_REGEX_STEPS.default,
    val evaluateTimeoutSeconds: Long = ParametersKey.EVALUATE_TIMEOUT_SECONDS.default,
    val selectorQueryTimeoutSeconds: Long = ParametersKey.SELECTOR_QUERY_TIMEOUT_SECONDS.default,
    val maxConcurrentSelectorQueries: Int = ParametersKey.MAX_CONCURRENT_SELECTOR_QUERIES.default.toInt(),
    val maxWaitingSelectorQueries: Int = ParametersKey.MAX_WAITING_SELECTOR_QUERIES.default.toInt(),
    val maxEvaluateResponseBytes: Long = ParametersKey.MAX_EVALUATE_RESPONSE_BYTES.default,
) {
    /** The domain config — constructing it enforces every bound, naming the key. */
    fun toConfig(): ParametersConfig =
        ParametersConfig(
            maxParametersPerSet = maxParametersPerSet,
            maxOptionsPerSelector = maxOptionsPerSelector,
            maxMultiBindValues = maxMultiBindValues,
            maxBindsPerStatement = maxBindsPerStatement,
            maxOptionValueChars = maxOptionValueChars,
            maxOptionLabelChars = maxOptionLabelChars,
            maxInputLength = maxInputLength,
            maxExpressionDepth = maxExpressionDepth,
            maxExpressionNodes = maxExpressionNodes,
            maxRegexSteps = maxRegexSteps,
            evaluateTimeoutSeconds = evaluateTimeoutSeconds,
            selectorQueryTimeoutSeconds = selectorQueryTimeoutSeconds,
            maxConcurrentSelectorQueries = maxConcurrentSelectorQueries,
            maxWaitingSelectorQueries = maxWaitingSelectorQueries,
            maxEvaluateResponseBytes = maxEvaluateResponseBytes,
        )
}
