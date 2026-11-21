package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.body.StructuredBodyDecoder
import co.datapipelines.typesystem.body.StructuredBodyDecoding
import co.datapipelines.typesystem.body.StructuredBodyLimits
import co.datapipelines.typesystem.body.StructuredBodyRefusalReason

/**
 * The one reading of an authored body fixture (#1038, review C1/C2), over [limits]. It applies the
 * [StructuredBodyDecoder] rules and canonical byte budget that framework execute and
 * `pipelines_execute` use, to the exact text [FixtureInputs] kept. [ScriptTestRunner] calls it
 * before any other input check.
 */
internal class BodyFixtureReader(
    private val limits: StructuredBodyLimits,
) {
    private val decoder = StructuredBodyDecoder(limits)

    /** A case's inputs with every exact body fixture decoded, or the first body refusal. */
    class Resolved(
        val inputs: Map<String, Any?>,
        val refusal: ScriptTestRunner.RunOutcome.Refused? = null,
    )

    /**
     * Each supplied `body` input that [FixtureInputs] read from a token stream is decoded from its
     * exact text and put in place of the ordinary value. The decoding checks syntax, duplicate
     * members, the root, depth, numbers and the canonical byte budget. An input given twice is
     * refused as a duplicate member. An input map built in code (no exact text) keeps its value,
     * and the runner's gate and [StructuredBodyLimits.refusalOf] still judge it. Table and value
     * inputs are untouched.
     */
    @Suppress("ReturnCount") // the first body refusal ends the case
    fun resolve(
        contract: ScriptContract,
        inputs: Map<String, Any?>,
    ): Resolved {
        val read = inputs as? FixtureInputs ?: return Resolved(inputs)
        var resolved: LinkedHashMap<String, Any?>? = null
        contract.inputs.forEach { (name, declared) ->
            if (declared !is ScriptInput.Body || !read.containsKey(name)) return@forEach
            if (read.isRepeated(name)) return Resolved(inputs, refusal(name, StructuredBodyRefusalReason.DUPLICATE_MEMBER))
            val text = read.exactText(name) ?: return@forEach
            when (val decoded = decoder.decodeFixture(text)) {
                is StructuredBodyDecoding.Decoded -> {
                    val target = resolved ?: LinkedHashMap<String, Any?>(read).also { resolved = it }
                    target[name] = decoded.body.value
                }

                is StructuredBodyDecoding.Refused -> {
                    return Resolved(inputs, refusal(name, decoded.refusal.reason))
                }
            }
        }
        return Resolved(resolved ?: inputs)
    }

    /** A body fixture refused by the shared policy: the input's name and the bounded reason word, never a value. */
    fun refusal(
        name: String,
        reason: StructuredBodyRefusalReason,
    ) = ScriptTestRunner.RunOutcome.Refused(
        PipelineErrorCodes.Script.INPUT_CONTRACT_VIOLATION,
        "input '$name': body ${reason.wire} (budget ${limits.maxBytes} bytes, depth ${limits.maxDepth})",
        details = mapOf("input" to name, "reason" to reason.wire),
    )
}
