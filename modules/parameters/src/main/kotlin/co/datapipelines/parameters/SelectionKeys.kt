package co.datapipelines.parameters

import co.datapipelines.typesystem.DatapipelinesException
import com.fasterxml.jackson.databind.JsonNode

/**
 * The selections' key rule (record §5.1): every key names a parameter of the set, or the whole request is
 * refused `parameter.evaluate.unknown_parameter` (400) before any work. ONE judge for both callers — the
 * evaluator's own step and the observed route, which applies it before its stream opens (#375 D4: a stream
 * never opens for a refused request) — so the two refusals cannot drift apart.
 */
object SelectionKeys {
    /** At most this many unknown keys are echoed in `details.unknown`; `details.count` says how many there were. */
    private const val MAX_ECHOED_KEYS = 20

    /** @throws DatapipelinesException `parameter.evaluate.unknown_parameter` when [selections] names a key [body] lacks. */
    fun refuseUnknown(
        body: ParameterSetBody,
        selections: Map<String, JsonNode?>,
    ) {
        val names = body.parameters.mapTo(HashSet()) { it.name }
        val unknown = selections.keys.filter { it !in names }
        if (unknown.isEmpty()) return
        throw DatapipelinesException(
            code = ParameterErrorCodes.EVALUATE_UNKNOWN_PARAMETER,
            message = "selections names ${unknown.size} key(s) that are no parameter of this set — send exactly the set's parameters.",
            details = mapOf("unknown" to unknown.take(MAX_ECHOED_KEYS).map { it.safeEcho() }, "count" to unknown.size),
        )
    }
}
