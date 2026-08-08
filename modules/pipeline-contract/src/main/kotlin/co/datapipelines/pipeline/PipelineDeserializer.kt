package co.datapipelines.pipeline

import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Reads pipeline JSON into the typed model, applying the D1 omitted-`output` default
 * (§17.2 step 1).
 *
 * ## Why there is a pre-scan
 *
 * Six §12 rules are about **wire values that have no typed representation**: a `type` of
 * `"SELECT"`, a `target` of `"kafka"`, a `mode` of `"upsert"`, a parameter `type` of
 * `"NULL"`, an engine of `"DUCKDB"`. Once such a payload is bound, the offending value is
 * either gone or the binding has already thrown — so the validator downstream physically
 * cannot report `type_invalid` or `output_target_invalid`, and the author would get a
 * Jackson stack trace instead of a catalog code.
 *
 * The pre-scan reads those values off the JSON tree first, collects **all** of them (§17.2:
 * exhaustive, not fail-fast), and only binds a document whose enum-valued fields are all in
 * catalog. Every other §12 rule is checked by [PipelineValidator] against the typed model,
 * where it belongs.
 *
 * ## Malformed JSON is not this class's error
 *
 * A syntax error, or a `nodes` that is a string rather than an array, propagates as
 * Jackson's own exception. §13 has no code for "this is not pipeline JSON" — that is a
 * transport-level concern the REST layer answers (rest-api §10.2), and inventing a code here
 * would put a second, drifting catalog in the codebase.
 */
class PipelineDeserializer(
    private val mapper: ObjectMapper = PipelineJson.objectMapper(),
) {
    /**
     * Parses [json] into a [Pipeline], or reports the wire-value failures that stop it being
     * a pipeline at all.
     *
     * The returned model is *syntactically* sound only. Semantic validation (§12) is
     * [PipelineValidator]'s job and runs next (§17.2).
     */
    fun read(json: String): DeserializationOutcome = fromTree(mapper.readTree(json))

    /** As [read], for a document already parsed into a tree. */
    fun fromTree(tree: JsonNode): DeserializationOutcome {
        val failures = WireValueScan(tree).run()
        return if (failures.isEmpty()) {
            DeserializationOutcome.Parsed(mapper.treeToValue(tree, Pipeline::class.java))
        } else {
            DeserializationOutcome.Rejected(ValidationResult(failures))
        }
    }

    /** As [read], but throws [PipelineValidationException] instead of returning a rejection. */
    fun readOrThrow(json: String): Pipeline =
        when (val outcome = read(json)) {
            is DeserializationOutcome.Parsed -> outcome.pipeline
            is DeserializationOutcome.Rejected -> throw PipelineValidationException(outcome.result)
        }
}

/** The result of [PipelineDeserializer.read]. */
sealed interface DeserializationOutcome {
    data class Parsed(
        val pipeline: Pipeline,
    ) : DeserializationOutcome

    data class Rejected(
        val result: ValidationResult,
    ) : DeserializationOutcome
}

/**
 * The wire-value pre-scan described on [PipelineDeserializer].
 *
 * One instance per document; [run] is the whole API. Failures are collected in document
 * order so an editor highlighting them walks the pipeline top to bottom.
 */
private class WireValueScan(
    private val tree: JsonNode,
) {
    private val failures = mutableListOf<ValidationFailure>()

    fun run(): List<ValidationFailure> {
        scanEngine()
        scanParameters()
        scanNodes()
        return failures.toList()
    }

    private fun scanEngine() {
        val engine = tree.path("settings").path("tempdb").path("engine")
        if (engine.isMissingNode || engine.isNull) return
        val wire = engine.asTextOrNull()
        if (StagingEngine.fromWireOrNull(wire) == null) {
            add(
                PipelineErrorCodes.Validation.TEMPDB_ENGINE_UNSUPPORTED,
                "settings.tempdb.engine",
                "Unsupported staging engine '${wire.truncateForError()}'. v1 supports ${StagingEngine.WIRE_VALUES}.",
                mapOf("value" to wire.truncateForError(), "supported" to StagingEngine.WIRE_VALUES),
            )
        }
    }

    private fun scanParameters() {
        val parameters = tree.path("parameters")
        if (!parameters.isObject) return
        parameters.properties().forEach { (name, descriptor) ->
            val wire = descriptor.path("type").asTextOrNull()
            val type = wire?.let { value -> LogicalType.entries.firstOrNull { it.wire == value } }
            if (type == null || type !in Parameter.ALLOWED_TYPES) {
                add(
                    PipelineErrorCodes.Validation.PARAMETER_TYPE_INVALID,
                    "parameters.${name.truncateForError()}.type",
                    "Parameter '${name.truncateForError()}' declares type '${wire.truncateForError()}'; " +
                        "allowed: ${Parameter.ALLOWED_TYPE_WIRE_VALUES}.",
                    mapOf("value" to wire.truncateForError(), "allowed" to Parameter.ALLOWED_TYPE_WIRE_VALUES),
                )
            }
        }
    }

    private fun scanNodes() {
        val nodes = tree.path("nodes")
        if (!nodes.isArray) return
        nodes.forEachIndexed { index, node ->
            scanNodeType(index, node)
            scanNodeOutput(index, node)
        }
    }

    private fun scanNodeType(
        index: Int,
        node: JsonNode,
    ) {
        val wire = node.path("type").asTextOrNull()
        if (NodeType.fromWireOrNull(wire) == null) {
            add(
                PipelineErrorCodes.Validation.TYPE_INVALID,
                "nodes[$index].type",
                "Node type '${wire.truncateForError()}' is not one of ${NodeType.WIRE_VALUES}.",
                mapOf("value" to wire.truncateForError(), "allowed" to NodeType.WIRE_VALUES),
            )
        }
    }

    private fun scanNodeOutput(
        index: Int,
        node: JsonNode,
    ) {
        val output = node.path("output")
        if (output.isMissingNode || output.isNull) return
        if (!output.isObject) {
            add(
                PipelineErrorCodes.Validation.OUTPUT_TARGET_INVALID,
                "nodes[$index].output",
                "The output block must be an object with a 'target' field; got a ${output.nodeType}.",
                mapOf("allowed" to OutputTarget.WIRE_VALUES),
            )
            return
        }
        val targetWire = output.path("target").asTextOrNull()
        val target = OutputTarget.fromWireOrNull(targetWire)
        if (target == null) {
            add(
                PipelineErrorCodes.Validation.OUTPUT_TARGET_INVALID,
                "nodes[$index].output.target",
                "Output target '${targetWire.truncateForError()}' is not one of ${OutputTarget.WIRE_VALUES}.",
                mapOf("value" to targetWire.truncateForError(), "allowed" to OutputTarget.WIRE_VALUES),
            )
            return
        }
        if (target == OutputTarget.DATASOURCE) scanWriteMode(index, output)
    }

    /**
     * §12.4 `output_mode_invalid`, applied to an **absent** mode as well as a misspelled one.
     *
     * §4.7 lists `mode` among the fields a `datasource` target requires and defines no
     * default; §12.4 phrases the check as "when present". Rejecting the absent case is the
     * only reading that fails safe — the alternative is inventing a default, and half of the
     * enum (`replace`) TRUNCATES the target table.
     */
    private fun scanWriteMode(
        index: Int,
        output: JsonNode,
    ) {
        val modeNode = output.path("mode")
        val wire = modeNode.asTextOrNull()
        if (WriteMode.fromWireOrNull(wire) != null) return
        val absent = modeNode.isMissingNode || modeNode.isNull
        add(
            PipelineErrorCodes.Validation.OUTPUT_MODE_INVALID,
            "nodes[$index].output.mode",
            if (absent) {
                "A datasource output requires 'mode'; allowed: ${WriteMode.WIRE_VALUES}."
            } else {
                "Write mode '${wire.truncateForError()}' is not one of ${WriteMode.WIRE_VALUES}."
            },
            mapOf("value" to if (absent) null else wire.truncateForError(), "allowed" to WriteMode.WIRE_VALUES),
        )
    }

    private fun add(
        code: String,
        path: String,
        message: String,
        details: Map<String, Any?>,
    ) {
        failures += validationFailure(code, path, message, details)
    }
}
