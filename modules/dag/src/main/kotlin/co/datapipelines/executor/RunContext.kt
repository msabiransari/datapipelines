package co.datapipelines.executor

import co.datapipelines.pipeline.ContextKeys
import co.datapipelines.pipeline.OrgContext
import co.datapipelines.pipeline.ParameterBinder
import co.datapipelines.pipeline.Pipeline
import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

/**
 * One execution's Context — **live**, shared by every node, and assembled from
 * [calculators design §0.2](../../../../../../../docs/superpowers/specs/2026-09-04-calculators-design.md)'s
 * tiers (pipeline-contract §7.1 steps 1–5).
 *
 * ```
 * org config  <  platform keys  <  declared parameters  <  execute-time inputs  <  calculator outputs
 * ```
 *
 * Tiers 1–4 are fixed the moment the execution starts. Tier 5 is why this class exists and why
 * it is a `Map` rather than a snapshot of one: a CALCULATOR node writes its `context_key` at its
 * DAG position, and every node scheduled after it renders and binds against the SAME map, so a
 * downstream `:run_fiscal_quarter` resolves by topology with no plumbing between the two nodes.
 * [NodeExecutionContext.values] holds this instance, so every existing read path — the template
 * render, `SqlBindTranslator.translate` — sees the write with no call-site change.
 *
 * ## Why a lock and not a `ConcurrentHashMap`
 *
 * The Context legitimately holds nulls (an optional parameter that was neither supplied nor
 * defaulted is *present and null* — pipeline-contract §7.4, and `IS NOT DISTINCT FROM :x` is the
 * spelling that needs it). `ConcurrentHashMap` cannot store one. So the map is guarded, and every
 * read that hands out a collection hands out a **copy**: an iteration concurrent with a
 * calculator's write would otherwise be a `ConcurrentModificationException` in a template render.
 *
 * The lock is not what makes a downstream node SEE an upstream write — `PipelineExecutor`
 * awaits a node's dependencies before running it, and `Deferred.await` is a happens-before
 * edge. The validator (`calculator_input_unordered`) is what guarantees that edge exists.
 * The lock protects the map's own structure against unrelated nodes running in parallel.
 */
class RunContext private constructor(
    initial: Map<String, Any?>,
    /**
     * The calculator `context_key`s the CALLER supplied at execute time (078 A5, owner ruling
     * 2026-09-05): a supplied key means the node that would compute it is SKIPPED — the value
     * is already here, bound and coerced by `ParameterBinder`. [NodeRunner] reads this set to
     * decide, and stamps `provided_by: "caller"` on the skipped node's stats. Empty for a
     * Context built over already-resolved values ([of]).
     */
    val callerSupplied: Set<String> = emptySet(),
) : Map<String, Any?> {
    private val lock = Any()
    private val store = LinkedHashMap<String, Any?>(initial)

    /**
     * Writes a calculator's output (tier 5). Returns the previous value, which is `null` both
     * for an absent key and for a present-and-null one — callers that need the difference ask
     * [containsKey] first, exactly as the save-time collision check does.
     */
    fun put(
        key: String,
        value: Any?,
    ): Any? = synchronized(lock) { store.put(key, value) }

    /** An immutable copy — what the snapshot persisted at the end of the run is taken from. */
    fun snapshot(): Map<String, Any?> = synchronized(lock) { LinkedHashMap(store) }

    override val size: Int get() = synchronized(lock) { store.size }
    override val entries: Set<Map.Entry<String, Any?>> get() = snapshot().entries
    override val keys: Set<String> get() = snapshot().keys
    override val values: Collection<Any?> get() = snapshot().values

    override fun containsKey(key: String): Boolean = synchronized(lock) { store.containsKey(key) }

    override fun containsValue(value: Any?): Boolean = synchronized(lock) { store.containsValue(value) }

    override fun get(key: String): Any? = synchronized(lock) { store[key] }

    override fun isEmpty(): Boolean = synchronized(lock) { store.isEmpty() }

    override fun toString(): String = "RunContext(keys=$keys)"

    companion object {
        /**
         * Builds the Context for one execution (§7.1 steps 1–4), in tier order.
         *
         * The org and platform tiers go in first and the bound parameters last, which IS the
         * precedence rule: a pipeline that declares a parameter named `org_timezone` overrides
         * the deployment's, and one that does not simply reads the deployment's. `ParameterBinder`
         * already resolves tiers 3 and 4 between themselves (a supplied input beats a declared
         * default) and raises the §12.7 refusals, so this function adds no parameter semantics of
         * its own.
         *
         * @throws co.datapipelines.pipeline.PipelineValidationException a parameter was missing or
         *   ill-typed — the same 400 the caller would have got before org config existed.
         */
        fun create(
            org: OrgContext,
            pipeline: Pipeline,
            inputs: Map<String, JsonNode>,
            executionId: UUID,
            startedAt: Instant,
        ): RunContext {
            val zone = ContextKeys.zoneOf(org)
            // 078 A5: the pipeline's calculator output keys bind as implicit OPTIONAL inputs —
            // supplied → the value enters the Context from the caller and the node is skipped;
            // unsupplied → the binder puts nothing in, and the node runs and writes the key.
            val calculatorOutputs = pipeline.calculatorOutputs()
            val bound = ParameterBinder(pipeline.parameters, calculatorOutputs).bindOrThrow(inputs).asMap()
            return RunContext(
                org.values + ContextKeys.platformValues(executionId, startedAt, zone) + bound,
                // A calculator key is in the bound map only when the caller supplied it (the
                // binder drops explicit nulls), so the intersection IS the caller-supplied set.
                callerSupplied = bound.keys.intersect(calculatorOutputs.keys),
            )
        }

        /** A Context over already-resolved values — the seam tests and the node-run debug path use. */
        fun of(values: Map<String, Any?>): RunContext = RunContext(values)
    }
}
