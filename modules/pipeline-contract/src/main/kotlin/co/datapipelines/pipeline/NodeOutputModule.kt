package co.datapipelines.pipeline

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.module.SimpleModule

/**
 * Jackson binding for the flat [NodeOutput] hierarchy.
 *
 * Hand-written rather than `@JsonTypeInfo`-driven for two reasons. First, `caller` has **no
 * fields**: Jackson's polymorphic machinery would serialize the `data object` as `{}` plus a
 * type property, and round-tripping a fieldless variant through `@JsonSubTypes` needs more
 * annotation than the eight lines below. Second, the reader must be **lenient about missing
 * fields** — §17.2 requires all §12 failures collected together, so an absent `table` has to
 * survive binding and be reported as `output_table_missing` by the validator instead of
 * aborting the parse with a Jackson exception.
 *
 * Out-of-catalog `target` and `mode` values never reach here: [PipelineDeserializer]'s
 * pre-scan rejects them with `output_target_invalid` / `output_mode_invalid` first.
 */
internal object NodeOutputModule {
    fun create(): SimpleModule =
        SimpleModule("datapipelines-node-output")
            .addSerializer(NodeOutput::class.java, NodeOutputSerializer)
            .addDeserializer(NodeOutput::class.java, NodeOutputDeserializer)
}

private object NodeOutputSerializer : JsonSerializer<NodeOutput>() {
    override fun serialize(
        value: NodeOutput,
        gen: JsonGenerator,
        serializers: SerializerProvider,
    ) {
        gen.writeStartObject()
        gen.writeStringField(TARGET, value.target.wire)
        when (value) {
            is NodeOutput.Tempdb -> {
                gen.writeStringField(TABLE, value.table)
            }

            NodeOutput.Caller -> {
                // The caller target carries no fields beyond the discriminator (§4.7).
            }

            is NodeOutput.Datasource -> {
                gen.writeStringField(DATASOURCE, value.datasource)
                gen.writeStringField(TABLE, value.table)
                gen.writeStringField(MODE, value.mode.wire)
            }
        }
        gen.writeEndObject()
    }
}

private object NodeOutputDeserializer : JsonDeserializer<NodeOutput>() {
    override fun deserialize(
        parser: JsonParser,
        context: DeserializationContext,
    ): NodeOutput {
        val node: JsonNode = parser.readValueAsTree()
        return when (OutputTarget.fromWireOrNull(node.path(TARGET).asTextOrNull())) {
            OutputTarget.TEMPDB -> {
                NodeOutput.Tempdb(table = node.path(TABLE).asTextOrNull().orEmpty())
            }

            OutputTarget.DATASOURCE -> {
                NodeOutput.Datasource(
                    datasource = node.path(DATASOURCE).asTextOrNull().orEmpty(),
                    table = node.path(TABLE).asTextOrNull().orEmpty(),
                    // Unreachable: the pre-scan rejects an absent or unknown mode with
                    // `output_mode_invalid`. APPEND, not REPLACE, is the fallback anyway —
                    // guessing wrong toward REPLACE would TRUNCATE a table (§4.7).
                    mode = WriteMode.fromWireOrNull(node.path(MODE).asTextOrNull()) ?: WriteMode.APPEND,
                )
            }

            // `caller`, and (unreachably) an absent/unknown target the pre-scan already rejected.
            else -> {
                NodeOutput.Caller
            }
        }
    }
}

private const val TARGET = "target"
private const val TABLE = "table"
private const val DATASOURCE = "datasource"
private const val MODE = "mode"

/** The node's text, or null when it is absent, JSON `null`, or not a string. */
internal fun JsonNode.asTextOrNull(): String? = if (isTextual) asText() else null
