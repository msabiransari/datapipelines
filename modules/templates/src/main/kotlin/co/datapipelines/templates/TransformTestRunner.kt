package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.scripting.CanonicalJson
import co.datapipelines.scripting.CompiledScript
import co.datapipelines.scripting.EvaluationLimits
import co.datapipelines.scripting.GateRefusal
import co.datapipelines.scripting.ScriptEngine
import co.datapipelines.scripting.ScriptEvaluationPool
import co.datapipelines.scripting.ScriptLanguage
import co.datapipelines.scripting.ScriptingException
import co.datapipelines.scripting.TypeGate
import co.datapipelines.scripting.TypeGate.GateResult
import co.datapipelines.typesystem.ColumnSchema
import java.time.Duration
import java.time.Instant

/**
 * The test runner and the single-case evaluator (transform-nodes design §8.1, §9.1): every
 * test case of a transform template runs through the real engine under the version's limits
 * on the evaluation pool; its output passes the type gate against the contract (R1 rounding
 * included); the invariants run on it; the result is compared to `expect` after
 * canonicalisation (D-T12 — exact equality or a refusal code, no tolerances).
 *
 * [runSuite] is the save/release gate; [runCase] is the shape `templates_evaluate` and the
 * REST evaluate route share (record §9.1: the tool, the route and the test runner are one
 * service). Neither stages anything and neither touches the Context.
 *
 * The pool is a constructor parameter: evaluations never run on a request thread (record
 * §9.5). [evaluateTimeout] bounds one case/probe; [suiteTimeout] bounds the whole suite.
 */
class TransformTestRunner(
    private val engines: Map<ScriptLanguage, ScriptEngine>,
    private val pool: ScriptEvaluationPool,
    private val evaluateTimeout: Duration,
    private val suiteTimeout: Duration,
    private val maxInputRows: Long = 100_000,
    private val maxStringBytes: Long = 1_048_576,
    private val maxValueBytes: Long = 1_048_576,
    private val maxDepth: Int = 100,
) {
    /** One invariant's verdict on one evaluation (record §9.1's `{ name, passed, message }`). */
    data class InvariantVerdict(
        val name: String,
        val passed: Boolean,
        val message: String,
    )

    /** The result of one case or probe. */
    sealed interface RunOutcome {
        /** The run refused — the code the caller maps (engine, gate, input-check or pool refusal). */
        data class Refused(
            val code: String,
            val message: String,
        ) : RunOutcome

        /** The run evaluated, gated and checked. */
        data class Evaluated(
            val output: Any?,
            val rejects: List<Map<String, Any?>>,
            val invariants: List<InvariantVerdict>,
        ) : RunOutcome
    }

    private data class Gated(
        val output: Any?,
        val rejects: List<Map<String, Any?>>,
    )

    /** One case's evaluated input (the test-case object and its two halves). */
    private data class CaseContext(
        val input: TransformTestInput,
        val rows: List<Map<String, Any?>>,
        val inputs: Map<String, Any?>,
    )

    /** A case's clock pin, parsed (record §2.2, v0.3.1): ISO-8601, or null (the clock builtins refuse). */
    fun caseNow(input: TransformTestInput): Instant? =
        input.now?.let { raw ->
            runCatching { Instant.parse(raw) }.getOrNull()
        }

    /** The engine for a transform type, or null where the type rule has already refused the save. */
    fun engineFor(type: TemplateType): ScriptEngine? =
        when (type) {
            TemplateType.JSONATA -> engines[ScriptLanguage.JSONATA]
            TemplateType.JAVASCRIPT -> engines[ScriptLanguage.JAVASCRIPT]
            else -> null
        }

    /**
     * Runs the template's whole suite (§8.1), in declaration order, and returns every
     * `template.test_failed` / `template.invariant_invalid` failure — empty when every case
     * holds. The suite is bounded by [suiteTimeout]: a case starting past the deadline fails
     * the save like any case failure.
     */
    fun runSuite(
        label: String,
        body: String,
        type: TemplateType,
        contract: TransformContract,
        invariants: List<TransformInvariant>,
        tests: List<TransformTestCase>,
    ): List<TemplateValidationFailure> {
        val engine = engineFor(type) ?: return emptyList()
        val script = engine.compile(body)
        val compiledInvariants = invariants.map { it to jsonata().compile(it.expr) }
        val deadline = System.nanoTime() + suiteTimeout.toNanos()
        val failures = mutableListOf<TemplateValidationFailure>()
        tests.forEach { case ->
            if (System.nanoTime() > deadline) {
                failures +=
                    TemplateValidationFailure(
                        code = PipelineErrorCodes.Template.TEST_FAILED,
                        message =
                            "The test suite did not finish within ${suiteTimeout.toSeconds()}s " +
                                "(suite-timeout-seconds).",
                        details = mapOf("timeout" to "suite", "suite_timeout_seconds" to suiteTimeout.toSeconds()),
                    )
                return@forEach
            }
            val outcome = runCase("$label case '${case.name}'", engine, script, contract, compiledInvariants, case.input)
            failures += verdictOf(case, outcome)
        }
        return failures
    }

    /**
     * One case or probe (record §9.1): the §5.1 input check, one evaluation on the pool, the
     * §5.3 type gate, then every invariant over `{ rows, rejects, inputs }`. Invariants are
     * compiled by the caller — they do not vary per case. [now] is the production clock pin
     * (the execution's `current_timestamp`); a case's own `now` wins when present.
     */
    @Suppress("ReturnCount") // each §4.3/§5.1 guard is its own early refusal; the order is the contract
    fun runCase(
        label: String,
        engine: ScriptEngine,
        script: CompiledScript,
        contract: TransformContract,
        compiledInvariants: List<Pair<TransformInvariant, CompiledScript>>,
        input: TransformTestInput,
        now: Instant? = null,
    ): RunOutcome {
        val rows = input.rows.orEmpty()
        val inputs = input.inputs.orEmpty()

        inputCaps(contract, rows, inputs)?.let { return it }

        // §5.1: rows and each table input against the contract's columns, value inputs against types.
        inputViolation(contract, rows, inputs)?.let { return it }

        val limits =
            EvaluationLimits(
                wallClock = evaluateTimeout,
                maxDepth = maxDepth,
                now = caseNow(input) ?: now,
            )
        val evaluated = evaluateOnPool(label, engine, script, contract, CaseContext(input, rows, inputs), limits)
        if (evaluated is RunOutcome.Refused) return evaluated

        // Shape + gate the output (§5.3).
        val gated =
            when (val shaped = shapeAndGate(contract, (evaluated as RunOutcome.Evaluated).output)) {
                is RunOutcome.Refused -> return shaped
                is Gated -> shaped
                else -> error("shapeAndGate returned ${shaped.javaClass}")
            }

        val verdicts =
            runInvariants(label, contract, compiledInvariants, gated, rows, inputs, limits)
        if (verdicts is RunOutcome.Refused) return verdicts
        return RunOutcome.Evaluated(gated.output, gated.rejects, (verdicts as RunOutcome.Evaluated).invariants)
    }

    /** Caps BEFORE any evaluation (§4.3): the row batch, or any table input, above the row cap. */
    private fun inputCaps(
        contract: TransformContract,
        rows: List<Map<String, Any?>>,
        inputs: Map<String, Any?>,
    ): RunOutcome.Refused? {
        val tableCounts =
            contract.inputs
                .filterValues { it is TransformInput.Table }
                .keys
                .associateWith { name ->
                    when (contract.mode) {
                        TransformMode.ROW -> rows.size.toLong()
                        else -> ((inputs[name] as? List<*>)?.size ?: 0).toLong()
                    }
                }
        if (rows.size.toLong() > maxInputRows || tableCounts.values.any { it > maxInputRows }) {
            return RunOutcome.Refused(
                TransformCodes.INPUT_TOO_LARGE,
                "input exceeds max-input-rows ($maxInputRows): rows=${rows.size}, tables=$tableCounts",
            )
        }
        return null
    }

    /** The one engine evaluation on the pool — the input object is §3.2's, mirroring production (R2). */
    private fun evaluateOnPool(
        label: String,
        engine: ScriptEngine,
        script: CompiledScript,
        contract: TransformContract,
        case: CaseContext,
        limits: EvaluationLimits,
    ): RunOutcome {
        val inputObject =
            buildMap<String, Any?> {
                if (contract.mode == TransformMode.ROW) put("rows", case.rows)
                put("inputs", productionInputs(contract, case.rows, case.inputs))
                put("meta", case.input.meta ?: mapOf("node_id" to "test", "context_key" to null))
            }
        return try {
            RunOutcome.Evaluated(pool.run(limits, label) { engine.evaluate(script, inputObject, limits) }, emptyList(), emptyList())
        } catch (err: ScriptingException) {
            RunOutcome.Refused(err.code, err.message ?: err.code)
        }
    }

    /** Every invariant over `{ rows, rejects, inputs }` (§5.4; R2 — the table rides under its name). */
    private fun runInvariants(
        label: String,
        contract: TransformContract,
        compiledInvariants: List<Pair<TransformInvariant, CompiledScript>>,
        gated: Gated,
        rows: List<Map<String, Any?>>,
        inputs: Map<String, Any?>,
        limits: EvaluationLimits,
    ): RunOutcome {
        val invariantObject =
            mapOf(
                "rows" to gatedRows(gated.output, contract),
                "rejects" to gated.rejects,
                "inputs" to productionInputs(contract, rows, inputs),
            )
        val verdicts = mutableListOf<InvariantVerdict>()
        compiledInvariants.forEach { (invariant, compiled) ->
            val verdict =
                try {
                    pool.run(limits, "$label invariant '${invariant.name}'") {
                        jsonata().evaluate(compiled, invariantObject, limits)
                    }
                } catch (err: ScriptingException) {
                    return RunOutcome.Refused(err.code, "invariant '${invariant.name}': ${err.message ?: err.code}")
                }
            when (verdict) {
                is Boolean -> {
                    verdicts += InvariantVerdict(invariant.name, verdict, invariant.message)
                }

                else -> {
                    return RunOutcome.Refused(
                        PipelineErrorCodes.Template.INVARIANT_INVALID,
                        "invariant '${invariant.name}' produced ${CanonicalJson.write(verdict).bounded()} " +
                            "instead of a boolean",
                    )
                }
            }
        }
        return RunOutcome.Evaluated(null, emptyList(), verdicts)
    }

    /** The rows half of a gated table output (the invariants' `rows`). */
    private fun gatedRows(
        output: Any?,
        contract: TransformContract,
    ): Any? =
        when {
            contract.output !is TransformOutput.Table -> output
            contract.rejects -> (output as? Map<*, *>)?.get("rows")
            else -> output
        }

    /** The value inputs plus, for table/value modes the loaded tables and for row mode the batch under its contract name (R2). */
    private fun productionInputs(
        contract: TransformContract,
        rows: List<Map<String, Any?>>,
        inputs: Map<String, Any?>,
    ): Map<String, Any?> =
        buildMap {
            putAll(inputs)
            if (contract.mode == TransformMode.ROW) {
                contract.inputs
                    .filterValues { it is TransformInput.Table }
                    .keys
                    .forEach { name -> put(name, rows) }
            }
        }

    /** The §5.1 check as a refusal, or null when every input fits. */
    @Suppress("UNCHECKED_CAST", "ReturnCount")
    // gateRow's Pass value is a LinkedHashMap by the gate's contract; every input's first
    // violation is its own early refusal.
    private fun inputViolation(
        contract: TransformContract,
        rows: List<Map<String, Any?>>,
        inputs: Map<String, Any?>,
    ): RunOutcome.Refused? {
        if (contract.mode == TransformMode.ROW) {
            val table =
                contract.inputs
                    .values
                    .filterIsInstance<TransformInput.Table>()
                    .singleOrNull()
            if (table != null) {
                val gate = TypeGate.over(table.columns.toColumnSchemas(), maxStringBytes)
                rows.forEachIndexed { index, row ->
                    val verdict = gate.gateRow(row, index + 1)
                    if (verdict is GateResult.Refuse) {
                        return RunOutcome.Refused(
                            TransformCodes.INPUT_CONTRACT_VIOLATION,
                            "row ${index + 1}: ${describe(verdict.refusal)}",
                        )
                    }
                }
            }
        }
        contract.inputs.forEach { (name, input) ->
            when (input) {
                is TransformInput.Table -> {
                    if (contract.mode != TransformMode.ROW) {
                        val gate = TypeGate.over(input.columns.toColumnSchemas(), maxStringBytes)
                        val table = (inputs[name] as? List<*>) ?: emptyList<Any?>()
                        table.forEachIndexed { index, entry ->
                            val map =
                                entry as? Map<String, Any?>
                                    ?: return RunOutcome.Refused(
                                        TransformCodes.INPUT_CONTRACT_VIOLATION,
                                        "input '$name' row ${index + 1}: not an object",
                                    )
                            val verdict = gate.gateRow(map, index + 1)
                            if (verdict is GateResult.Refuse) {
                                return RunOutcome.Refused(
                                    TransformCodes.INPUT_CONTRACT_VIOLATION,
                                    "input '$name' row ${index + 1}: ${describe(verdict.refusal)}",
                                )
                            }
                        }
                    }
                }

                is TransformInput.Value -> {
                    val column = ColumnSchema(name, input.type, input.precision, input.scale, nullable = false)
                    val verdict = TypeGate.over(listOf(column), maxStringBytes).gateValue(inputs[name], column)
                    if (verdict is GateResult.Refuse) {
                        return RunOutcome.Refused(
                            TransformCodes.INPUT_CONTRACT_VIOLATION,
                            "input '$name': ${describe(verdict.refusal)}",
                        )
                    }
                }
            }
        }
        return null
    }

    /** Shapes the engine result and gates it (§5.3) — the output (rows/value/object) and the rejects. */
    @Suppress("UNCHECKED_CAST") // gateRow's Pass value is a LinkedHashMap<String, Any?> by the gate's contract
    private fun shapeAndGate(
        contract: TransformContract,
        evaluated: Any?,
    ): Any {
        when (val output = contract.output) {
            is TransformOutput.Obj -> {
                return gateObjectOutput(evaluated)
            }

            is TransformOutput.Value -> {
                return gateValueOutput(output, evaluated)
            }

            is TransformOutput.Table -> {
                return gateTableOutput(contract, output, evaluated)
            }
        }
    }

    /** A table output: the rows (and rejects, when declared) shaped, then gated row by row (§5.3). */
    @Suppress("UNCHECKED_CAST", "ReturnCount")
    // gateRow's Pass value is a LinkedHashMap<String, Any?> by the gate's contract; the shape and
    // reject guards are each their own early refusal (§5.3's mix-shape rule).
    private fun gateTableOutput(
        contract: TransformContract,
        output: TransformOutput.Table,
        evaluated: Any?,
    ): Any {
        val (rawRows, rawRejects) =
            shapeRows(contract, evaluated)
                ?: return RunOutcome.Refused(
                    TransformCodes.ROW_SHAPE_MISMATCH,
                    "the function's return does not match the declared output shape",
                )

        val gate = TypeGate.over(output.columns.toColumnSchemas(), maxStringBytes)
        val rowsOut = mutableListOf<Map<String, Any?>>()
        rawRows.forEachIndexed { index, entry ->
            val map =
                entry as? Map<String, Any?>
                    ?: return RunOutcome.Refused(TransformCodes.ROW_SHAPE_MISMATCH, "row ${index + 1}: not an object")
            when (val verdict = gate.gateRow(map, index + 1)) {
                is GateResult.Refuse -> return refused(verdict.refusal)
                is GateResult.Pass -> rowsOut += verdict.value as Map<String, Any?>
            }
        }

        val rejectsOutcome = gateRejects(contract, output, rawRejects)
        if (rejectsOutcome is RunOutcome.Refused) return rejectsOutcome
        val gatedRejects = (rejectsOutcome as RunOutcome.Evaluated).rejects
        val outputValue: Any =
            if (contract.rejects) {
                mapOf("rows" to rowsOut, "rejects" to gatedRejects)
            } else {
                rowsOut
            }
        return Gated(outputValue, gatedRejects)
    }

    /** The `{ rows, rejects }` (or bare array) the function returned, as raw lists (§5.3's mix-shape rule). */
    @Suppress("ReturnCount") // a shape mismatch at any step is null; every other path returns
    private fun shapeRows(
        contract: TransformContract,
        evaluated: Any?,
    ): Pair<List<*>, List<*>>? {
        if (!contract.rejects) {
            val rows = evaluated as? List<*> ?: return null
            return rows to emptyList<Any?>()
        }
        val asMap = evaluated as? Map<*, *> ?: return null
        val rows = asMap["rows"] as? List<*> ?: return null
        val rejects = asMap["rejects"] as? List<*> ?: return null
        return rows to rejects
    }

    /** The rejects half of a rejects contract, gated row by row with a non-empty reason (§5.3). */
    @Suppress("UNCHECKED_CAST", "ReturnCount")
    // gateRow's Pass value is a LinkedHashMap by the gate's contract; each reject's first
    // violation is its own early refusal.
    private fun gateRejects(
        contract: TransformContract,
        output: TransformOutput.Table,
        rawRejects: List<*>,
    ): RunOutcome {
        if (!contract.rejects) return RunOutcome.Evaluated(null, emptyList(), emptyList())
        val rejectColumns =
            when (contract.mode) {
                TransformMode.ROW -> {
                    contract.inputs
                        .values
                        .filterIsInstance<TransformInput.Table>()
                        .singleOrNull()
                        ?.columns
                }

                else -> {
                    output.columns
                }
            }.orEmpty()
        val rejectGate = TypeGate.over(rejectColumns.toColumnSchemas(), maxStringBytes)
        val rejectsOut = mutableListOf<Map<String, Any?>>()
        rawRejects.forEachIndexed { index, entry ->
            val map =
                entry as? Map<String, Any?>
                    ?: return RunOutcome.Refused(TransformCodes.ROW_SHAPE_MISMATCH, "reject ${index + 1}: not an object")
            val reason = map["reason"] as? String
            if (reason.isNullOrBlank()) {
                return RunOutcome.Refused(
                    TransformCodes.VALUE_TYPE_MISMATCH,
                    "reject ${index + 1}: 'reason' must be a non-empty string",
                )
            }
            val row =
                map["row"] as? Map<String, Any?>
                    ?: return RunOutcome.Refused(
                        TransformCodes.ROW_SHAPE_MISMATCH,
                        "reject ${index + 1}: 'row' is not an object",
                    )
            val verdict = rejectGate.gateRow(row, index + 1)
            if (verdict is GateResult.Refuse) return refused(verdict.refusal)
            rejectsOut += mapOf("row" to (verdict as GateResult.Pass).value, "reason" to reason)
        }
        return RunOutcome.Evaluated(null, rejectsOut, emptyList())
    }

    /** An object output: the value must be a JSON object whose canonical form fits the byte cap (R4). */
    private fun gateObjectOutput(evaluated: Any?): Any =
        when (val verdict = TypeGate.over(emptyList(), maxStringBytes).gateObject(evaluated, maxValueBytes)) {
            is GateResult.Refuse -> refused(verdict.refusal)
            is GateResult.Pass -> Gated(verdict.value, emptyList())
        }

    /** A value output: the declared type through the gate, then the byte cap (§4.3). */
    private fun gateValueOutput(
        output: TransformOutput.Value,
        evaluated: Any?,
    ): Any {
        val column = ColumnSchema("value", output.type, output.precision, output.scale, nullable = false)
        val gated =
            when (val verdict = TypeGate.over(listOf(column), maxStringBytes).gateValue(evaluated, column)) {
                is GateResult.Refuse -> return refused(verdict.refusal)
                is GateResult.Pass -> verdict.value
            }
        val bytes =
            CanonicalJson
                .write(gated)
                .toByteArray(Charsets.UTF_8)
                .size
                .toLong()
        if (bytes > maxValueBytes) {
            return RunOutcome.Refused(TransformCodes.VALUE_TOO_LARGE, "value output is $bytes bytes (cap $maxValueBytes)")
        }
        return Gated(gated, emptyList())
    }

    /**
     * The save-time verdict for one case against its `expect` (D-T12): a refusal expectation
     * matches only that code (an unexpected success is a failure too); an output expectation
     * compares after canonicalisation, with the first difference's path and both sides bounded
     * to 2 000 chars in total. A non-boolean invariant is `invariant_invalid` naming the case;
     * a false one is `test_failed` naming the invariant.
     */
    private fun verdictOf(
        case: TransformTestCase,
        outcome: RunOutcome,
    ): List<TemplateValidationFailure> {
        val refusal = case.expect.refusal
        if (refusal != null) {
            return when (outcome) {
                is RunOutcome.Refused -> {
                    if (outcome.code == refusal) {
                        emptyList()
                    } else {
                        listOf(
                            testFailed(
                                case.name,
                                "expected refusal '$refusal' but the run refused with '${outcome.code}' (${outcome.message.bounded()})",
                            ),
                        )
                    }
                }

                is RunOutcome.Evaluated -> {
                    listOf(
                        testFailed(
                            case.name,
                            "expected refusal '$refusal' but the run SUCCEEDED (output ${CanonicalJson.write(outcome.output).bounded()})",
                        ),
                    )
                }
            }
        }
        return when (outcome) {
            is RunOutcome.Refused -> unexpectedRefusal(case, outcome)
            is RunOutcome.Evaluated -> outputVerdict(case, outcome)
        }
    }

    /** An output-expected case whose run REFUSED — `invariant_invalid` keeps its own code. */
    private fun unexpectedRefusal(
        case: TransformTestCase,
        outcome: RunOutcome.Refused,
    ): List<TemplateValidationFailure> {
        if (outcome.code == PipelineErrorCodes.Template.INVARIANT_INVALID) {
            return listOf(
                TemplateValidationFailure(
                    code = PipelineErrorCodes.Template.INVARIANT_INVALID,
                    message = "Test case '${case.name}': ${outcome.message}",
                    details = mapOf("case" to case.name, "message" to outcome.message.bounded()),
                ),
            )
        }
        return listOf(
            testFailed(
                case.name,
                "expected an output but the run refused with '${outcome.code}' (${outcome.message.bounded()})",
            ),
        )
    }

    /** The output-expected verdict: canonical equality (first difference's path), then invariants. */
    private fun outputVerdict(
        case: TransformTestCase,
        outcome: RunOutcome.Evaluated,
    ): List<TemplateValidationFailure> {
        val expected = TransformBlocks.mapper.convertValue(case.expect.output, Any::class.java)
        val diff = firstDifference("$", expected, outcome.output)
        val invariantFailure =
            outcome.invariants.firstOrNull { !it.passed }?.let { invariant ->
                testFailed(case.name, "invariant '${invariant.name}' is false: ${invariant.message}", invariant.name)
            }
        return listOfNotNull(
            diff?.let { (path, expected, actual) ->
                testFailed(
                    case.name,
                    "output differs at $path — expected ${CanonicalJson.write(expected).bounded()}, " +
                        "actual ${CanonicalJson.write(actual).bounded()}",
                )
            },
            invariantFailure,
        )
    }

    /** The first structural difference between two JSON-shaped values, numbers compared by value (§5.5). */
    @Suppress("ReturnCount") // recursion returns the first difference found; every other path falls through to null
    private fun firstDifference(
        path: String,
        expected: Any?,
        actual: Any?,
    ): Triple<String, Any?, Any?>? {
        if (expected is Map<*, *> && actual is Map<*, *>) {
            (expected.keys + actual.keys).distinct().forEach { key ->
                val diff = firstDifference("$path.$key", expected[key], actual[key])
                if (diff != null) return diff
            }
            return null
        }
        if (expected is List<*> && actual is List<*>) {
            val size = maxOf(expected.size, actual.size)
            for (index in 0 until size) {
                val diff =
                    firstDifference(
                        "$path[$index]",
                        expected.getOrNull(index),
                        actual.getOrNull(index),
                    )
                if (diff != null) return diff
            }
            return null
        }
        return if (CanonicalJson.equal(expected, actual)) null else Triple(path, expected, actual)
    }

    private fun refused(refusal: GateRefusal): RunOutcome.Refused = RunOutcome.Refused(codeOf(refusal), describe(refusal))

    private fun codeOf(refusal: GateRefusal): String =
        when (refusal) {
            is GateRefusal.RowShapeMismatch -> TransformCodes.ROW_SHAPE_MISMATCH
            is GateRefusal.ValueTypeMismatch -> TransformCodes.VALUE_TYPE_MISMATCH
            is GateRefusal.PrecisionLost -> TransformCodes.PRECISION_LOST
            is GateRefusal.ValueTooLarge -> TransformCodes.VALUE_TOO_LARGE
        }

    private fun describe(refusal: GateRefusal): String =
        when (refusal) {
            is GateRefusal.RowShapeMismatch -> {
                "row ${refusal.row}: shape mismatch (missing ${refusal.missing}, extra ${refusal.extra})"
            }

            is GateRefusal.ValueTypeMismatch -> {
                "${refusal.column}: expected ${refusal.expected}, got ${refusal.actual}"
            }

            is GateRefusal.PrecisionLost -> {
                "${refusal.column}: ${refusal.detail}"
            }

            is GateRefusal.ValueTooLarge -> {
                "${refusal.column}: ${refusal.bytes} bytes above the cap"
            }
        }

    private fun jsonata(): ScriptEngine = engines.getValue(ScriptLanguage.JSONATA)

    private fun testFailed(
        caseName: String,
        assertion: String,
        invariant: String? = null,
    ) = TemplateValidationFailure(
        code = PipelineErrorCodes.Template.TEST_FAILED,
        message = "Test case '$caseName' failed: ${assertion.bounded()}",
        details =
            buildMap {
                put("case", caseName)
                put("assertion", assertion.bounded())
                invariant?.let { put("invariant", it) }
            },
    )

    private fun List<ContractColumn>.toColumnSchemas(): List<ColumnSchema> =
        map { ColumnSchema(it.name, it.type, it.precision, it.scale, nullable = it.nullable) }

    private fun String.bounded(): String = if (length <= MAX_DETAIL_CHARS) this else take(MAX_DETAIL_CHARS) + "…"

    companion object {
        /** The bounded-diff cap of record §8.1. */
        private const val MAX_DETAIL_CHARS = 2_000
    }
}
