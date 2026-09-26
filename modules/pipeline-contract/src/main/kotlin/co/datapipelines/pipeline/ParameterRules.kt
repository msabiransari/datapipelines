package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Validation
import co.datapipelines.typesystem.DeclarationRule
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.typesystem.ParameterCardinality
import co.datapipelines.typesystem.ParameterCoercion
import co.datapipelines.typesystem.ParameterValueOutcome
import co.datapipelines.typesystem.ParameterValueRule

/**
 * pipeline-contract §12.7 — parameter declarations.
 *
 * `parameter_type_invalid` is raised by [PipelineDeserializer]'s pre-scan (a wire value with
 * no typed representation — see its KDoc), as are the shape refusals of `cardinality` and
 * `constraints`; everything else is checkable here.
 *
 * `constraints` and the default are judged by the shared validator ([PipelineParameterValidator],
 * #194) — the one that judges a supplied value at execute — so a default this saves is a value
 * execute would accept.
 */
internal object ParameterRules {
    fun check(
        pipeline: Pipeline,
        into: FailureCollector,
    ) {
        pipeline.parameters.forEach { (name, parameter) ->
            checkName(name, into)
            checkPrecisionAndScale(name, parameter, into)
            checkCardinality(name, parameter, into)
            val declarationSound = checkConstraints(name, parameter, into)
            checkDefault(name, parameter, declarationSound, into)
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
     * §12.7 `cardinality_unsupported` (#194) — `MULTI` is the parameter engine's list shape and
     * part of the shared declaration contract, but a pipeline parameter binds one value until the
     * dashboard round adopts list binding (§6.2). An unknown value was refused by the pre-scan.
     */
    private fun checkCardinality(
        name: String,
        parameter: Parameter,
        into: FailureCollector,
    ) {
        val cardinality = parameter.cardinality ?: return
        if (cardinality == ParameterCardinality.SINGLE) return
        into.add(
            Validation.CARDINALITY_UNSUPPORTED,
            "parameters.${name.truncateForError()}.cardinality",
            "Parameter '${name.truncateForError()}' declares cardinality ${cardinality.wire}; a pipeline parameter is " +
                "${ParameterCardinality.SINGLE.wire} until list binding is adopted.",
            mapOf(
                "parameter" to name.truncateForError(),
                "value" to cardinality.wire,
                "supported" to listOf(ParameterCardinality.SINGLE.wire),
            ),
        )
    }

    /**
     * §12.7 `constraint_not_applicable` / `constraint_invalid` / `pattern_invalid` (#194): every
     * problem the shared validator finds in the declaration's `constraints`, all at once. True
     * when there were none — the default may then be judged against them.
     */
    private fun checkConstraints(
        name: String,
        parameter: Parameter,
        into: FailureCollector,
    ): Boolean {
        val problems = VALIDATOR.checkDeclaration(parameter.declaration)
        problems.forEach { problem ->
            into.add(
                when (problem.rule) {
                    DeclarationRule.CONSTRAINT_NOT_APPLICABLE -> Validation.CONSTRAINT_NOT_APPLICABLE
                    DeclarationRule.CONSTRAINT_INVALID -> Validation.CONSTRAINT_INVALID
                    DeclarationRule.PATTERN_INVALID -> Validation.PATTERN_INVALID
                },
                "parameters.${name.truncateForError()}.constraints.${problem.constraint}",
                "Parameter '${name.truncateForError()}': ${problem.message}.",
                mapOf(
                    "parameter" to name.truncateForError(),
                    "constraint" to problem.constraint,
                    "type" to parameter.type.wire,
                    "reason" to problem.reason,
                ),
            )
        }
        return problems.isEmpty()
    }

    /**
     * §12.7 `conflicting_required_default`, `default_type_mismatch` and `default_invalid`.
     *
     * The type check is delegated to [ParameterCoercion], the same code path that runs at
     * execution time (§6.3). Checking the JSON *type* alone — which is the letter of §12.7 —
     * would accept `{"type": "DATE", "default": "yesterday"}`: a string where a string
     * belongs, and a guaranteed `invalid_parameter_type` on the first execution that uses the
     * default. D2 says nothing invalid reaches the database, so the stricter check is the one
     * that keeps that promise.
     *
     * Since #194 the check is the shared validator's whole judgement: a default that coerces but
     * breaks the declaration's own rules — a `constraints` bound, length or pattern, or its
     * declared precision/scale — is `default_invalid`, because execute would refuse it the first
     * time it applied (`min: 0` with `default: -1`). When the constraints themselves were refused
     * ([declarationSound] false) only the type is checked; their own codes are already reported.
     */
    private fun checkDefault(
        name: String,
        parameter: Parameter,
        declarationSound: Boolean,
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
        if (!declarationSound) {
            val outcome = ParameterCoercion.coerce(parameter.type, default)
            if (outcome is ParameterCoercion.Outcome.Rejected) defaultTypeMismatch(name, parameter, outcome.reason, into)
            return
        }
        val judged = VALIDATOR.resolveDefault(parameter.declaration) as? ParameterValueOutcome.Refused ?: return
        when (judged.refusal.rule) {
            ParameterValueRule.CONSTRAINT_VIOLATION -> {
                into.add(
                    Validation.DEFAULT_INVALID,
                    "parameters.${name.truncateForError()}.default",
                    "Default for parameter '${name.truncateForError()}' breaks its declared rules: ${judged.refusal.message}.",
                    mapOf("parameter" to name.truncateForError(), "type" to parameter.type.wire, "reason" to judged.refusal.reason),
                )
            }

            ParameterValueRule.INVALID_VALUE_TYPE, ParameterValueRule.REQUIRED_MISSING -> {
                defaultTypeMismatch(name, parameter, judged.refusal.message, into)
            }
        }
    }

    private fun defaultTypeMismatch(
        name: String,
        parameter: Parameter,
        reason: String,
        into: FailureCollector,
    ) = into.add(
        Validation.DEFAULT_TYPE_MISMATCH,
        "parameters.${name.truncateForError()}.default",
        "Default for parameter '${name.truncateForError()}' does not match its declared type: $reason.",
        mapOf("parameter" to name.truncateForError(), "type" to parameter.type.wire),
    )

    private val VALIDATOR = PipelineParameterValidator.validator

    /** §12.7 — parameter keys: anchored against a leading digit, capped at 63. See [checkName]. */
    private val PARAMETER_NAME = Regex("^[a-z_][a-z0-9_]{0,62}$")
}
