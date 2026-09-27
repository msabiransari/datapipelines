package co.datapipelines.parameters

import co.datapipelines.typesystem.ParameterCardinality
import co.datapipelines.typesystem.ParameterWireEncoder
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.UUID

/** Where a parameter's value came from (record P26) — `state.origin`. */
enum class ValueOrigin(
    val wire: String,
) {
    /** Priority 1: the client's selection, valid against the recomputed options (an `INPUT`: against its rules). */
    CLIENT("client"),

    /** Priority 2: the configured default (`default_value`, else the `is_default` option). */
    DEFAULT("default"),

    /** Priority 3: the first option in the order the source returned. */
    FIRST("first"),

    /** An `INPUT`'s database-fed initial value — its source's one row (record §6.2a). */
    SOURCE("source"),

    /** Nothing resolved: the value is `null` (and binds `NULL` for the children). */
    NONE("none"),
}

/** One option as evaluate returns it (record P6): the canonical [value], its presentation text, the default mark. */
data class EvaluatedOption(
    val value: Any,
    val displayValue: String,
    val isDefault: Boolean,
)

/** One refusal on one parameter, in the house envelope's shape (rest-api §4): `code`, `message`, `details`. */
data class ParameterError(
    val code: String,
    val message: String,
    val details: Map<String, Any?> = emptyMap(),
)

/**
 * A parameter's evaluated state (record §5.3) — [value] canonical (a `List` for a `MULTI`, `null`
 * when unresolved), [computedDefault] what priorities 2-then-3 give right now (an `INPUT`: its
 * sourced row, else `default_value`), [reset] true when a client value was dropped by the walk,
 * [options] `null` for an `INPUT`.
 */
data class ParameterState(
    val value: Any?,
    val origin: ValueOrigin,
    val computedDefault: Any?,
    val reset: Boolean,
    val hidden: Boolean,
    val disabled: Boolean,
    val options: List<EvaluatedOption>?,
    val errors: List<ParameterError>,
)

/** One parameter of the response: its stored definition, the parameters that depend on it (transitively), its state. */
data class EvaluatedParameter(
    val definition: ParameterDefinition,
    val dependents: List<String>,
    val state: ParameterState,
)

/** The deployment's currency, echoed once per response for a renderer's `currency` format (record §3.7). */
data class OrgEcho(
    val currencySymbol: String?,
    val currencyName: String?,
)

/**
 * The evaluate response (record §5.3): the whole set, re-rendered — every parameter's definition,
 * its dependents and its state, in display order. [values] is the CONSUMER payload (P4): one
 * canonical value per parameter, hidden and disabled included; [valid] is false while any parameter
 * carries an error, and a consumer refuses then (and validates the outgoing values again, P28).
 */
data class EvaluateResponse(
    val id: UUID,
    val name: String,
    val version: Int,
    val org: OrgEcho,
    val parameters: List<EvaluatedParameter>,
) {
    val valid: Boolean get() = parameters.all { it.state.errors.isEmpty() }

    val values: Map<String, Any?> get() = parameters.associateTo(LinkedHashMap()) { it.definition.name to it.state.value }
}

/**
 * The §5.3 JSON — the one writer the response travels through, so the byte cap
 * (`max-evaluate-response-bytes`) measures exactly what a caller receives.
 *
 * - Every value is wire-encoded for its parameter's type (type-system §3.1 via
 *   [ParameterWireEncoder] — `BIGINTEGER`/`BIGDECIMAL` as strings, a `MULTI` as an array).
 * - The definition is echoed as stored (the strict mapper), with `presentation.control` filled with
 *   the derived default when the author set none (§3.7 — a renderer always receives a control) and a
 *   `constants` source NOT repeated: options are echoed ONCE, in `state.options`.
 */
object EvaluateResponseJson {
    private val nodes = JsonNodeFactory.instance

    fun write(response: EvaluateResponse): ObjectNode =
        nodes.objectNode().apply {
            put("id", response.id.toString())
            put("name", response.name)
            put("version", response.version)
            put("valid", response.valid)
            putObject("org").apply {
                put("currency_symbol", response.org.currencySymbol)
                put("currency_name", response.org.currencyName)
            }
            val values = putObject("values")
            response.parameters.forEach { values.set<JsonNode>(it.definition.name, encode(it.definition, it.state.value)) }
            val parameters = putArray("parameters")
            response.parameters.forEach { parameters.add(parameter(it)) }
        }

    fun bytes(response: EvaluateResponse): ByteArray = ParameterSetJson.mapper.writeValueAsBytes(write(response))

    /** A value of [definition]'s type as the wire carries it — an array for a `MULTI`, JSON null when unresolved. */
    fun encode(
        definition: ParameterDefinition,
        value: Any?,
    ): JsonNode =
        when {
            value == null -> {
                NullNode.instance
            }

            definition.cardinality == ParameterCardinality.MULTI -> {
                nodes.arrayNode().apply { (value as List<*>).forEach { add(ParameterWireEncoder.encode(definition.type, it)) } }
            }

            else -> {
                ParameterWireEncoder.encode(definition.type, value)
            }
        }

    private fun parameter(evaluated: EvaluatedParameter): ObjectNode {
        val definition = evaluated.definition
        val node = definitionEcho(definition)
        node.putArray("dependents").apply { evaluated.dependents.forEach { add(it) } }
        val state = evaluated.state
        node.putObject("state").apply {
            set<JsonNode>("value", encode(definition, state.value))
            put("origin", state.origin.wire)
            set<JsonNode>("computed_default", encode(definition, state.computedDefault))
            put("reset", state.reset)
            put("hidden", state.hidden)
            put("disabled", state.disabled)
            set<JsonNode>("options", state.options?.let { options(definition, it) } ?: NullNode.instance)
            set<JsonNode>("errors", errors(state.errors))
        }
        return node
    }

    private fun options(
        definition: ParameterDefinition,
        options: List<EvaluatedOption>,
    ): ArrayNode =
        nodes.arrayNode().apply {
            options.forEach { option ->
                addObject()
                    .set<ObjectNode>("value", ParameterWireEncoder.encode(definition.type, option.value))
                    .put("display_value", option.displayValue)
                    .put("is_default", option.isDefault)
            }
        }

    private fun errors(errors: List<ParameterError>): ArrayNode =
        nodes.arrayNode().apply {
            errors.forEach { error ->
                addObject()
                    .put("code", error.code)
                    .put("message", error.message)
                    .set<ObjectNode>("details", ParameterSetJson.mapper.valueToTree(error.details))
            }
        }

    private fun definitionEcho(definition: ParameterDefinition): ObjectNode {
        val node: ObjectNode = ParameterSetJson.mapper.valueToTree(definition)
        val presentation = (node.get("presentation") as? ObjectNode) ?: node.putObject("presentation")
        if (!presentation.has("control")) {
            PresentationCatalogue.derivedControl(definition)?.let { presentation.put("control", it.wire) }
        }
        (node.get("source") as? ObjectNode)?.let { source ->
            source.remove("constants")
            if (source.isEmpty) node.remove("source")
        }
        return node
    }
}
