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
 * @param maxDepth maximum expression/recursion depth; the JSONata engine maps it onto
 *   the library's `maxRecursionDepth` (checked at every evaluate entry/exit).
 * @param maxHeapBytes heap budget in bytes, or null when the caller declares none. No
 *   in-process engine can enforce this today (`EngineCapabilities.boundsHeap` is false);
 *   carrying the field keeps the seam stable for the round-two isolate, which can.
 * @param maxStatements statement budget, or null when the caller declares none. Same
 *   story as [maxHeapBytes]: reserved for the isolate engine (`boundsStatements`).
 * @param now the instant `$now()` and `$millis()` return, or null. A transform is a pure
 *   function of its inputs (D-T4), so production callers always pin this to the
 *   execution's `current_timestamp` — a template is reproducible because the clock is an
 *   input, not an ambient read. When null, the engine refuses any body that calls the
 *   clock functions (the refusal fires when the body actually evaluates them).
 */
data class EvaluationLimits(
    val wallClock: Duration,
    val maxDepth: Int,
    val maxHeapBytes: Long? = null,
    val maxStatements: Long? = null,
    val now: Instant? = null,
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
    }
}
