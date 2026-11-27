package co.datapipelines.visualization

/**
 * The closed list of the reasons a chart save refuses a document (`details.reason`, record §4.4): one member per
 * `chart.<family>.<name>` text [ChartSaveRules] can give. A refusal names a member, never a text of its own, so a code
 * exists in exactly one place. `ChartReasonCodesSpecDriftTest` holds this list equal to the reason table of
 * `docs/dashboards.md` and to the manual's table, both directions. The schema layer's reason `schema` is not a member:
 * it is the other producer of a chart save's `details.reason` and has its own row.
 *
 * Members are sorted by [wire] (byte order) and a member's name is its wire without `chart.`, upper-cased, dots as
 * underscores; the drift test holds both.
 */
enum class ChartReason(
    val wire: String,
) {
    BANDS_NOT_ASCENDING("chart.bands.not_ascending"),
    BANDS_OUTSIDE_DOMAIN("chart.bands.outside_domain"),
    BINDINGS_MISMATCH("chart.bindings.mismatch"),
    CATEGORY_NULLABLE("chart.category.nullable"),
    CATEGORY_TYPE_MISMATCH("chart.category.type_mismatch"),
    COLOR_NULLABLE("chart.color.nullable"),
    FIELD_BINARY("chart.field.binary"),
    FIELD_NULL_TYPE("chart.field.null_type"),
    FIELD_UNKNOWN("chart.field.unknown"),
    FORMAT_CURRENCY_UNKNOWN("chart.format.currency_unknown"),
    FORMAT_NORMALIZE_PERCENT("chart.format.normalize_percent"),
    INPUTS_CARDINALITY("chart.inputs.cardinality"),
    MEASURE_NOT_NUMERIC("chart.measure.not_numeric"),
    SCALE_DOMAIN_INVALID("chart.scale.domain_invalid"),
    SCALE_DOMAIN_LOG("chart.scale.domain_log"),
    SCALE_DOMAIN_NORMALIZE("chart.scale.domain_normalize"),
    SCALE_LOG_UNSUPPORTED("chart.scale.log_unsupported"),
    SHADE_MID_INVALID("chart.shade.mid_invalid"),
    SIZE_RANGE_INVALID("chart.size.range_invalid"),
    TARGET_OUTSIDE_DOMAIN("chart.target.outside_domain"),
    TRANSFORM_OUTPUT_UNKNOWN("chart.transform.output_unknown"),
    ;

    companion object {
        val WIRE_VALUES: List<String> = entries.map { it.wire }

        fun fromWire(value: String): ChartReason? = entries.firstOrNull { it.wire == value }
    }
}
