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

/**
 * The field ids of `D/matrix/fields-bar.csv` a document SETS (the reference `setFields`) — D-A's input: each set field
 * the server library classes `degraded`/`cannot` yields exactly one warning or refusal. A title counts as set only when
 * it carries text (`null` hides it, which every library honours); a rule counts when the document can trigger it.
 */
object BarFieldSet {
    private val ALWAYS =
        listOf(
            "mark.type",
            "orientation",
            "category.field",
            "category.type",
            "category.sort",
            "value.aggregate",
            "rule.null-measure",
            "rule.null-category",
            "rule.no-data",
            "rule.text-as-text",
            "rule.tooltip-content",
            "rule.long-labels",
        )

    @Suppress("CyclomaticComplexMethod") // one line per field id of the matrix, as the reference reads them
    fun of(
        body: JsonNode,
        columns: List<InputColumn>,
    ): List<String> {
        val config = body.path("config")
        val ch = BarChannels.of(config)
        val set = ALWAYS.toMutableSet()
        val value = ch.value.encoding
        val band = ch.band.encoding
        if (ch.value.field != null) set += "value.field"
        ch.color?.let { color ->
            set += listOf("color.field", "color.sort")
            val stack = value.get("stack")?.takeUnless { it.isNull }?.asText()
            set += "value.stack=${stack ?: "null"}"
            if (stack != null) set += "rule.stack-sign"
            if (color.encoding.get("title")?.isTextual == true) set += "color.title"
            val legend = color.encoding.get("legend")
            when {
                legend != null && legend.isNull -> set += "color.legend=null"
                legend?.has("orient") == true -> set += "color.legend.orient"
            }
        }
        if (band.path("type").asText() == "temporal") set += "rule.utc"
        val measure = columns.firstOrNull { it.name == ch.value.field }
        if (measure != null && measure.type in ChartTypes.EXACT) set += "rule.exact-big-numbers"
        if (body.get("description")?.isTextual == true) set += "description"
        config.get("title")?.takeIf { it.isObject }?.let { title ->
            set += "title.text"
            if (title.get("subtitle")?.isTextual == true) set += "title.subtitle"
        }
        if (band.get("title")?.isTextual == true) set += "category.title"
        if (value.get("title")?.isTextual == true) set += "value.title"
        if (band.get("axis")?.has("grid") == true) set += "category.axis.grid"
        if (value.get("axis")?.has("grid") == true) set += "value.axis.grid"
        if (band.has("format")) set += "category.format"
        if (value.has("format")) set += "value.format"
        if (value.has("scale")) set += "value.scale.domain"
        if (config.path("mark").path("labels").asText() == "values") set += "mark.labels"
        return set.sorted()
    }

    /** The JSON Pointer a field id is authored at — where a D-A warning or refusal points; `/config` for the rules. */
    @Suppress("CyclomaticComplexMethod") // one flat lookup: a branch per field id of the D-A table (record §4.3)
    fun pointer(
        field: String,
        ch: BarChannels,
    ): String =
        when (field) {
            "description" -> {
                "/description"
            }

            "title.text" -> {
                "/config/title/text"
            }

            "title.subtitle" -> {
                "/config/title/subtitle"
            }

            "mark.type" -> {
                "/config/mark/type"
            }

            "mark.labels" -> {
                "/config/mark/labels"
            }

            "category.field", "category.type", "category.sort", "category.title", "category.format" -> {
                "${ch.band.pointer}/${field.substringAfter('.')}"
            }

            "category.axis.grid" -> {
                "${ch.band.pointer}/axis/grid"
            }

            "value.field", "value.aggregate", "value.title", "value.format" -> {
                "${ch.value.pointer}/${field.substringAfter('.')}"
            }

            "value.axis.grid" -> {
                "${ch.value.pointer}/axis/grid"
            }

            "value.scale.domain" -> {
                "${ch.value.pointer}/scale/domain"
            }

            "value.stack=zero", "value.stack=normalize", "value.stack=null" -> {
                "${ch.value.pointer}/stack"
            }

            "color.field", "color.sort", "color.title" -> {
                "/config/encoding/color/${field.substringAfter('.')}"
            }

            "color.legend.orient" -> {
                "/config/encoding/color/legend/orient"
            }

            "color.legend=null" -> {
                "/config/encoding/color/legend"
            }

            else -> {
                "/config"
            }
        }
}
