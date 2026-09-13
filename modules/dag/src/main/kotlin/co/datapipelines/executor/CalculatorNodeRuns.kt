package co.datapipelines.executor

import co.datapipelines.calculators.CalculatorEvaluationException
import co.datapipelines.calculators.CalculatorKind
import co.datapipelines.calculators.CalculatorRegistry
import co.datapipelines.pipeline.CalculatorInputResolver
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import java.time.Instant

/**
 * The CALCULATOR-node leg of [NodeRunner] (§4.10, calculators design §0.3): resolve the inputs
 * from the live Context, evaluate the kind, write the value back under `context_key` — or, on
 * a multi-output kind (121 D5), evaluate once and write every mapped `context_keys` value.
 *
 * Its own object rather than more private methods on [NodeRunner] — the class is at detekt's
 * size ceiling (the move [SourceStreaming] made first), and the calculator leg is self-contained:
 * the runner's only job left is the type dispatch, so everything about *what a calculator write
 * means* lives here, once.
 *
 * ## The caller-supplied half (078 A5, owner ruling 2026-09-05)
 *
 * A calculator key is an implicit optional execute input: supplied, the node does NOT evaluate —
 * the value is already in the live Context, bound and coerced by `ParameterBinder` — and the
 * stats carry the SUPPLIED value with `provided_by: "caller"` so the run detail page shows where
 * the number came from. A calculator that RUNS still `put()`s and wins over everything (tier
 * order org < platform < params < caller-supplied calculator keys < calculator outputs).
 * 121 D5 widens the rule to a set: EVERY key of a multi-output node supplied and the node is
 * skipped; a proper subset never reaches here (`RunContext.create` refuses it with
 * `pipeline.execution.calculator_keys_partial` before any node runs). `provided_by` stays ONE
 * field on the node's stats, with every supplied value in `context_values`.
 *
 * `rows_out` is 0 and that is honest — a calculator produces no rows. What it produced travels
 * as `context_key` / `context_value` (single) or `context_values` (multi) on the node's stats,
 * so the run detail page and `executions_get` can show it.
 */
internal object CalculatorNodeRuns {
    fun run(
        node: ExecutableNode,
        ctx: NodeExecutionContext,
        startedAt: Instant,
        kinds: (String) -> CalculatorKind?,
    ): NodeResult {
        val kindName = node.kind.orEmpty()
        val mapping = node.contextKeys
        return if (mapping != null) {
            multi(node, kindName, mapping, ctx, startedAt, kinds)
        } else {
            single(node, kindName, ctx, startedAt, kinds)
        }
    }

    private fun single(
        node: ExecutableNode,
        kindName: String,
        ctx: NodeExecutionContext,
        startedAt: Instant,
        kinds: (String) -> CalculatorKind?,
    ): NodeResult {
        val contextKey = node.contextKey.orEmpty()
        if (contextKey in ctx.values.callerSupplied) {
            return NodeResult.of(
                nodeId = node.id,
                rowsOut = 0,
                startedAt = startedAt,
                contextKey = contextKey,
                contextValue = ctx.values[contextKey]?.toString(),
                providedBy = NodeResult.PROVIDED_BY_CALLER,
            )
        }
        val value =
            try {
                val kind = requireKind(kinds, kindName)
                kind.evaluate(CalculatorInputResolver.resolve(kind, node.inputs.orEmpty(), ctx.values))
            } catch (e: CalculatorEvaluationException) {
                throw failed(node, kindName, e, "context_key" to contextKey)
            }
        ctx.values.put(contextKey, value)
        return NodeResult.of(
            nodeId = node.id,
            rowsOut = 0,
            startedAt = startedAt,
            contextKey = contextKey,
            contextValue = value?.toString(),
        )
    }

    /**
     * The 121 D5 twin of [single]: evaluate ONCE, `put` every mapped key. Save-time validation
     * (§12.10) proved the mapping covers every declared output; the executor's own check is that
     * the kind's result honours its contract ([shapeChecked]) — a kind whose map lacks a declared
     * output would otherwise write a partial window silently, the exact failure the whole design
     * exists to prevent.
     */
    private fun multi(
        node: ExecutableNode,
        kindName: String,
        mapping: Map<String, String>,
        ctx: NodeExecutionContext,
        startedAt: Instant,
        kinds: (String) -> CalculatorKind?,
    ): NodeResult {
        val keys = mapping.values.toList()
        if (keys.isNotEmpty() && ctx.values.callerSupplied.containsAll(keys)) {
            return NodeResult.of(
                nodeId = node.id,
                rowsOut = 0,
                startedAt = startedAt,
                contextValues = keys.associateWith { ctx.values[it]?.toString() },
                providedBy = NodeResult.PROVIDED_BY_CALLER,
            )
        }
        val results =
            try {
                val kind = requireKind(kinds, kindName)
                shapeChecked(kindName, kind.evaluate(CalculatorInputResolver.resolve(kind, node.inputs.orEmpty(), ctx.values)), mapping)
            } catch (e: CalculatorEvaluationException) {
                throw failed(node, kindName, e, "context_keys" to keys)
            }
        mapping.forEach { (output, key) -> ctx.values.put(key, results[output]) }
        return NodeResult.of(
            nodeId = node.id,
            rowsOut = 0,
            startedAt = startedAt,
            contextValues = mapping.values.associateWith { ctx.values[it]?.toString() },
        )
    }

    /**
     * The result contract a registered multi-output kind already satisfies (`CalculatorPurityTest`
     * asserts it over the registry), re-checked at the write boundary because the alternative is a
     * silent half-window: the map must exist and carry every output the node's mapping writes.
     */
    private fun shapeChecked(
        kindName: String,
        value: Any?,
        mapping: Map<String, String>,
    ): Map<*, *> {
        if (value !is Map<*, *>) {
            throw CalculatorEvaluationException(
                null,
                "Kind '$kindName' is multi-output but evaluated to a ${value?.javaClass?.simpleName ?: "null"}, " +
                    "not a map of its declared outputs.",
            )
        }
        if (!value.keys.containsAll(mapping.keys)) {
            throw CalculatorEvaluationException(
                null,
                "Kind '$kindName' evaluated without its declared output(s) " +
                    "${mapping.keys.filter { it !in value.keys }} — a multi-output kind's result must carry every one.",
            )
        }
        return value
    }

    /** The registry lookup with [CalculatorRegistry.require]'s exact refusal shape. */
    private fun requireKind(
        kinds: (String) -> CalculatorKind?,
        kindName: String,
    ): CalculatorKind = kinds(kindName) ?: throw CalculatorEvaluationException(null, "No calculator kind named '$kindName'.")

    private fun failed(
        node: ExecutableNode,
        kindName: String,
        cause: CalculatorEvaluationException,
        keys: Pair<String, Any>,
    ) = DatapipelinesException(
        code = PipelineErrorCodes.Node.CALCULATOR_FAILED,
        message = "Calculator '$kindName' on node '${node.id}' failed: ${cause.message}",
        details =
            mapOf(
                "node" to node.id,
                "kind" to kindName,
                "input" to cause.input,
                keys,
            ),
        cause = cause,
    )
}
