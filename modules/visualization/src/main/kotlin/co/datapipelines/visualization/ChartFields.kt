package co.datapipelines.visualization

import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.JsonNode

/**
 * The bar family's encoding read as roles (design record §4.3, the 854 reference `bar-fields.mjs`): orientation is
 * DERIVED — the quantitative channel carries the value, the other one the band (category); `color` is the series.
 * Built only over a config the schema accepted, so every required member is present.
 */
class BarChannels private constructor(
    /** True when `y` carries the value (vertical bars). */
    val vertical: Boolean,
    val band: Channel,
    val value: Channel,
    val color: Channel?,
) {
    /** One encoding channel: its key under `config.encoding` and its object. */
    class Channel(
        val key: String,
        val encoding: JsonNode,
    ) {
        /** The JSON Pointer of the channel object. */
        val pointer: String get() = "/config/encoding/$key"

        /** The encoded column, or null (`count` has none). */
        val field: String? get() = encoding.get("field")?.takeIf { it.isTextual }?.asText()
    }

    companion object {
        fun of(config: JsonNode): BarChannels {
            val encoding = config.path("encoding")
            val vertical = encoding.path("y").path("type").asText() == "quantitative"
            val x = Channel("x", encoding.path("x"))
            val y = Channel("y", encoding.path("y"))
            val color = encoding.get("color")?.takeIf { it.isObject }?.let { Channel("color", it) }
            return if (vertical) BarChannels(true, x, y, color) else BarChannels(false, y, x, color)
        }
    }
}

/** The column-type sets the bar rules read (record §4.1). */
object ChartTypes {
    val QUANTITATIVE: Set<LogicalType> = setOf(LogicalType.INTEGER, LogicalType.BIGINTEGER, LogicalType.DECIMAL, LogicalType.BIGDECIMAL)

    /** Exact (string-wired) numbers: aggregated and ordered by exact decimal arithmetic. */
    val EXACT: Set<LogicalType> = setOf(LogicalType.BIGINTEGER, LogicalType.BIGDECIMAL)
    val TEMPORAL: Set<LogicalType> = setOf(LogicalType.DATE, LogicalType.TIMESTAMP)
}
