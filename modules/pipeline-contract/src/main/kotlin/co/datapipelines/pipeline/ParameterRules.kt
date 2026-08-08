package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Validation
import co.datapipelines.typesystem.LogicalType

/**
 * pipeline-contract §12.7 — parameter declarations.
 *
 * `parameter_type_invalid` is raised by [PipelineDeserializer]'s pre-scan (a wire value with
 * no typed representation — see its KDoc); everything else is checkable here.
 */
internal object ParameterRules {
    fun check(
        pipeline: Pipeline,
        into: FailureCollector,
    ) {
        pipeline.parameters.forEach { (name, parameter) ->
            checkName(name, into)
            checkPrecisionAndScale(name, parameter, into)
            checkDefault(name, parameter, into)
        }
    }

    /**
     * §12.7 `parameter_name_invalid` — every parameter key matches `[a-z_][a-z0-9_]*`, length
     * 1–63.
     *
     * **Anchored** against a leading digit (amended 2026-08-08): `[a-z0-9_]+` accepted
     * `1st_date`, and `${1st_date}` is not a legal Freemarker identifier, so such a parameter
     * saves successfully and is unusable forever after.
     *
     * **Bounded** at 63 characters, which is also a security control and not only tidiness: the
     * key comes straight from the payload's `parameters` object, and an unbounded key is a
     * megabyte of attacker text that this rule then reflects into a message, a `details` map,
     * a `path`, and every log line downstream. The cap closes that at source; CF-2 truncation
     * below is the second layer.
     */
    private fun checkName(
        name: String,
        into: FailureCollector,
    ) {
        if (PARAMETER_NAME.matches(name)) return
        into.add(
            Validation.PARAMETER_NAME_INVALID,
            "parameters.${name.truncateForError()}",
            "Parameter name '${name.truncateForError()}' must match [a-z_][a-z0-9_]*, length 1-63.",
            mapOf("parameter" to name.truncateForError()),
        )
    }

    /**
     * §12.7 `parameter_precision_missing` / `parameter_scale_missing`.
     *
     * Precision is required for `DECIMAL` only. For `BIGDECIMAL` it is **optional** — omitted
     * means *unbounded*, the same semantics type-system §4 gives a derived column, and a
     * declared parameter follows the derived-column rule (adjudicated 2026-08-08). Nothing
     * downstream needs a precision bound to coerce a `BIGDECIMAL` value: [ParameterCoercion]
     * parses the full arbitrary-precision string either way.
     *
     * Scale is required for `BIGDECIMAL` only. For `DECIMAL`, §12.7 requires it "with exact
     * semantics" and type-system §7.3 defines an omitted scale as an *approximate*-numeric
     * origin — so an omitted scale is a legal declaration, not a missing field.
     */
    private fun checkPrecisionAndScale(
        name: String,
        parameter: Parameter,
        into: FailureCollector,
    ) {
        val type = parameter.type
        if (type == LogicalType.DECIMAL && parameter.precision == null) {
            into.add(
                Validation.PARAMETER_PRECISION_MISSING,
                "parameters.${name.truncateForError()}.precision",
                "Parameter '${name.truncateForError()}' of type ${type.wire} must declare precision.",
                mapOf("parameter" to name.truncateForError(), "type" to type.wire),
            )
        }
        if (type == LogicalType.BIGDECIMAL && parameter.scale == null) {
            into.add(
                Validation.PARAMETER_SCALE_MISSING,
                "parameters.${name.truncateForError()}.scale",
                "Parameter '${name.truncateForError()}' of type ${type.wire} must declare scale.",
                mapOf("parameter" to name.truncateForError(), "type" to type.wire),
            )
        }
    }

    /**
     * §12.7 `conflicting_required_default` and `default_type_mismatch`.
     *
     * The type check is delegated to [ParameterCoercion], the same code path that runs at
     * execution time (§6.3). Checking the JSON *type* alone — which is the letter of §12.7 —
     * would accept `{"type": "DATE", "default": "yesterday"}`: a string where a string
     * belongs, and a guaranteed `invalid_parameter_type` on the first execution that uses the
     * default. D2 says nothing invalid reaches the database, so the stricter check is the one
     * that keeps that promise.
     */
    private fun checkDefault(
        name: String,
        parameter: Parameter,
        into: FailureCollector,
    ) {
        if (!parameter.hasDefault) return
        val default = parameter.default ?: return
        if (parameter.required) {
            into.add(
                Validation.CONFLICTING_REQUIRED_DEFAULT,
                "parameters.${name.truncateForError()}",
                "Parameter '${name.truncateForError()}' is required and also declares a default; " +
                    "a default is only honoured when required is false.",
                mapOf("parameter" to name.truncateForError()),
            )
        }
        val outcome = ParameterCoercion.coerce(parameter.type, default)
        if (outcome is ParameterCoercion.Outcome.Rejected) {
            into.add(
                Validation.DEFAULT_TYPE_MISMATCH,
                "parameters.${name.truncateForError()}.default",
                "Default for parameter '${name.truncateForError()}' does not match its declared type: ${outcome.reason}.",
                mapOf("parameter" to name.truncateForError(), "type" to parameter.type.wire),
            )
        }
    }

    /** §12.7 — parameter keys: anchored against a leading digit, capped at 63. See [checkName]. */
    private val PARAMETER_NAME = Regex("^[a-z_][a-z0-9_]{0,62}$")
}
