package co.datapipelines.scripting

/**
 * The JavaScript isolate's sizing (transform-nodes design §4.4) — plain values; the app binds them
 * from `datapipelines.script.js.*` (lane 8b). Each per-evaluation limit here is the CEILING an
 * [EvaluationLimits] field may lower but never raise, or the value used when the caller sets none.
 *
 * @property isolateMemoryBytes `engine.MaxIsolateMemory`: the isolate's own heap, a hard cap (the
 *   sandbox refuses an Engine without one under UNTRUSTED; polyglot's floor is 32 MB). The isolate
 *   lives OUTSIDE the JVM heap, so it is sized by process RSS, not `-Xmx`.
 * @property maxHeapBytes `sandbox.MaxHeapMemory` per Context, unless [EvaluationLimits.maxHeapBytes]
 *   is smaller. Soft: the sandbox measures retained heap, so an allocation can overshoot before the
 *   Context is cancelled; [isolateMemoryBytes] is the hard bound behind it.
 * @property maxStatements `sandbox.MaxStatements` per Context, unless [EvaluationLimits.maxStatements]
 *   is smaller. The engine's own prelude is an internal source and is not counted.
 * @property maxAstDepth `sandbox.MaxASTDepth`: the parse-time nesting ceiling, at compile AND for any
 *   source the body parses at runtime (`eval`, `new Function`).
 * @property maxStackFrames `sandbox.MaxStackFrames` ceiling over [EvaluationLimits.maxDepth]. A hard
 *   bound only below the engine's native JavaScript stack (~1 000–2 000 frames, measured); past
 *   that the body sees a catchable `RangeError` first.
 * @property maxOutputBytes `sandbox.MaxOutputStreamSize` and `sandbox.MaxErrorStreamSize`: the
 *   console stream. Past it the Context is cancelled ([ScriptResourceLimitException.Kind.OUTPUT]).
 * @property maxResultBytes the serialized result's budget unless [EvaluationLimits.maxResultBytes]
 *   is smaller ([ScriptResourceLimitException.Kind.RESULT_BYTES]).
 * @property maxInputBytes the serialized input's budget unless [EvaluationLimits.maxInputBytes] is
 *   smaller ([ScriptResourceLimitException.Kind.INPUT_BYTES], refused before the sandbox exists).
 * @property ecmascriptVersion `js.ecmascript-version`: pinned, so a GraalJS upgrade cannot change
 *   the language a stored body is written in.
 */
data class JavaScriptRuntimeOptions(
    val isolateMemoryBytes: Long = DEFAULT_ISOLATE_MEMORY_BYTES,
    val maxHeapBytes: Long = DEFAULT_MAX_HEAP_BYTES,
    val maxStatements: Long = DEFAULT_MAX_STATEMENTS,
    val maxAstDepth: Int = DEFAULT_MAX_AST_DEPTH,
    val maxStackFrames: Int = DEFAULT_MAX_STACK_FRAMES,
    val maxOutputBytes: Long = DEFAULT_MAX_OUTPUT_BYTES,
    val maxResultBytes: Long = DEFAULT_MAX_RESULT_BYTES,
    val maxInputBytes: Long = DEFAULT_MAX_INPUT_BYTES,
    val ecmascriptVersion: String = DEFAULT_ECMASCRIPT_VERSION,
) {
    init {
        require(isolateMemoryBytes > 0) { "isolateMemoryBytes must be positive, was $isolateMemoryBytes" }
        require(maxHeapBytes in 1..isolateMemoryBytes) {
            "maxHeapBytes must be positive and at most isolateMemoryBytes ($isolateMemoryBytes), was $maxHeapBytes"
        }
        require(maxStatements > 0) { "maxStatements must be positive, was $maxStatements" }
        require(maxAstDepth > 0) { "maxAstDepth must be positive, was $maxAstDepth" }
        require(maxStackFrames > 0) { "maxStackFrames must be positive, was $maxStackFrames" }
        require(maxOutputBytes > 0) { "maxOutputBytes must be positive, was $maxOutputBytes" }
        require(maxResultBytes > 0) { "maxResultBytes must be positive, was $maxResultBytes" }
        require(maxInputBytes > 0) { "maxInputBytes must be positive, was $maxInputBytes" }
        require(ecmascriptVersion.isNotBlank()) { "ecmascriptVersion must not be blank" }
    }

    companion object {
        private const val MIB = 1024L * 1024L

        /** See [isolateMemoryBytes]. */
        const val DEFAULT_ISOLATE_MEMORY_BYTES = 512 * MIB

        /** See [maxHeapBytes]. */
        const val DEFAULT_MAX_HEAP_BYTES = 256 * MIB

        /** See [maxStatements]. */
        const val DEFAULT_MAX_STATEMENTS = 100_000_000L

        /** See [maxAstDepth]. */
        const val DEFAULT_MAX_AST_DEPTH = 1000

        /** See [maxStackFrames]. */
        const val DEFAULT_MAX_STACK_FRAMES = 1000

        /** See [maxOutputBytes]. */
        const val DEFAULT_MAX_OUTPUT_BYTES = 1 * MIB

        /** See [maxResultBytes]. */
        const val DEFAULT_MAX_RESULT_BYTES = 64 * MIB

        /** See [maxInputBytes]. */
        const val DEFAULT_MAX_INPUT_BYTES = 64 * MIB

        /** See [ecmascriptVersion]. */
        const val DEFAULT_ECMASCRIPT_VERSION = "2025"
    }
}
