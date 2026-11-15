package co.datapipelines.visualization

import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.JsonNode

/**
 * One §4.4 shape object of a chart document (design record 2026-11-14-js-rendered-charts §4.2, O-12): the shape's
 * name, the object, and its JSON Pointer. Its field-bearing [positions] are `fields[i].field` when the object carries a
 * `fields` array (a `value`'s series from columns, G1), else its `field`; a field-less shape (`count`) has none.
 */
data class ChartShapeNode(
    val shape: String,
    val node: JsonNode,
    val pointer: String,
) {
    val positions: List<ChartPosition> =
        node.get("fields")?.takeIf { it.isArray }?.let { fields ->
            fields.mapIndexedNotNull { i, entry -> textField(entry)?.let { ChartPosition(it, "$pointer/fields/$i/field", this) } }
        } ?: listOfNotNull(textField(node)?.let { ChartPosition(it, "$pointer/field", this) })

    private companion object {
        fun textField(node: JsonNode): String? = node.get("field")?.takeIf { it.isTextual }?.asText()
    }
}

/** One field-bearing position: the encoded column, the JSON Pointer of its `field` member, and the shape that holds it. */
data class ChartPosition(
    val field: String,
    val pointer: String,
    val holder: ChartShapeNode,
)

/**
 * The walk over a type's channel descriptor (§4.2), written ONCE for every type: the shape objects of a config, in
 * descriptor order, and from them every field-bearing position with its JSON Pointer. A `oneOf` takes the alternative
 * its object's own `type` selects (the alternatives' type sets are disjoint, R-REG); a `list` walks each element; an
 * `object` walks each named member present; a member a document omits yields nothing. Built over a config the schema
 * accepted: a `mark.type` that names no [ChartType], or a `type` no alternative takes, yields nothing here because the
 * schema has already refused it.
 */
object ChartPositions {
    private const val ENCODING = "/config/encoding"

    /** Every shape object of [config], in descriptor order. */
    fun shapes(config: JsonNode): List<ChartShapeNode> {
        val type = ChartType.fromWire(config.path("mark").path("type").asText()) ?: return emptyList()
        val encoding = config.path("encoding")
        return type.channels.flatMap { channel ->
            encoding.get(channel.key)?.let { walk(channel.node, it, "$ENCODING/${channel.key}") }.orEmpty()
        }
    }

    /** Every field-bearing position of [config], in descriptor order. */
    fun of(config: JsonNode): List<ChartPosition> = shapes(config).flatMap { it.positions }

    private fun walk(
        node: ChannelNode,
        json: JsonNode,
        pointer: String,
    ): List<ChartShapeNode> =
        when (node) {
            is ChannelNode.Shape -> {
                shape(node.name, json, pointer)
            }

            is ChannelNode.OneOf -> {
                val type = json.path("type").asText()
                node.alternatives
                    .firstOrNull { type in it.types }
                    ?.let { shape(it.shape, json, pointer) }
                    .orEmpty()
            }

            is ChannelNode.ListOf -> {
                if (json.isArray) json.flatMapIndexed { i, element -> walk(node.of, element, "$pointer/$i") } else emptyList()
            }

            is ChannelNode.ObjectOf -> {
                node.members.flatMap { (key, member) -> json.get(key)?.let { walk(member, it, "$pointer/$key") }.orEmpty() }
            }
        }

    private fun shape(
        name: String,
        json: JsonNode,
        pointer: String,
    ): List<ChartShapeNode> = if (json.isObject) listOf(ChartShapeNode(name, json, pointer)) else emptyList()
}

/** The column-type sets the bar rules read (record §4.1). */
object ChartTypes {
    val QUANTITATIVE: Set<LogicalType> = setOf(LogicalType.INTEGER, LogicalType.BIGINTEGER, LogicalType.DECIMAL, LogicalType.BIGDECIMAL)

    /** Exact (string-wired) numbers: aggregated and ordered by exact decimal arithmetic. */
    val EXACT: Set<LogicalType> = setOf(LogicalType.BIGINTEGER, LogicalType.BIGDECIMAL)
    val TEMPORAL: Set<LogicalType> = setOf(LogicalType.DATE, LogicalType.TIMESTAMP)
}
