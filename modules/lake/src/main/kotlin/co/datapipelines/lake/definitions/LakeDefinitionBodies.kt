package co.datapipelines.lake.definitions

import co.datapipelines.lake.definitions.LakeDefinitionErrors.invalidBody
import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import org.apache.iceberg.PartitionSpec
import org.apache.iceberg.Schema

/**
 * The definition body's wire form (lake.md §1): a strict parse (unknown keys, duplicate keys, wrong JSON types and
 * an over-cap body are refused, each as `lake.definition.invalid_body` with `details.path`), the canonical JSON the
 * repository stores, and the reverse mapping from an Iceberg schema. Every parsed body passes [LakeDefinitionRules]
 * before it is returned, so an invalid body never reaches `body_hash`.
 */
@Suppress("ThrowsCount") // each wrong shape is its own named refusal (lake.md §1)
object LakeDefinitionBodies {
    /** The body cap, in UTF-8 bytes of the text the caller sent. */
    const val MAX_BODY_BYTES = 262_144

    private val ROOT_KEYS = setOf("fields", "partition", "identifier_field_ids")
    private val FIELD_KEYS = setOf("id", "name", "type", "precision", "scale", "required", "doc", "tags")
    private val PARTITION_KEYS = setOf("field_id", "transform")

    private val mapper =
        JsonMapper
            .builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build()

    /** Parses and validates [json]; [base] is the body the new version builds on (null for a table's first). */
    fun parse(
        json: String,
        base: LakeDefinitionBody?,
    ): LakeDefinitionBody {
        if (json.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) {
            throw invalidBody("$", "too_large", "the body is over $MAX_BODY_BYTES bytes")
        }
        val root =
            try {
                mapper.readTree(json)
            } catch (e: JacksonException) {
                throw invalidBody("$", "malformed_json", e.originalMessage ?: "not JSON", e)
            } ?: throw invalidBody("$", "malformed_json", "empty body")
        val body = fromTree(root)
        LakeDefinitionRules.validate(body, base)
        return body
    }

    /**
     * Reads a STORED body back. Every stored body passed [parse] before it reached the row, so the rules are not run
     * again here (a later version's base would make them answer differently); the shape checks of [parse] still are.
     */
    fun read(json: String): LakeDefinitionBody = fromTree(mapper.readTree(json))

    /** The canonical JSON of [body]: fixed key order, optional keys omitted when absent. */
    fun toJson(body: LakeDefinitionBody): String {
        val f = JsonNodeFactory.instance
        val root = f.objectNode()
        val fields = root.putArray("fields")
        body.fields.forEach { field -> putField(fields.addObject(), field) }
        if (body.partition.isNotEmpty()) {
            val partition = root.putArray("partition")
            body.partition.forEach { p -> partition.addObject().put("field_id", p.fieldId).put("transform", p.transform) }
        }
        if (body.identifierFieldIds.isNotEmpty()) {
            root.putArray("identifier_field_ids").apply { body.identifierFieldIds.forEach { add(it) } }
        }
        return mapper.writeValueAsString(root)
    }

    private fun putField(
        node: ObjectNode,
        field: LakeFieldDefinition,
    ) {
        node.put("id", field.id)
        node.put("name", field.name)
        node.put("type", field.type.wire)
        field.precision?.let { node.put("precision", it) }
        field.scale?.let { node.put("scale", it) }
        node.put("required", field.required)
        field.doc?.let { node.put("doc", it) }
        if (field.tags.isNotEmpty()) {
            val tags = node.putArray("tags")
            field.tags.forEach { tags.add(it) }
        }
    }

    /**
     * The body an Iceberg [schema] and [spec] spell (the round trip of lake.md §1): field ids, names, types, required,
     * docs, the partition fields and the identifier fields. Tags are DP's own and absent from Iceberg, so they come
     * back empty. A type outside the closed list is refused with `unsupported_type`.
     */
    fun fromIceberg(
        schema: Schema,
        spec: PartitionSpec,
    ): LakeDefinitionBody {
        val fields =
            schema.columns().mapIndexed { index, column ->
                val (type, precision, scale) =
                    LakeTypes.logicalOf(column.type())
                        ?: throw invalidBody("fields[$index].type", "unsupported_type", "Iceberg type ${column.type()} has no DP type")
                LakeFieldDefinition(column.fieldId(), column.name(), type, precision, scale, column.isRequired, column.doc())
            }
        val partition = spec.fields().map { LakePartitionField(it.sourceId(), it.transform().toString()) }
        return LakeDefinitionBody(fields, partition, schema.identifierFieldIds().sorted())
    }

    private fun fromTree(root: JsonNode): LakeDefinitionBody {
        val obj = root as? ObjectNode ?: throw invalidBody("$", "not_object", "the body must be a JSON object")
        unknownKey(obj, ROOT_KEYS, "")?.let { throw invalidBody(it, "unknown_key", "unknown key") }
        val fieldsNode = obj.get("fields") ?: throw invalidBody("fields", "missing", "fields is required")
        if (!fieldsNode.isArray) throw invalidBody("fields", "wrong_type", "fields must be an array")
        val fields = fieldsNode.mapIndexed { i, node -> field(node, "fields[$i]") }
        val partition =
            array(obj, "partition").mapIndexed { i, node ->
                val path = "partition[$i]"
                val p = node as? ObjectNode ?: throw invalidBody(path, "wrong_type", "a partition field is an object")
                unknownKey(p, PARTITION_KEYS, path)?.let { throw invalidBody(it, "unknown_key", "unknown key") }
                LakePartitionField(requiredInt(p, "field_id", path), requiredString(p, "transform", path))
            }
        val identifiers =
            array(obj, "identifier_field_ids").mapIndexed { i, node ->
                if (!node.isIntegralNumber || !node.canConvertToInt()) {
                    throw invalidBody("identifier_field_ids[$i]", "wrong_type", "an identifier field id is an integer")
                }
                node.intValue()
            }
        return LakeDefinitionBody(fields, partition, identifiers)
    }

    private fun field(
        node: JsonNode,
        path: String,
    ): LakeFieldDefinition {
        val obj = node as? ObjectNode ?: throw invalidBody(path, "wrong_type", "a field is an object")
        unknownKey(obj, FIELD_KEYS, path)?.let { throw invalidBody(it, "unknown_key", "unknown key") }
        val typeWire = requiredString(obj, "type", path)
        val type =
            LogicalType.entries.firstOrNull { it.wire == typeWire && it != LogicalType.NULL }
                ?: throw invalidBody("$path.type", "unknown_type", "'$typeWire' is not in the closed type list")
        val requiredNode = obj.get("required")
        if (requiredNode != null && !requiredNode.isBoolean) throw invalidBody("$path.required", "wrong_type", "required is a boolean")
        val tags =
            array(obj, "tags", path).mapIndexed { i, tag ->
                if (!tag.isTextual) throw invalidBody("$path.tags[$i]", "wrong_type", "a tag is a string")
                tag.textValue()
            }
        return LakeFieldDefinition(
            id = requiredInt(obj, "id", path),
            name = requiredString(obj, "name", path),
            type = type,
            precision = int(obj, "precision", path, required = false),
            scale = int(obj, "scale", path, required = false),
            required = requiredNode?.booleanValue() ?: false,
            doc = string(obj, "doc", path, required = false)?.takeUnless { it.isEmpty() },
            tags = tags,
        )
    }

    private fun unknownKey(
        obj: ObjectNode,
        allowed: Set<String>,
        path: String,
    ): String? =
        obj
            .fieldNames()
            .asSequence()
            .firstOrNull { it !in allowed }
            ?.let { if (path.isEmpty()) it else "$path.$it" }

    private fun array(
        obj: ObjectNode,
        key: String,
        path: String = "",
    ): List<JsonNode> {
        val node = obj.get(key) ?: return emptyList()
        if (node.isNull) return emptyList()
        val at = if (path.isEmpty()) key else "$path.$key"
        if (!node.isArray) throw invalidBody(at, "wrong_type", "$key must be an array")
        return node.toList()
    }

    private fun int(
        obj: ObjectNode,
        key: String,
        path: String,
        required: Boolean,
    ): Int? {
        val node = obj.get(key)
        if (node == null || node.isNull) {
            if (required) throw invalidBody("$path.$key", "missing", "$key is required")
            return null
        }
        if (!node.isIntegralNumber || !node.canConvertToInt()) {
            throw invalidBody("$path.$key", "wrong_type", "$key is an integer")
        }
        return node.intValue()
    }

    private fun requiredInt(
        obj: ObjectNode,
        key: String,
        path: String,
    ): Int = int(obj, key, path, required = true) ?: throw invalidBody("$path.$key", "missing", "$key is required")

    private fun requiredString(
        obj: ObjectNode,
        key: String,
        path: String,
    ): String = string(obj, key, path, required = true) ?: throw invalidBody("$path.$key", "missing", "$key is required")

    private fun string(
        obj: ObjectNode,
        key: String,
        path: String,
        required: Boolean,
    ): String? {
        val node = obj.get(key)
        if (node == null || node.isNull) {
            if (required) throw invalidBody("$path.$key", "missing", "$key is required")
            return null
        }
        if (!node.isTextual) throw invalidBody("$path.$key", "wrong_type", "$key is a string")
        return node.textValue()
    }
}
