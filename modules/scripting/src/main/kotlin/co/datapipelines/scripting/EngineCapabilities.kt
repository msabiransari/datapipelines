package co.datapipelines.scripting

/**
 * Which limits an engine can actually enforce — measured facts, not intentions
 * (transform-nodes design §4.1/§4.5).
 *
 * A caller that passes `EvaluationLimits(maxHeapBytes = …)` to an engine whose
 * [boundsHeap] is false is not protected; the capability table exists so the
 * documentation states that honestly instead of letting a limit field imply a
 * guarantee. Each engine's values are proven by the breach suite (`jsonata-breach.md`,
 * `javascript-breach.md`), whose record dag-executor.md publishes.
 *
 * @property boundsWallClockBetweenSteps true when the wall clock is checked between
 *   expression steps. JSONata observes an overrun inside a builtin only after it returns;
 *   the JavaScript isolate also has a watchdog that closes its Context during a builtin.
 * @property boundsDepth true when expression depth is enforced at every step.
 * @property boundsHeap true when the engine enforces a heap budget. No in-process
 *   engine can do this; the JavaScript engine's polyglot isolate does (`sandbox.MaxHeapMemory`).
 * @property boundsStatements true when a statement budget is enforced: the JavaScript
 *   engine's `sandbox.MaxStatements`; JSONata has no statements to count.
 * @property interruptible true when the pool can stop the engine through its cancellation hook.
 *   The JavaScript engine registers `Context.close(true)`; `Thread.interrupt()` alone cannot
 *   stop its isolate. JSONata has no cancellation hook and never reads the interrupt flag,
 *   so its abandoned thread keeps its pool slot until the work ends on its own.
 */
data class EngineCapabilities(
    val boundsWallClockBetweenSteps: Boolean,
    val boundsDepth: Boolean,
    val boundsHeap: Boolean,
    val boundsStatements: Boolean,
    val interruptible: Boolean,
)
