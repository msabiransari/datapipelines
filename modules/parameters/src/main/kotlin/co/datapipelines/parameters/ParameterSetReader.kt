package co.datapipelines.parameters

import co.datapipelines.pipeline.ValidationResult
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.typesystem.ParameterCardinality
import co.datapipelines.typesystem.ParameterConstraints
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * The ONE way a parameter-set document gets in from JSON (record §3; §4 step 1's "JSON shape").
 *
 * ## Why a pre-scan, and what it refuses
 *
 * A document Jackson cannot bind is either silently thinned (an unknown key dropped) or a stack
 * trace (a wrong JSON type), and neither is a catalogued code. So the tree is walked FIRST, every
 * level, and every problem that would stop it binding is reported — all of them, with paths:
 *
 * - an **unknown key**, a **wrong JSON type**, a **missing structural key** or an over-long
 *   `depends_on` — `parameter.validation.body_invalid` (the owner's ruling of 2026-09-26),
 *   `details.reason` `unknown_key` / `wrong_type` / `missing` / `too_long`;
 * - a missing field that HAS its own code — the set's `name` (`name_invalid`), its `display_name`
 *   and a parameter's `label` (`label_invalid`), a parameter's `name` / `type` / `kind`
 *   (`name_invalid` / `type_invalid` / `kind_invalid`), an option's `value` / `display_value`
 *   (`option_invalid`);
 * - an out-of-catalogue enum value — `type_invalid`, `kind_invalid`, `cardinality_invalid`,
 *   `presentation_invalid` (an unknown control), `format_invalid` (an unknown numeric format);
 * - the two collection caps, BEFORE their members are walked, so a document cannot make this pass
 *   work in proportion to its own size: `too_many_parameters`, `too_many_options`.
 *
 * Everything else — a blank label, an unknown dependency, a cycle, a bad pin — is the validator's,
 * on the bound model. Inside an expression the grammar is §7's and its catch-all is
 * `expression_invalid` (the parser's, lane B's A.2), so the pre-scan only asks that an expression
 * be an object.
 *
 * A JSON `null` is ABSENT at every level (the record has no key where null means something else),
 * so nulls are removed before binding and the Kotlin defaults apply.
 */
class ParameterSetReader(
    private val config: ParametersConfig = ParametersConfig(),
) {
    /** Reads [tree] into a [ParameterSetDocument], or reports every shape problem at once. */
    fun read(tree: JsonNode): ParameterSetReadOutcome {
        val failures = ParameterSetFailures()
        Scan(config, failures).set(tree)
        if (!failures.isEmpty) return ParameterSetReadOutcome.Refused(failures.toResult())
        val stripped = withoutNulls(tree) as ObjectNode
        val name = stripped.remove("name").asText()
        return try {
            ParameterSetReadOutcome.Read(
                ParameterSetDocument(name, ParameterSetJson.mapper.treeToValue(stripped, ParameterSetBody::class.java)),
            )
        } catch (e: JsonProcessingException) {
            // The pre-scan's key and type tables are the binding's own; reaching here means they
            // drifted apart. Still a refusal the author can read, never a 500.
            failures.add(
                ParameterErrorCodes.BODY_INVALID,
                e.pathReference(),
                "The document does not bind: ${e.originalMessage.safeEcho(MAX_REFLECTED_PATH_LENGTH)}",
                mapOf("reason" to REASON_WRONG_TYPE),
            )
            ParameterSetReadOutcome.Refused(failures.toResult())
        }
    }

    /** [read] that throws [ParameterSetValidationException] instead of returning a refusal. */
    fun readOrThrow(tree: JsonNode): ParameterSetDocument =
        when (val outcome = read(tree)) {
            is ParameterSetReadOutcome.Read -> outcome.document
            is ParameterSetReadOutcome.Refused -> throw ParameterSetValidationException(outcome.result)
        }

    companion object {
        const val REASON_UNKNOWN_KEY = "unknown_key"
        const val REASON_WRONG_TYPE = "wrong_type"
        const val REASON_MISSING = "missing"
        const val REASON_TOO_LONG = "too_long"

        /** The keys of each level — the binding's own properties, pinned equal by `ParameterSetReaderTest`. */
        val SET_KEYS: Set<String> = setOf("name", "display_name", "description", "parameters")
        val PARAMETER_KEYS: Set<String> =
            setOf(
                "name",
                "label",
                "description",
                "type",
                "precision",
                "scale",
                "kind",
                "cardinality",
                "required",
                "default_value",
                "source",
                "depends_on",
                "hidden_expression",
                "disabled_expression",
                "constraints",
                "presentation",
            )
        val SOURCE_KEYS: Set<String> = setOf("constants", "template", "datasource")
        val OPTION_KEYS: Set<String> = setOf("value", "display_value", "is_default")
        val TEMPLATE_REF_KEYS: Set<String> = setOf("id", "version")
        val PRESENTATION_KEYS: Set<String> = setOf("control", "format")
        val FORMAT_KEYS: Set<String> = setOf("kind", "pattern")

        /** Record §3.2: the ten declarable logical types — `NULL` is a column fact, never a declaration. */
        val DECLARABLE_TYPES: List<String> = LogicalType.entries.filter { it != LogicalType.NULL }.map { it.wire }

        /**
         * A copy of [document] with every JSON-null property removed at the SCHEMA's levels (null ≡
         * absent) — the set, each parameter, its source and options and pin, its constraints and its
         * presentation. A fixed number of levels, never a walk of the document's own depth: raw values
         * (`default_value`, an option's `value`, a bound, an expression) are copied as they are, so a
         * deeply nested value costs this nothing and cannot make the host stack the bound (the 260
         * lesson). The pre-scan has already checked every level's shape.
         */
        internal fun withoutNulls(document: JsonNode): JsonNode {
            val set = strip(document)
            (set.get("parameters") as? ArrayNode)?.let { parameters ->
                val copies = parameters.map { parameter -> strip(parameter).also(::stripParameterChildren) }
                set.set<JsonNode>("parameters", ParameterSetJson.mapper.createArrayNode().addAll(copies))
            }
            return set
        }

        private fun stripParameterChildren(parameter: ObjectNode) {
            (parameter.get("source") as? ObjectNode)?.let { source ->
                val copy = strip(source)
                (copy.get("template") as? ObjectNode)?.let { copy.set<JsonNode>("template", strip(it)) }
                (copy.get("constants") as? ArrayNode)?.let { options ->
                    copy.set<JsonNode>("constants", ParameterSetJson.mapper.createArrayNode().addAll(options.map { strip(it) }))
                }
                parameter.set<JsonNode>("source", copy)
            }
            (parameter.get("constraints") as? ObjectNode)?.let { parameter.set<JsonNode>("constraints", strip(it)) }
            (parameter.get("presentation") as? ObjectNode)?.let { presentation ->
                val copy = strip(presentation)
                (copy.get("format") as? ObjectNode)?.let { copy.set<JsonNode>("format", strip(it)) }
                parameter.set<JsonNode>("presentation", copy)
            }
        }

        /** One level: the object's non-null properties, values shared (not deep-copied). */
        private fun strip(node: JsonNode): ObjectNode {
            val copy = ParameterSetJson.mapper.createObjectNode()
            node.properties().forEach { (key, value) -> if (!value.isNull) copy.set<JsonNode>(key, value) }
            return copy
        }

        /** The binding's reference chain in the pre-scan's own spelling: `parameters[0].source.constants[1]`. */
        private fun JsonProcessingException.pathReference(): String =
            (this as? com.fasterxml.jackson.databind.JsonMappingException)
                ?.path
                .orEmpty()
                .fold("") { path, ref ->
                    val field = ref.fieldName
                    when {
                        field == null -> "$path[${ref.index}]"
                        path.isEmpty() -> field
                        else -> "$path.$field"
                    }
                }
    }
}

/** The result of [ParameterSetReader.read]. */
sealed interface ParameterSetReadOutcome {
    data class Read(
        val document: ParameterSetDocument,
    ) : ParameterSetReadOutcome

    data class Refused(
        val result: ValidationResult,
    ) : ParameterSetReadOutcome
}

/** One walk over one document; [set] is the whole API. Failures are collected in document order. */
@Suppress("TooManyFunctions") // one function per level of the record's §3 tree, deliberately flat
private class Scan(
    private val config: ParametersConfig,
    private val failures: ParameterSetFailures,
) {
    fun set(tree: JsonNode) {
        if (!tree.isObject) {
            wrongType("", "object", tree)
            return
        }
        unknownKeys(tree, ParameterSetReader.SET_KEYS, "")
        requiredText(tree, "name", "name", ParameterErrorCodes.NAME_INVALID, "The set has no name.")
        requiredText(tree, "display_name", "display_name", ParameterErrorCodes.LABEL_INVALID, "The set has no display_name.")
        optionalText(tree, "description", "description")
        val parameters = tree.get("parameters")
        when {
            parameters == null || parameters.isNull -> missing("parameters", "The set has no parameters array.")
            !parameters.isArray -> wrongType("parameters", "array", parameters)
            parameters.size() > config.maxParametersPerSet -> tooManyParameters(parameters.size())
            else -> parameters.forEachIndexed { index, parameter -> parameter(parameter, "parameters[$index]") }
        }
    }

    private fun parameter(
        node: JsonNode,
        path: String,
    ) {
        if (!node.isObject) {
            wrongType(path, "object", node)
            return
        }
        unknownKeys(node, ParameterSetReader.PARAMETER_KEYS, path)
        requiredText(node, "name", "$path.name", ParameterErrorCodes.NAME_INVALID, "A parameter has no name.")
        requiredText(node, "label", "$path.label", ParameterErrorCodes.LABEL_INVALID, "A parameter has no label.")
        optionalText(node, "description", "$path.description")
        enumValue(node, "type", "$path.type", ParameterSetReader.DECLARABLE_TYPES, ParameterErrorCodes.TYPE_INVALID, required = true)
        enumValue(node, "kind", "$path.kind", ParameterKind.WIRE_VALUES, ParameterErrorCodes.KIND_INVALID, required = true)
        enumValue(node, "cardinality", "$path.cardinality", ParameterCardinality.WIRE_VALUES, ParameterErrorCodes.CARDINALITY_INVALID)
        optionalInt(node, "precision", "$path.precision")
        optionalInt(node, "scale", "$path.scale")
        optionalOfType(node, "required", "$path.required", "boolean") { it.isBoolean }
        dependsOn(node.get("depends_on"), "$path.depends_on")
        optionalOfType(node, "hidden_expression", "$path.hidden_expression", "object") { it.isObject }
        optionalOfType(node, "disabled_expression", "$path.disabled_expression", "object") { it.isObject }
        present(node, "source")?.let { source(it, "$path.source") }
        present(node, "constraints")?.let { constraints(it, "$path.constraints") }
        present(node, "presentation")?.let { presentation(it, "$path.presentation") }
    }

    private fun dependsOn(
        node: JsonNode?,
        path: String,
    ) {
        if (node == null || node.isNull) return
        if (!node.isArray) {
            wrongType(path, "array", node)
            return
        }
        // A list longer than the set can hold names something twice or something absent; refusing it
        // whole bounds the validator's work by the set, not by the document.
        if (node.size() > config.maxParametersPerSet) {
            failures.add(
                ParameterErrorCodes.BODY_INVALID,
                path,
                "depends_on lists ${node.size()} entries; a set holds at most ${config.maxParametersPerSet} parameters.",
                mapOf("reason" to ParameterSetReader.REASON_TOO_LONG, "max" to config.maxParametersPerSet),
            )
            return
        }
        node.forEachIndexed { index, entry -> if (!entry.isTextual) wrongType("$path[$index]", "string", entry) }
    }

    private fun source(
        node: JsonNode,
        path: String,
    ) {
        if (!node.isObject) {
            wrongType(path, "object", node)
            return
        }
        unknownKeys(node, ParameterSetReader.SOURCE_KEYS, path)
        optionalText(node, "datasource", "$path.datasource")
        present(node, "template")?.let { templateRef(it, "$path.template") }
        val constants = present(node, "constants") ?: return
        when {
            !constants.isArray -> {
                wrongType("$path.constants", "array", constants)
            }

            constants.size() > config.maxOptionsPerSelector -> {
                failures.add(
                    ParameterErrorCodes.TOO_MANY_OPTIONS,
                    "$path.constants",
                    "The constants list holds ${constants.size()} options; at most ${config.maxOptionsPerSelector} " +
                        "(datapipelines.parameters.max-options-per-selector).",
                    mapOf("count" to constants.size(), "max" to config.maxOptionsPerSelector),
                )
            }

            else -> {
                constants.forEachIndexed { index, option -> option(option, "$path.constants[$index]") }
            }
        }
    }

    private fun option(
        node: JsonNode,
        path: String,
    ) {
        if (!node.isObject) {
            wrongType(path, "object", node)
            return
        }
        unknownKeys(node, ParameterSetReader.OPTION_KEYS, path)
        if (present(node, "value") == null) {
            failures.add(
                ParameterErrorCodes.OPTION_INVALID,
                "$path.value",
                "An option needs a non-null value — a deliberate \"nothing\" is a sentinel option such as \"ALL\".",
                mapOf("reason" to "null_value"),
            )
        }
        val label = present(node, "display_value")
        when {
            label == null -> {
                failures.add(
                    ParameterErrorCodes.OPTION_INVALID,
                    "$path.display_value",
                    "An option needs a display_value.",
                    mapOf("reason" to "empty_label"),
                )
            }

            !label.isTextual -> {
                wrongType("$path.display_value", "string", label)
            }
        }
        optionalOfType(node, "is_default", "$path.is_default", "boolean") { it.isBoolean }
    }

    private fun templateRef(
        node: JsonNode,
        path: String,
    ) {
        if (!node.isObject) {
            wrongType(path, "object", node)
            return
        }
        unknownKeys(node, ParameterSetReader.TEMPLATE_REF_KEYS, path)
        val id = present(node, "id")
        when {
            id == null -> missing("$path.id", "A template pin needs its id.")
            !id.isTextual -> wrongType("$path.id", "string", id)
        }
        val version = present(node, "version")
        when {
            version == null -> missing("$path.version", "A template pin needs its version.")
            !isInt(version) -> wrongType("$path.version", "integer", version)
        }
    }

    private fun constraints(
        node: JsonNode,
        path: String,
    ) {
        if (!node.isObject) {
            wrongType(path, "object", node)
            return
        }
        unknownKeys(node, ParameterConstraints.KEYS, path)
        ParameterConstraints.LENGTH_KEYS.forEach { key -> optionalInt(node, key, "$path.$key") }
        optionalText(node, "pattern", "$path.pattern")
    }

    private fun presentation(
        node: JsonNode,
        path: String,
    ) {
        if (!node.isObject) {
            wrongType(path, "object", node)
            return
        }
        unknownKeys(node, ParameterSetReader.PRESENTATION_KEYS, path)
        enumValue(node, "control", "$path.control", PresentationControl.WIRE_VALUES, ParameterErrorCodes.PRESENTATION_INVALID)
        val format = present(node, "format") ?: return
        if (!format.isObject) {
            wrongType("$path.format", "object", format)
            return
        }
        unknownKeys(format, ParameterSetReader.FORMAT_KEYS, "$path.format")
        enumValue(format, "kind", "$path.format.kind", NumericFormatKind.WIRE_VALUES, ParameterErrorCodes.FORMAT_INVALID)
        optionalText(format, "pattern", "$path.format.pattern")
    }

    // ---- the checks every level shares --------------------------------------------------------------

    private fun unknownKeys(
        node: JsonNode,
        allowed: Set<String>,
        path: String,
    ) {
        node.fieldNames().forEach { key ->
            if (key !in allowed) {
                failures.add(
                    ParameterErrorCodes.BODY_INVALID,
                    join(path, key),
                    "'${key.safeEcho()}' is not a key here; allowed: ${allowed.sorted()}.",
                    mapOf("reason" to ParameterSetReader.REASON_UNKNOWN_KEY, "key" to key.safeEcho(), "allowed" to allowed.sorted()),
                )
            }
        }
    }

    private fun requiredText(
        node: JsonNode,
        key: String,
        path: String,
        missingCode: String,
        missingMessage: String,
    ) {
        val value = present(node, key)
        when {
            value == null -> failures.add(missingCode, path, missingMessage, mapOf("reason" to ParameterSetReader.REASON_MISSING))
            !value.isTextual -> wrongType(path, "string", value)
        }
    }

    private fun optionalText(
        node: JsonNode,
        key: String,
        path: String,
    ) = optionalOfType(node, key, path, "string") { it.isTextual }

    private fun optionalInt(
        node: JsonNode,
        key: String,
        path: String,
    ) = optionalOfType(node, key, path, "integer", ::isInt)

    private inline fun optionalOfType(
        node: JsonNode,
        key: String,
        path: String,
        expected: String,
        accepts: (JsonNode) -> Boolean,
    ) {
        val value = present(node, key) ?: return
        if (!accepts(value)) wrongType(path, expected, value)
    }

    /** A catalogued enum value: absent is fine unless [required]; out of [allowed] is [code]. */
    @Suppress("LongParameterList") // the one helper every enum-valued key goes through
    private fun enumValue(
        node: JsonNode,
        key: String,
        path: String,
        allowed: List<String>,
        code: String,
        required: Boolean = false,
    ) {
        val value = present(node, key)
        when {
            value == null -> {
                if (required) {
                    failures.add(
                        code,
                        path,
                        "'$key' is required; allowed: $allowed.",
                        mapOf(
                            "reason" to ParameterSetReader.REASON_MISSING,
                            "allowed" to allowed,
                        ),
                    )
                }
            }

            !value.isTextual -> {
                wrongType(path, "string", value)
            }

            value.asText() !in allowed -> {
                failures.add(
                    code,
                    path,
                    "'${value.asText().safeEcho()}' is not a $key; allowed: $allowed.",
                    mapOf("value" to value.asText().safeEcho(), "allowed" to allowed),
                )
            }
        }
    }

    private fun wrongType(
        path: String,
        expected: String,
        actual: JsonNode,
    ) = failures.add(
        ParameterErrorCodes.BODY_INVALID,
        path,
        "Expected a JSON $expected at '${path.ifEmpty { "(document)" }}'; got ${actual.nodeType.name.lowercase()}.",
        mapOf("reason" to ParameterSetReader.REASON_WRONG_TYPE, "expected" to expected, "actual" to actual.nodeType.name.lowercase()),
    )

    private fun missing(
        path: String,
        message: String,
    ) = failures.add(ParameterErrorCodes.BODY_INVALID, path, message, mapOf("reason" to ParameterSetReader.REASON_MISSING))

    private fun tooManyParameters(count: Int) =
        failures.add(
            ParameterErrorCodes.TOO_MANY_PARAMETERS,
            "parameters",
            "The set declares $count parameters; at most ${config.maxParametersPerSet} (datapipelines.parameters.max-parameters-per-set).",
            mapOf("count" to count, "max" to config.maxParametersPerSet),
        )

    private companion object {
        /** A property that is present and not JSON null (null ≡ absent). */
        fun present(
            node: JsonNode,
            key: String,
        ): JsonNode? = node.get(key)?.takeUnless { it.isNull }

        fun isInt(node: JsonNode): Boolean = node.isIntegralNumber && node.canConvertToInt()

        fun join(
            path: String,
            key: String,
        ): String = if (path.isEmpty()) key else "$path.$key"
    }
}
