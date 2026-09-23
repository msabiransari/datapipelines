package co.datapipelines.scripting

import com.dashjoin.jsonata.Jsonata

/**
 * Internal normalisation between this module's JSON-shaped contract and the library's
 * value model. The input crosses as Java [Map]/[List]/scalars; the output is walked
 * back into the same shape with [String] keys — which is also where a lazily
 * represented sequence (the library's `RangeList` for a range expression like
 * `1 to 10000000`) is materialised, an allocation the breach suite measures and the
 * docs account for.
 *
 * Numbers keep the library's own fitting (`Utils.convertNumber` → int/long/double):
 * the type gate (7b/7c reuse) decides fit, the engine does not round.
 */
internal object JsonataValues {
    /** Recursively normalises the caller's input into the shape the library expects. */
    fun toEngineInput(value: Any?): Any? =
        when (value) {
            null, is String, is Boolean, is Number -> {
                value
            }

            is Map<*, *> -> {
                val out = LinkedHashMap<String, Any?>(value.size)
                for ((k, v) in value) out[keyString(k)] = toEngineInput(v)
                out
            }

            is List<*> -> {
                value.map { toEngineInput(it) }
            }

            else -> {
                throw ScriptEvaluationException(
                    "input value of type ${value.javaClass.name} is not JSON-shaped " +
                        "(Map/List/String/Number/Boolean/null expected)",
                )
            }
        }

    /** Recursively normalises the library's result into the module's JSON shape. */
    fun fromEngineOutput(value: Any?): Any? =
        when (value) {
            null, is String, is Boolean, is Number -> {
                value
            }

            is Map<*, *> -> {
                val out = LinkedHashMap<String, Any?>(value.size)
                for ((k, v) in value) out[keyString(k)] = fromEngineOutput(v)
                out
            }

            is List<*> -> {
                val out = ArrayList<Any?>(value.size)
                for (item in value) out.add(fromEngineOutput(item))
                out
            }

            else -> {
                throw ScriptEvaluationException(
                    "engine returned a non-JSON value of type ${value.javaClass.name}",
                )
            }
        }

    private fun keyString(key: Any?): String =
        when (key) {
            is String -> key

            else -> throw ScriptEvaluationException(
                "map key must be a JSON string, was ${key?.javaClass?.name ?: "null"}",
            )
        }
}
