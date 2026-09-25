package co.datapipelines.web.ui

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.scripting.CanonicalJson
import co.datapipelines.scripting.CompiledScript
import co.datapipelines.scripting.ScriptEngine
import co.datapipelines.scripting.ScriptingException
import co.datapipelines.templates.TemplateDraft
import co.datapipelines.templates.TemplateValidationFailure
import co.datapipelines.templates.TemplateValidator
import co.datapipelines.templates.TransformBlocks
import co.datapipelines.templates.TransformContract
import co.datapipelines.templates.TransformInvariant
import co.datapipelines.templates.TransformTestCase
import co.datapipelines.templates.TransformTestRunner
import co.datapipelines.templates.TransformTestRunner.RunOutcome
import java.time.Duration
import java.util.UUID

/**
 * The face's **Run suite** (7d; owner ruling 2026-09-25): the four panes AS TYPED — unsaved —
 * through 7b's machinery, writing nothing.
 *
 * Two passes, one budget:
 *
 * 1. **7b's save gate** — the `TemplateValidator` bean, exactly what Save would run: the static
 *    checks (a refusal there names its pane and the suite does not run, as at save), then the
 *    suite. Its answer is the result's [SuiteResult.saveAccepted] line, so "the suite is green"
 *    and "Save would accept" can never disagree on screen.
 * 2. **The per-case detail** — each case through [TransformTestRunner.runCase] (the same
 *    runner, pool and per-case limits), so the list can show what the gate's one-line message
 *    cannot: every invariant's verdict, and the difference as its path plus both sides.
 *
 * The suite is bounded as 7b bounds it: pass 2 shares pass 1's `suite-timeout-seconds`
 * deadline, so a click costs at most one suite budget (plus one case's overrun); a case that
 * would start past it is reported as not run, never silently dropped.
 */
internal class TransformSuiteRun(
    private val validator: TemplateValidator,
    private val runner: TransformTestRunner,
    private val suiteTimeout: Duration,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    fun run(
        workspaceId: UUID,
        draft: TemplateDraft,
    ): SuiteResult {
        val started = nanoTime()
        val failures = validator.validate(draft, workspaceId).failures
        val static = failures.filterNot { it.isCaseVerdict() }
        if (static.isNotEmpty()) {
            return SuiteResult(saveAccepted = false, saveCode = static.first().code, refusals = static.map(TransformFace::refusalOf))
        }
        val cases =
            try {
                detail(draft, deadline = started + suiteTimeout.toNanos())
            } catch (err: ScriptingException) {
                // The gate compiled the same body a moment ago; a compile failure here is the
                // engine disagreeing with itself, and it is reported, not thrown.
                return SuiteResult(
                    saveAccepted = false,
                    saveCode = err.code,
                    refusals = listOf(FaceRefusal(TransformFace.BODY, err.code, err.message ?: err.code, detail = null)),
                )
            }
        return SuiteResult(saveAccepted = failures.isEmpty(), saveCode = failures.firstOrNull()?.code, cases = cases)
    }

    /** A per-case verdict of the gate (a case failed, or the suite ran out of time), not a static refusal. */
    private fun TemplateValidationFailure.isCaseVerdict(): Boolean =
        code == PipelineErrorCodes.Template.TEST_FAILED ||
            (code == PipelineErrorCodes.Template.INVARIANT_INVALID && details.containsKey("case"))

    /** What every case of one run shares: the engine, the compiled body and invariants, the contract. */
    private class Compiled(
        val engine: ScriptEngine,
        val script: CompiledScript,
        val contract: TransformContract,
        val invariants: List<Pair<TransformInvariant, CompiledScript>>,
    )

    /**
     * Compiles the draft once for the whole detail pass, or null. The gate refused every shape
     * that reaches here without these (blocks_missing, the JavaScript round-two refusal), so
     * an absent one is an empty list, not a crash.
     */
    private fun compile(draft: TemplateDraft): Compiled? {
        val engine = draft.type?.let(runner::engineFor)
        val jsonata = runner.engineFor(TemplateType.JSONATA)
        val contract = draft.contract
        if (engine == null || jsonata == null || contract == null) return null
        return Compiled(engine, engine.compile(draft.body), contract, draft.invariants.orEmpty().map { it to jsonata.compile(it.expr) })
    }

    private fun detail(
        draft: TemplateDraft,
        deadline: Long,
    ): List<CaseResult> {
        val compiled = compile(draft) ?: return emptyList()
        return draft.tests.orEmpty().map { case ->
            if (nanoTime() > deadline) {
                CaseResult(name = case.name, passed = false, notRun = true)
            } else {
                val label = "${draft.id ?: "<unsaved>"} case '${case.name}'"
                verdict(
                    case,
                    runner.runCase(label, compiled.engine, compiled.script, compiled.contract, compiled.invariants, case.input),
                )
            }
        }
    }

    /** D-T12: a refusal expectation matches only its code; an output one is canonical equality AND every invariant true. */
    private fun verdict(
        case: TransformTestCase,
        outcome: RunOutcome,
    ): CaseResult {
        val expectedRefusal = case.expect.refusal
        return when (outcome) {
            is RunOutcome.Refused -> {
                CaseResult(
                    name = case.name,
                    passed = expectedRefusal != null && outcome.code == expectedRefusal,
                    expectedRefusal = expectedRefusal,
                    expected = case.expect.output?.let { bounded(CanonicalJson.write(it)) },
                    actualRefusal = outcome.code,
                    refusalMessage = bounded(outcome.message),
                )
            }

            is RunOutcome.Evaluated -> {
                evaluated(case, outcome)
            }
        }
    }

    private fun evaluated(
        case: TransformTestCase,
        outcome: RunOutcome.Evaluated,
    ): CaseResult {
        val invariants = outcome.invariants.map { InvariantResult(it.name, it.passed, it.message) }
        val expectedRefusal = case.expect.refusal
        if (expectedRefusal != null) {
            return CaseResult(
                name = case.name,
                passed = false,
                expectedRefusal = expectedRefusal,
                actual = bounded(CanonicalJson.write(outcome.output)),
                invariants = invariants,
            )
        }
        val expected = TransformBlocks.mapper.convertValue(case.expect.output, Any::class.java)
        val difference = firstDifference("$", expected, outcome.output)
        return CaseResult(
            name = case.name,
            passed = difference == null && invariants.all { it.passed },
            diffPath = difference?.path,
            expected = difference?.let { bounded(CanonicalJson.write(it.expected)) },
            actual = difference?.let { bounded(CanonicalJson.write(it.actual)) },
            invariants = invariants,
        )
    }

    private data class Difference(
        val path: String,
        val expected: Any?,
        val actual: Any?,
    )

    /**
     * The first structural difference, numbers compared by value — the display twin of the
     * runner's private comparison (record §5.5: key order and decimal spelling never differ).
     * The gate's verdict above is the authority; this only locates what to show.
     */
    @Suppress("ReturnCount") // the recursion returns the first difference it finds
    private fun firstDifference(
        path: String,
        expected: Any?,
        actual: Any?,
    ): Difference? {
        if (expected is Map<*, *> && actual is Map<*, *>) {
            (expected.keys + actual.keys).distinct().forEach { key ->
                firstDifference("$path.$key", expected[key], actual[key])?.let { return it }
            }
            return null
        }
        if (expected is List<*> && actual is List<*>) {
            for (index in 0 until maxOf(expected.size, actual.size)) {
                firstDifference("$path[$index]", expected.getOrNull(index), actual.getOrNull(index))?.let { return it }
            }
            return null
        }
        return if (CanonicalJson.equal(expected, actual)) null else Difference(path, expected, actual)
    }

    private fun bounded(text: String): String = if (text.length <= MAX_TEXT_CHARS) text else text.take(MAX_TEXT_CHARS) + "…"

    private companion object {
        /** The record's §8.1 bound on a diff's text. */
        const val MAX_TEXT_CHARS = 2_000
    }
}

/** What a Run suite answers: the gate's verdict, its static refusals, and the per-case list. */
data class SuiteResult(
    val saveAccepted: Boolean,
    val saveCode: String?,
    val refusals: List<FaceRefusal> = emptyList(),
    val cases: List<CaseResult> = emptyList(),
) {
    val passedCount: Int get() = cases.count { it.passed }
}

/** One case's row in the result list — every string is user- or engine-supplied (`th:text` only). */
data class CaseResult(
    val name: String,
    val passed: Boolean,
    val notRun: Boolean = false,
    val diffPath: String? = null,
    val expected: String? = null,
    val actual: String? = null,
    val expectedRefusal: String? = null,
    val actualRefusal: String? = null,
    val refusalMessage: String? = null,
    val invariants: List<InvariantResult> = emptyList(),
)

/** One invariant's verdict on one case (record §9.1's `{ name, passed, message }`). */
data class InvariantResult(
    val name: String,
    val passed: Boolean,
    val message: String,
)
