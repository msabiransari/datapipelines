package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.scripting.ConsoleLine
import co.datapipelines.typesystem.body.StructuredBodyDiagnostics

/**
 * The default diagnostics policy for an authored body fixture (#1038, review C3): what a case that
 * supplies the contract's `body` input may disclose, on evaluation and in the save/release suite.
 *
 * A refusal keeps its code and only the safe detail keys ([StructuredBodyDiagnostics.SAFE_DETAILS]),
 * and its message becomes the code. Console lines keep their level and lose their text. An
 * evaluated case keeps its declared output, its rejects and each invariant's name and verdict, and
 * loses the invariant's authored message, as the executor's failure record does. A failing suite
 * case is named by its case, a fixed rule word and, for a refusal, the refusal's `reason` word. It
 * never carries an output, an expected or actual value, the run's message or an invariant's text.
 * [ScriptTestRunner] applies this policy. A case without a body input keeps every message it had.
 */
object BodyFixtureDiagnostics {
    /** A body fixture's console and invariant message text: level, count, name and verdict stay; text does not. */
    const val WITHHELD_TEXT = "withheld"

    /** The bounded verdict's rule words for a failing body case. */
    const val RULE_WRONG_REFUSAL = "wrong_refusal"
    const val RULE_UNEXPECTED_SUCCESS = "unexpected_success"
    const val RULE_UNEXPECTED_REFUSAL = "unexpected_refusal"
    const val RULE_INVARIANT_INVALID = "invariant_invalid"
    const val RULE_OUTPUT_DIFFERS = "output_differs"
    const val RULE_INVARIANT_FALSE = "invariant_false"

    /** True when [inputs] supplies the contract's `body` input: the case is a body fixture. */
    internal fun isBodyCase(
        contract: ScriptContract,
        inputs: Map<String, Any?>,
    ): Boolean = contract.inputs.any { (name, declared) -> declared is ScriptInput.Body && inputs.containsKey(name) }

    /** [outcome] under the policy (the object KDoc). */
    internal fun bounded(outcome: ScriptTestRunner.RunOutcome): ScriptTestRunner.RunOutcome =
        when (outcome) {
            is ScriptTestRunner.RunOutcome.Refused -> {
                ScriptTestRunner.RunOutcome.Refused(
                    code = outcome.code,
                    message = outcome.code,
                    console = withheld(outcome.console),
                    details = StructuredBodyDiagnostics.bounded(outcome.details),
                )
            }

            is ScriptTestRunner.RunOutcome.Evaluated -> {
                outcome.copy(
                    invariants = outcome.invariants.map { it.copy(message = WITHHELD_TEXT) },
                    console = withheld(outcome.console),
                )
            }
        }

    /** The suite verdict for a body case: the runner's pass/fail decisions, worded by the policy. */
    internal fun verdictOf(
        case: ScriptTestCase,
        outcome: ScriptTestRunner.RunOutcome,
    ): List<TemplateValidationFailure> {
        val (rule, assertion) = failureOf(case, outcome) ?: return emptyList()
        val code =
            if (rule == RULE_INVARIANT_INVALID) PipelineErrorCodes.Template.INVARIANT_INVALID else PipelineErrorCodes.Template.TEST_FAILED
        // The refusal's reason is a fixed word (duplicate_member, too_deep, …): safe to show, and the author's clue.
        val reason =
            (outcome as? ScriptTestRunner.RunOutcome.Refused)
                ?.details
                ?.get("reason")
                ?.let { mapOf("reason" to it) }
                .orEmpty()
        return listOf(
            TemplateValidationFailure(
                code = code,
                message = "Test case '${case.name}' failed: $assertion",
                details = mapOf("case" to case.name, "rule" to rule) + reason,
            ),
        )
    }

    private fun withheld(lines: List<ConsoleLine>): List<ConsoleLine> = lines.map { ConsoleLine(it.level, WITHHELD_TEXT) }

    /** The rule word and assertion of a failing body case, or null when it holds. */
    private fun failureOf(
        case: ScriptTestCase,
        outcome: ScriptTestRunner.RunOutcome,
    ): Pair<String, String>? {
        val refusal = case.expect.refusal
        return when {
            refusal != null && outcome is ScriptTestRunner.RunOutcome.Refused -> {
                (RULE_WRONG_REFUSAL to "expected refusal '$refusal' but the run refused with '${outcome.code}'")
                    .takeUnless { outcome.code == refusal }
            }

            refusal != null -> {
                RULE_UNEXPECTED_SUCCESS to "expected refusal '$refusal' but the run succeeded"
            }

            outcome is ScriptTestRunner.RunOutcome.Refused && outcome.code == PipelineErrorCodes.Template.INVARIANT_INVALID -> {
                RULE_INVARIANT_INVALID to "an invariant did not produce a boolean"
            }

            outcome is ScriptTestRunner.RunOutcome.Refused -> {
                RULE_UNEXPECTED_REFUSAL to "expected an output but the run refused with '${outcome.code}'"
            }

            else -> {
                val evaluated = outcome as ScriptTestRunner.RunOutcome.Evaluated
                val expected = ScriptBlocks.mapper.convertValue(case.expect.output, Any::class.java)
                when {
                    OutputDifference.first("$", expected, evaluated.output) != null -> {
                        RULE_OUTPUT_DIFFERS to "the output differs from the expectation"
                    }

                    evaluated.invariants.any { !it.passed } -> {
                        RULE_INVARIANT_FALSE to "an invariant is false"
                    }

                    else -> {
                        null
                    }
                }
            }
        }
    }
}
