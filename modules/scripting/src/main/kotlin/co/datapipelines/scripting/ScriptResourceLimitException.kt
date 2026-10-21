package co.datapipelines.scripting

/**
 * A declared resource limit other than time was hit (transform-nodes design §7:
 * `pipeline.script.resource_limit`, detail names which).
 *
 * For the JSONata engine today this is [Kind.DEPTH] — the engine's own recursion
 * bound, counted at every evaluate entry and exit: nested expressions and non-tail
 * lambda recursion alike (#260; the library's `Timebox` skipped `isParallelCall`
 * frames, so a set-level shape leaked a depth unit per item until the engine counted
 * for itself). A tail-recursive lambda loop is trampolined by the library — depth
 * stays flat and the wall clock bounds that shape instead. The same kind is ALSO the
 * compile-time nesting ceiling's refusal (#314): a body past the ceiling is refused
 * before the library's recursive parser can overflow the stack, and any residual
 * stack overflow at the compile/evaluate boundary becomes this refusal rather than
 * an Error escaping the seam.
 * [Kind.HEAP] and [Kind.STATEMENTS] name limits the JSONata engine cannot enforce
 * in-process (`EngineCapabilities`); the JavaScript isolate enforces both (#8). The
 * JavaScript engine adds three: [Kind.OUTPUT] (its console stream's size cap),
 * [Kind.INPUT_BYTES] (the serialized input, refused before the sandbox exists) and
 * [Kind.RESULT_BYTES] (the serialized result, measured on the way out).
 */
class ScriptResourceLimitException(
    val kind: Kind,
    detail: String,
) : ScriptingException(
        CODE,
        "resource limit hit ($kind): $detail",
        mapOf("kind" to kind.name),
    ) {
    /** Which declared limit fired. */
    enum class Kind { DEPTH, HEAP, STATEMENTS, OUTPUT, INPUT_BYTES, RESULT_BYTES }

    companion object {
        /** pipeline-contract §13.18 — the resource-limit row (record §7). */
        const val CODE = "pipeline.script.resource_limit"
    }
}
