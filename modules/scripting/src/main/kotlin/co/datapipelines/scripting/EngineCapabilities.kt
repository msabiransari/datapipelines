package co.datapipelines.scripting

/**
 * Which limits an engine can actually enforce — measured facts, not intentions
 * (transform-nodes design §4.1/§4.5).
 *
 * A caller that passes `EvaluationLimits(maxHeapBytes = …)` to an engine whose
 * [boundsHeap] is false is not protected; the capability table exists so the
 * documentation states that honestly instead of letting a limit field imply a
 * guarantee. The JSONata engine's values are proven by the breach suite, whose
 * record dag-executor.md publishes.
 *
 * @property boundsWallClockBetweenSteps true when the wall clock is checked between
 *   expression steps. A single builtin call that overruns is caught only AFTER it
 *   finishes — or by the evaluation pool's abandonment, never by an in-process bound.
 * @property boundsDepth true when expression depth is enforced at every step.
 * @property boundsHeap true when the engine enforces a heap budget in-process. No
 *   in-process engine can do this; round two's polyglot isolate can.
 * @property boundsStatements true when a statement budget is enforced. Reserved for
 *   the isolate engine.
 * @property interruptible true when the engine observes `Thread.interrupt()`. The
 *   JSONata engine never reads the flag (no `Thread.interrupted()` anywhere in its
 *   sources), so an abandoned evaluation's thread lives until its work ends on its own;
 *   the pool abandons it instead of joining it, and the thread keeps its slot until it ends.
 */
data class EngineCapabilities(
    val boundsWallClockBetweenSteps: Boolean,
    val boundsDepth: Boolean,
    val boundsHeap: Boolean,
    val boundsStatements: Boolean,
    val interruptible: Boolean,
)
