package co.datapipelines.visualization

import com.fasterxml.jackson.databind.JsonNode

/**
 * The checks every level of a document pre-scan shares (the `ParameterSetReader` Scan, lifted so both readers
 * spell a refusal identically). Every problem is REPORTED with its path and the walk continues — the readers
 * are exhaustive. A refusal names a key, a path, an expected JSON type; the one thing echoed from the body is an
 * enum candidate ([literal]), clipped and sanitised through `safeEcho` — the `ParameterSetReader` mould.
 *
 * [bodyInvalid] is the family's `*.validation.body_invalid`; `details.reason` is one of the `REASON_*`
 * constants below.
 */
@Suppress("TooManyFunctions") // one helper per shape the two schemas share, deliberately flat
internal class JsonScan(
    private val failures: ArtifactFailures,
    private val bodyInvalid: String,
) {
    /** A property that is present and not JSON null (null ≡ absent). */
    fun present(
        node: JsonNode,
        key: String,
    ): JsonNode? = node.get(key)?.takeUnless { it.isNull }

    /** Refuses every key of [node] outside [allowed]. */
    fun unknownKeys(
        node: JsonNode,
        allowed: Set<String>,
        path: String,
    ) {
        node.fieldNames().forEach { key ->
            if (key !in allowed) {
                failures.add(
                    bodyInvalid,
                    join(path, key),
                    "'${key.safeEcho()}' is not a key here; allowed: ${allowed.sorted()}.",
                    mapOf("reason" to REASON_UNKNOWN_KEY, "key" to key.safeEcho(), "allowed" to allowed.sorted()),
                )
            }
        }
    }

    /** The object at [key], or null — reporting it missing (when [required]) or of the wrong JSON type. */
    fun objectAt(
        node: JsonNode,
        key: String,
        path: String,
        required: Boolean,
    ): JsonNode? = shaped(node, key, path, required, "object") { it.isObject }

    /** The array at [key], or null — reporting it missing (when [required]) or of the wrong JSON type. */
    fun arrayAt(
        node: JsonNode,
        key: String,
        path: String,
        required: Boolean,
    ): JsonNode? = shaped(node, key, path, required, "array") { it.isArray }

    /** A required string; [missingCode] when absent (the family's body_invalid unless a field owns a code). */
    fun requiredText(
        node: JsonNode,
        key: String,
        path: String,
        missingCode: String = bodyInvalid,
    ) {
        val value = present(node, key)
        when {
            value == null -> failures.add(missingCode, path, "'$key' is required.", mapOf("reason" to REASON_MISSING))
            !value.isTextual -> wrongType(path, "string", value)
        }
    }

    fun optionalText(
        node: JsonNode,
        key: String,
        path: String,
    ) = optional(node, key, path, "string") { it.isTextual }

    fun optionalInt(
        node: JsonNode,
        key: String,
        path: String,
    ) = optional(node, key, path, "integer", ::isInt)

    fun requiredInt(
        node: JsonNode,
        key: String,
        path: String,
    ) {
        val value = present(node, key)
        when {
            value == null -> missing(path)
            !isInt(value) -> wrongType(path, "integer", value)
        }
    }

    fun optionalBoolean(
        node: JsonNode,
        key: String,
        path: String,
    ) = optional(node, key, path, "boolean") { it.isBoolean }

    /**
     * A catalogued literal at [key]: absent is fine unless [required]; not a string is `wrong_type`; outside
     * [allowed] is [code] with `details.reason` [unknownReason] and the allowed list.
     */
    @Suppress("LongParameterList") // the one helper every enum-valued key goes through
    fun literal(
        node: JsonNode,
        key: String,
        path: String,
        allowed: List<String>,
        code: String = bodyInvalid,
        required: Boolean = false,
        unknownReason: String = REASON_NOT_ALLOWED,
    ) {
        val value = present(node, key)
        when {
            value == null -> {
                if (required) missing(path)
            }

            !value.isTextual -> {
                wrongType(path, "string", value)
            }

            value.asText() !in allowed -> {
                failures.add(
                    code,
                    path,
                    "'${value.asText().safeEcho()}' is not a $key here; allowed: $allowed.",
                    mapOf("reason" to unknownReason, "allowed" to allowed),
                )
            }
        }
    }

    /** Every member of the object at [path] must be a string (a map of names, a binding table). */
    fun stringValues(
        node: JsonNode,
        path: String,
    ) {
        node.properties().forEach { (key, value) -> if (!value.isTextual) wrongType(join(path, key), "string", value) }
    }

    /** Every element of the array at [path] must be a string (a member or target list). */
    fun stringElements(
        node: JsonNode,
        path: String,
    ) {
        node.forEachIndexed { index, value -> if (!value.isTextual) wrongType("$path[$index]", "string", value) }
    }

    /** A `{ "name", "version" }` reference. */
    fun ref(
        node: JsonNode,
        path: String,
    ) {
        if (!node.isObject) {
            wrongType(path, "object", node)
            return
        }
        unknownKeys(node, REF_KEYS, path)
        requiredText(node, "name", "$path.name")
        requiredInt(node, "version", "$path.version")
    }

    /**
     * True when [node] nests at most [max] levels — walked ITERATIVELY, stopping at the first level past [max],
     * so a value built a hundred thousand arrays deep costs one bounded walk and never the host stack (the
     * parameter-set reader's 260 lesson). Over the bound is `too_deep`. The surfaces' request mappers already
     * stop parsing at depth 100 (pipeline-contract §13.21); this is the module's own bound, independent of them.
     */
    fun depthWithin(
        node: JsonNode,
        path: String,
        max: Int,
    ): Boolean {
        val pending = ArrayDeque<Pair<JsonNode, Int>>()
        pending.addLast(node to 1)
        while (pending.isNotEmpty()) {
            val (current, depth) = pending.removeLast()
            if (depth > max) {
                failures.add(
                    bodyInvalid,
                    path,
                    "The value at '$path' nests deeper than $max levels.",
                    mapOf("reason" to REASON_TOO_DEEP, "max" to max),
                )
                return false
            }
            if (current.isContainerNode) current.forEach { pending.addLast(it to depth + 1) }
        }
        return true
    }

    /** `too_many` — a collection over its bound, reported BEFORE its members are walked. */
    fun tooMany(
        path: String,
        count: Int,
        key: VisualizationKey,
        max: Int,
    ) = tooMany(path, count, key.path, max)

    /** [configKey] is the key whose value is [max]: one of this module's, or a ceiling another module's key sets. */
    fun tooMany(
        path: String,
        count: Int,
        configKey: String,
        max: Int,
    ) = failures.add(
        bodyInvalid,
        path,
        "$count entries here; at most $max ($configKey).",
        mapOf("reason" to REASON_TOO_MANY, "count" to count, "max" to max, "config_key" to configKey),
    )

    fun wrongType(
        path: String,
        expected: String,
        actual: JsonNode,
    ) = failures.add(
        bodyInvalid,
        path,
        "Expected a JSON $expected at '${path.ifEmpty { "(document)" }}'; got ${actual.kindName()}.",
        mapOf("reason" to REASON_WRONG_TYPE, "expected" to expected, "actual" to actual.kindName()),
    )

    fun missing(path: String) = failures.add(bodyInvalid, path, "'$path' is required.", mapOf("reason" to REASON_MISSING))

    private inline fun shaped(
        node: JsonNode,
        key: String,
        path: String,
        required: Boolean,
        expected: String,
        accepts: (JsonNode) -> Boolean,
    ): JsonNode? {
        val value = present(node, key)
        return when {
            value == null -> {
                if (required) missing(path)
                null
            }

            !accepts(value) -> {
                wrongType(path, expected, value)
                null
            }

            else -> {
                value
            }
        }
    }

    private inline fun optional(
        node: JsonNode,
        key: String,
        path: String,
        expected: String,
        accepts: (JsonNode) -> Boolean,
    ) {
        val value = present(node, key) ?: return
        if (!accepts(value)) wrongType(path, expected, value)
    }

    companion object {
        const val REASON_UNKNOWN_KEY = "unknown_key"
        const val REASON_WRONG_TYPE = "wrong_type"
        const val REASON_MISSING = "missing"
        const val REASON_NOT_ALLOWED = "not_allowed"
        const val REASON_TOO_MANY = "too_many"
        const val REASON_TOO_LARGE = "too_large"
        const val REASON_TOO_DEEP = "too_deep"

        /**
         * How deep a renderer configuration may nest — a constant, like the request mappers' Jackson
         * constraints (§13.21): a Plotly figure nests about six levels (`layout.xaxis.title.font.size`).
         */
        const val MAX_RAW_DEPTH = 32

        /** How deep a literal parameter value may nest — a scalar, or a list of scalars for a MULTI. */
        const val MAX_LITERAL_DEPTH = 2

        /** A reference's keys (the spec's §4). */
        val REF_KEYS: Set<String> = setOf("name", "version")

        fun isInt(node: JsonNode): Boolean = node.isIntegralNumber && node.canConvertToInt()

        fun join(
            path: String,
            key: String,
        ): String = if (path.isEmpty()) key else "$path.$key"
    }
}
