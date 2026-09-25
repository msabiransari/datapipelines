package co.datapipelines.pipeline

import co.datapipelines.calculators.CalculatorInput
import co.datapipelines.calculators.CalculatorKind
import co.datapipelines.calculators.CalculatorRegistry
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * 078 A5-composition — the read-surface projection of the pipeline's **calculator output
 * declared set** ([Pipeline.calculatorOutputs]).
 *
 * A calculator `context_key` is an implicit OPTIONAL execute input (078 A5, owner ruling
 * 2026-09-05), so a surface that lists the pipeline's `parameters` must list these too, or an
 * agent reading `pipelines_get` / `GET /pipelines/{id}` cannot discover what it is allowed to
 * supply. Each key merges one entry into the body's `parameters` object, in the declared
 * parameters' own shape plus the marker: `{"type": <wire>, "required": false, "derived": true}`.
 * `type` is the kind output's wire type, or `"ANY"` ([CalculatorInput.ANY_TYPE]) for an
 * ANY-output kind; declared parameters carry no `derived` flag (absence IS the false).
 *
 * Derived, never stored: the merge happens on the response tree after the body is read, so
 * `body_json` keeps holding exactly the portable §3 body and a get→edit→update round trip
 * cannot persist a derived entry as a declaration. A key colliding with a declared parameter
 * is skipped — the collision is §12.10's save-time refusal, and the parameter owns its name.
 */
object DerivedInputs {
    /**
     * Merges one derived entry per calculator Context key into [body]'s `parameters` object —
     * a multi-output node contributes one per mapped output (121), each typed by its output.
     *
     * [kinds] is the registry lookup, defaulted so production callers pass nothing; a test
     * injects a fixture kind through it.
     */
    fun mergeInto(
        body: ObjectNode,
        kinds: (String) -> CalculatorKind? = CalculatorRegistry::find,
    ) {
        val pipeline = PipelineJson.objectMapper().treeToValue(body, Pipeline::class.java)
        // 7c (#7): a value-mode TRANSFORM's `context_key` is the same implicit optional input,
        // typed ANY here — the contract that types it is the pinned template's, not the body's.
        val outputs = pipeline.calculatorOutputs(kinds) + pipeline.transformOutputKeys().associateWith { null }
        if (outputs.isEmpty()) return
        val parameters = body.get("parameters") as? ObjectNode ?: body.putObject("parameters")
        outputs.forEach { (key, type) ->
            if (parameters.has(key)) return@forEach
            parameters
                .putObject(key)
                .put("type", type?.wire ?: CalculatorInput.ANY_TYPE)
                .put("required", false)
                .put("derived", true)
        }
    }
}
