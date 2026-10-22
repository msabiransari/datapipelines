package co.datapipelines.scripting

import java.time.Duration
import java.time.Instant

/**
 * The resource bounds one evaluation is granted (transform-nodes design §4.1).
 *
 * Every field states an INTENT. Whether the engine can actually enforce it is a fact
 * about the engine, reported by [ScriptEngine.capabilities] — the caller documents what
 * a limit means there rather than guessing (§4.5). For the JSONata engine the honest
 * table is: wall clock is bounded **between expression steps** (a single builtin call
 * runs to completion first, so the evaluation pool's abandonment is the outer bound),
 * depth is enforced at every step, and heap / statements are NOT bounded in-process —
 * input and output caps at the callers bound a well-formed evaluation instead.
 *
 * @param wallClock wall-clock budget for one `evaluate` call; the evaluation pool adds
 *   its abandonment grace on top before declaring the evaluation abandoned.
 * @param maxDepth maximum expression/recursion depth — nested expressions and
 *   non-tail lambda recursion included (a tail-recursive call is trampolined by the
 *   library and never nests; the wall clock bounds that shape). The JSONata engine
 *   enforces it with its own evaluate entry/exit hooks (checked at every entry and
 *   exit — #260; the library's `Timebox` skipped `isParallelCall` frames).
 * @param maxHeapBytes heap budget in bytes, or null when the caller declares none (the engine's
 *   own default then applies). The JSONata engine cannot enforce it in-process
 *   (`EngineCapabilities.boundsHeap` is false); the JavaScript isolate can, softly — the sandbox
 *   checks retained heap, so an allocation may overshoot before the Context is cancelled.
 * @param maxStatements statement budget, or null when the caller declares none (the engine's own
 *   default then applies). Enforced by the JavaScript isolate (`boundsStatements`); the JSONata
 *   engine has no statement count.
 * @param now the instant `$now()` and `$millis()` return, or null. A script is a pure
 *   function of its inputs (D-T4), so production callers always pin this to the
 *   execution's `current_timestamp` — a template is reproducible because the clock is an
 *   input, not an ambient read. When null, the engine refuses any body that calls the
 *   clock functions (the refusal fires when the body actually evaluates them).
 * @param maxInputBytes the serialized input's budget in UTF-8 bytes, or null for the engine's own
 *   default. The JavaScript engine refuses a larger input BEFORE its sandbox exists
 *   ([ScriptResourceLimitException.Kind.INPUT_BYTES]); the JSONata engine passes its input by
 *   reference and ignores the field.
 * @param maxResultBytes the serialized result's budget in UTF-8 bytes, or null for the engine's own
 *   default ([ScriptResourceLimitException.Kind.RESULT_BYTES]). Not the executor's cap on a VALUE
 *   output, which is a different, smaller thing. The JSONata engine ignores the field.
 * @param console not a bound: where the body's `console.*` lines go; null discards them. The
 *   JavaScript engine hands at most 200 lines of at most 2 000 characters each, in the order they
 *   were written, after the evaluation returns or fails; the JSONata engine has no console and
 *   ignores the field.
 */
data class EvaluationLimits(
    val wallClock: Duration,
    val maxDepth: Int,
    val maxHeapBytes: Long? = null,
    val maxStatements: Long? = null,
    val now: Instant? = null,
    val maxInputBytes: Long? = null,
    val maxResultBytes: Long? = null,
    val console: ConsoleSink? = null,
) {
    init {
        require(!wallClock.isNegative && wallClock != Duration.ZERO) {
            "wallClock must be positive, was $wallClock"
        }
        require(maxDepth > 0) { "maxDepth must be positive, was $maxDepth" }
        require(maxHeapBytes == null || maxHeapBytes > 0) {
            "maxHeapBytes must be positive when present, was $maxHeapBytes"
        }
        require(maxStatements == null || maxStatements > 0) {
            "maxStatements must be positive when present, was $maxStatements"
        }
        require(maxInputBytes == null || maxInputBytes > 0) {
            "maxInputBytes must be positive when present, was $maxInputBytes"
        }
        require(maxResultBytes == null || maxResultBytes > 0) {
            "maxResultBytes must be positive when present, was $maxResultBytes"
        }
    }
}
