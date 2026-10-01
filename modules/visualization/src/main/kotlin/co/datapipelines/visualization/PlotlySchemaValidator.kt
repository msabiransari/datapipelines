package co.datapipelines.visualization

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * The DEEP Plotly config validation (the spec's §11.3 step 1, D4's exception for the renderer's own
 * schema): every trace against the reduced schema's attribute tree for its type — unknown attributes,
 * wrong types and unsupported traces refused with the path — and `layout`/`config` against theirs. The
 * house checks (a non-empty data array of supported trace types, the object shape) stay in force; this
 * adds the schema walk the save-time validator and the mechanical test now share, so save, release and
 * runtime refuse the same configurations with the same paths.
 *
 * The walk is bounded twice: a configuration is ≤ `max-config-bytes` (256 KiB) before it gets here, and
 * the walker carries its own node budget ([MAX_NODES]) plus a depth cap — a pathological document is
 * refused `too_complex`, never spun on. Value checks follow the attribute's `valType` exactly
 * ([ValueChecks]); an `arrayOk` attribute accepts the scalar form or an array of them; compound
 * (`role: object` + `items`) and `info_array` attributes validate element-wise; numbered subplot keys
 * (`xaxis2`, `scene3`, …) normalize onto their family's tree.
 */
class PlotlySchemaValidator(
    private val schema: PlotlySchemaHandle = PlotlySchemaHandle.DEFAULT,
) : RendererConfigValidator {
    /** The schema handle — a seam so tests can pin a small hand-built tree beside the real one. */
    class PlotlySchemaHandle(
        private val traces: (String) -> JsonNode?,
        val layout: () -> JsonNode,
        val config: () -> JsonNode,
    ) {
        fun trace(type: String): JsonNode? = traces(type)

        companion object {
            /** The committed reduced schema. */
            val DEFAULT = PlotlySchemaHandle({ PlotlySchema.traceAttributes(it) }, { PlotlySchema.layoutAttributes() }, { PlotlySchema.configAttributes() })
        }
    }

    override fun validate(
        kind: RendererKind,
        config: ObjectNode,
    ): List<ConfigProblem> {
        if (kind != RendererKind.PLOTLY) return emptyList() // the house schemas own table and kpi
        val problems = mutableListOf<ConfigProblem>()
        val data = config.get("data")
        when {
            data == null || !data.isArray || data.isEmpty -> problems += ConfigProblem("data", "traces_missing", "A Plotly config needs a non-empty data array of traces.")

            data.size() > RendererConfigValidators.MAX_TRACES -> problems += ConfigProblem("data", "too_many_traces", "At most ${RendererConfigValidators.MAX_TRACES} traces.")

            else -> data.forEachIndexed { index, trace -> traceNode(trace, "data[$index]", Walker(), problems) }
        }
        listOf("layout" to schema.layout(), "config" to schema.config()).forEach { (key, attributes) ->
            val node = config.get(key) ?: return@forEach
            if (!node.isObject) {
                problems += ConfigProblem(key, "wrong_type", "'$key' must be an object.")
            } else {
                objectNode(node, attributes, key, Walker(), problems)
            }
        }
        return problems
    }

    /** The walk's budget: nodes left. A spent budget is ONE refusal, never a truncated pass. */
    private class Walker(
        var budget: Int = MAX_NODES,
        var depth: Int = 0,
    ) {
        var exhausted = false
        var reported = false

        fun spend(): Boolean {
            if (exhausted) return false
            budget -= 1
            if (budget < 0) exhausted = true
            return !exhausted
        }

        /** The one too_complex report — whoever exhausts the budget reports it, and only once. */
        fun reportOnce(
            path: String,
            problems: MutableList<ConfigProblem>,
        ) {
            if (exhausted && !reported) {
                reported = true
                problems += ConfigProblem(path, "too_complex", "The configuration exceeds the validation walk's bounds.")
            }
        }
    }

    private fun traceNode(
        trace: JsonNode,
        path: String,
        walker: Walker,
        problems: MutableList<ConfigProblem>,
    ) {
        walker.spend()
        if (walker.exhausted) {
            walker.reportOnce(path, problems)
            return
        }
        if (!trace.isObject) {
            problems += ConfigProblem(path, "wrong_type", "A trace is an object.")
            return
        }
        val type = trace.get("type")
        val typeText = type?.takeIf { it.isTextual }?.asText()
        when {
            type == null || !type.isTextual -> {
                problems += ConfigProblem("$path.type", "trace_type_missing", "A trace names its type.")
                return
            }

            typeText !in RendererConfigValidators.PLOTLY_TRACES -> {
                problems +=
                    ConfigProblem("$path.type", "trace_type_unsupported", "Trace types are ${RendererConfigValidators.PLOTLY_TRACES} (the vendored bundles).")
                return
            }
        }
        val attributes = schema.trace(checkNotNull(typeText))
        if (attributes == null) {
            problems += ConfigProblem("$path.type", "trace_type_unsupported", "The reduced schema carries no '$typeText' tree.")
            return
        }
        objectNode(trace, attributes, path, walker, problems, skipKey = "type")
    }

    /** Every key of [node] must be an attribute of [attributes]; values are judged per their valType. */
    private fun objectNode(
        node: JsonNode,
        attributes: JsonNode,
        path: String,
        walker: Walker,
        problems: MutableList<ConfigProblem>,
        skipKey: String? = null,
    ) {
        node.properties().forEach { (key, value) ->
            if (key == skipKey) return@forEach
            if (walker.exhausted) return@forEach
            walker.spend()
            if (walker.depth > MAX_DEPTH) return@forEach
            val attribute = attributes.get(key) ?: normalizeSubplot(key)?.let { attributes.get(it) }
            if (attribute == null || !attribute.isObject) {
                problems +=
                    ConfigProblem(
                        "$path.$key",
                        "unknown_attribute",
                        "'${key.safeEcho()}' is not an attribute here.",
                    )
                return@forEach
            }
            value(attribute, value, "$path.$key", walker, problems)
        }
        walker.reportOnce(path, problems)
    }

    private fun value(
        attribute: JsonNode,
        node: JsonNode,
        path: String,
        walker: Walker,
        problems: MutableList<ConfigProblem>,
    ) {
        // A binding placeholder (the spec's §3.1: the stored configuration carries "$.x" where the render
        // substitutes the bound column's values) is the substitution grammar, judged for BINDING coverage
        // by the mechanical test's step 2, which holds the bindings map — not by the schema walk.
        if (node.isTextual && PLACEHOLDER.matches(node.asText())) return
        if (attribute.path("arrayOk").asBoolean(false) && node.isArray) {
            node.forEachIndexed { index, element -> value(attributeWithoutArrayOk(attribute), element, "$path[$index]", walker, problems) }
            return
        }
        val items = attribute.get("items")
        when (val valType = attribute.path("valType").asText("")) {
            "" -> {
                // An object attribute (role: object) or a compound array (role: object + items).
                if (items != null && node.isArray && items.isObject) {
                    val singular = items.fieldNames().asSequence().firstOrNull()
                    val element = singular?.let { items.path(it) }
                    if (element == null) {
                        problems += ConfigProblem(path, "wrong_type", "The schema's items carry no element tree.")
                    } else {
                        node.forEachIndexed { index, item ->
                            walker.depth += 1
                            objectNode(item, element, "$path[$index]", walker, problems)
                            walker.depth -= 1
                        }
                    }
                } else if (node.isObject) {
                    walker.depth += 1
                    objectNode(node, attribute, path, walker, problems)
                    walker.depth -= 1
                } else {
                    problems += ConfigProblem(path, "wrong_type", "This attribute is an object.")
                }
            }

            "info_array" -> {
                if (!node.isArray) {
                    problems += ConfigProblem(path, "wrong_type", "This attribute is an array.")
                } else {
                    node.forEachIndexed { index, element ->
                        walker.depth += 1
                        when {
                            // Fixed-length form: one attribute per position (layout.xaxis.range's two).
                            items != null && items.isArray ->
                                items.get(minOf(index, items.size() - 1))?.let { value(it, element, "$path[$index]", walker, problems) }

                            // Uniform form: one attribute for every element.
                            items != null && items.isObject && items.has("valType") ->
                                value(items, element, "$path[$index]", walker, problems)

                            // Named form: each element is judged against the named element tree.
                            items != null && items.isObject && node.isObject -> {
                                val singular = items.fieldNames().asSequence().firstOrNull()
                                val tree = singular?.let { items.get(it) }
                                if (tree != null) {
                                    objectNode(element, tree, "$path[$index]", walker, problems)
                                }
                            }

                            else -> problems += ConfigProblem(path, "schema_shape", "The schema's info_array carries no items tree.")
                        }
                        walker.depth -= 1
                    }
                }
            }

            else -> ValueChecks.check(valType, attribute, node, path, problems)
        }
    }

    private fun attributeWithoutArrayOk(attribute: JsonNode): JsonNode {
        val copy = attribute.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        copy.remove("arrayOk")
        return copy
    }

    /** `xaxis2` → `xaxis`, `scene3` → `scene`, … — numbered subplot instances share their family's tree. */
    private fun normalizeSubplot(key: String): String? =
        SUBPLOT_FAMILIES.firstOrNull { family ->
            key == family || (key.startsWith(family) && key.removePrefix(family).all { it.isDigit() } && key.removePrefix(family).isNotEmpty())
        }

    private companion object {
        const val MAX_NODES = 20_000
        const val MAX_DEPTH = 32

        /** The binding placeholder's shape: a `$.`-rooted dotted path (the spec's §3.1 worked example). */
        val PLACEHOLDER = Regex("""^\$\.[A-Za-z0-9_.\[\]]+$""")

        val SUBPLOT_FAMILIES = listOf("xaxis", "yaxis", "scene", "polar", "ternary", "map", "smith", "geo")
    }
}

/** The valType → JSON shape rules, from the schema's own vocabulary. */
private object ValueChecks {
    fun check(
        valType: String,
        attribute: JsonNode,
        node: JsonNode,
        path: String,
        problems: MutableList<ConfigProblem>,
    ) {
        val ok =
            when (valType) {
                "number", "integer" -> node.isNumber && (valType == "number" || node.isIntegralNumber)
                "boolean" -> node.isBoolean
                "data_array" -> node.isArray
                "string", "angle", "subplotid" -> node.isTextual
                "color" -> node.isTextual || node.isNumber
                "colorlist" -> node.isTextual || (node.isArray && node.all { it.isTextual || it.isNumber })
                "colorscale" -> node.isTextual || (node.isArray && node.all { it.isArray })
                "enumerated" -> node.isTextual && (allowed(attribute)?.let { values -> node.asText() in values } ?: true)
                "flaglist" ->
                    node.isTextual && (allowed(attribute)?.let { values -> node.asText().split(Regex("\\s+")).all { it in values } } ?: true)
                "any" -> true
                else -> true // a valType this lane's schema carries but round one does not judge tighter
            }
        if (!ok) {
            problems += ConfigProblem(path, "wrong_type", "The value does not satisfy '$valType'.")
        }
    }

    private fun allowed(attribute: JsonNode): List<String>? {
        val values = attribute.get("values") ?: return null
        return values.map { it.asText() }
    }
}
