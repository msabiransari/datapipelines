package co.datapipelines.parameters

import co.datapipelines.pipeline.ParameterNameGrammar
import co.datapipelines.typesystem.DeclarationRule
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.typesystem.ParameterCardinality
import co.datapipelines.typesystem.ParameterCoercion
import co.datapipelines.typesystem.ParameterValueOutcome
import co.datapipelines.typesystem.ParameterValueRule
import co.datapipelines.typesystem.ParameterValueValidator
import com.fasterxml.jackson.databind.JsonNode

/**
 * Record §4 step 1 for ONE parameter — §3.2's field table, §3.3's constants, §3.5's constraints (the
 * shared validator judges them), §3.7's presentation — with the expressions PARSED here so every
 * expression cap is enforced before the graph is built. Split from [ParameterSetValidator] along the
 * record's own step line; the orchestration and the ordering stay there.
 */
@Suppress("TooManyFunctions") // one function per rule family of §3.2
internal class DefinitionRules(
    private val config: ParametersConfig,
    /** Judges an `INPUT`'s values: the engine's `max_length` default applies (P34 — the engine's, not a pipeline's). */
    private val inputValidator: ParameterValueValidator,
    /** Judges option values and a `SELECT`'s default: no length default — an option has its own cap. */
    private val valueValidator: ParameterValueValidator,
    private val expressionParser: ExpressionParser,
) {
    /** The §3.2 field rules; answers the parameter's parsed expressions (null where absent or refused). */
    fun check(
        index: Int,
        parameter: ParameterDefinition,
        failures: ParameterSetFailures,
    ): ParsedExpressions {
        val at = "parameters[$index]"
        name(parameter.name, "$at.name", failures)
        label(parameter.label, "$at.label", failures)
        description(parameter.description, "$at.description", failures)
        if (parameter.type == LogicalType.DECIMAL && parameter.precision == null) {
            failures.add(
                ParameterErrorCodes.PRECISION_MISSING,
                "$at.precision",
                "A DECIMAL parameter must declare precision.",
                detailsOf(parameter),
            )
        }
        if (parameter.type == LogicalType.BIGDECIMAL && parameter.scale == null) {
            failures.add(ParameterErrorCodes.SCALE_MISSING, "$at.scale", "A BIGDECIMAL parameter must declare scale.", detailsOf(parameter))
        }
        if (parameter.kind == ParameterKind.INPUT && parameter.cardinality == ParameterCardinality.MULTI) {
            failures.add(
                ParameterErrorCodes.CARDINALITY_INVALID,
                "$at.cardinality",
                "An INPUT is always SINGLE (P23a) — a multi-value choice is a SELECT.",
                detailsOf(parameter) + ("reason" to "input_single"),
            )
        }
        sourceRules(at, parameter, failures)
        val declarationSound = constraintRules(at, parameter, failures)
        val options = constantsRules(at, parameter, failures)
        defaultRules(at, parameter, declarationSound, options, failures)
        PresentationCatalogue.check(parameter).forEach {
            failures.add(
                it.code,
                "$at.presentation.${it.path}",
                it.message,
                it.details + detailsOf(parameter),
            )
        }
        return ParsedExpressions(
            hidden = parameter.hiddenExpression?.let { parse(it, "$at.hidden_expression", failures) },
            disabled = parameter.disabledExpression?.let { parse(it, "$at.disabled_expression", failures) },
        )
    }

    private fun name(
        name: String,
        path: String,
        failures: ParameterSetFailures,
    ) {
        when {
            !ParameterNameGrammar.matches(name) -> {
                failures.add(
                    ParameterErrorCodes.NAME_INVALID,
                    path,
                    "Parameter name '${name.safeEcho()}' must match ${ParameterNameGrammar.pattern} (pipeline-contract §6.1).",
                    mapOf("parameter" to name.safeEcho(), "reason" to "grammar"),
                )
            }

            name.endsWith(COUNT_SUFFIX) || SLICE_BIND.matches(name) -> {
                failures.add(
                    ParameterErrorCodes.NAME_RESERVED,
                    path,
                    "'${name.safeEcho()}' is reserved: `<name>_count` and `<name>__<digits>` are the binds a MULTI generates (P29).",
                    mapOf("parameter" to name.safeEcho(), "reason" to if (SLICE_BIND.matches(name)) "slice_suffix" else "count_suffix"),
                )
            }
        }
    }

    fun label(
        label: String,
        path: String,
        failures: ParameterSetFailures,
    ) {
        if (label.isBlank() || label.length > MAX_LABEL_CHARS) {
            failures.add(
                ParameterErrorCodes.LABEL_INVALID,
                path,
                "A label is 1-$MAX_LABEL_CHARS characters and not blank; got ${label.length}.",
                mapOf("length" to label.length, "max" to MAX_LABEL_CHARS),
            )
        }
    }

    fun description(
        description: String?,
        path: String,
        failures: ParameterSetFailures,
    ) {
        if (description != null && description.length > MAX_DESCRIPTION_CHARS) {
            failures.add(
                ParameterErrorCodes.DESCRIPTION_TOO_LONG,
                path,
                "A description is at most $MAX_DESCRIPTION_CHARS characters; got ${description.length}.",
                mapOf("length" to description.length, "max" to MAX_DESCRIPTION_CHARS),
            )
        }
    }

    /** §3.2 `source` (and §3.4's pin pair). */
    private fun sourceRules(
        at: String,
        parameter: ParameterDefinition,
        failures: ParameterSetFailures,
    ) {
        val source = parameter.source
        when {
            source == null && parameter.kind == ParameterKind.SELECT -> {
                failures.add(
                    ParameterErrorCodes.SOURCE_MISSING,
                    "$at.source",
                    "A SELECT needs a source: constants or a template.",
                    detailsOf(parameter),
                )
            }

            source == null -> {
                // An INPUT with no source is hard-coded (its default_value): nothing to check here.
            }

            source.constants != null && parameter.kind == ParameterKind.INPUT -> {
                failures.add(
                    ParameterErrorCodes.SOURCE_NOT_ALLOWED,
                    "$at.source.constants",
                    "An INPUT takes no constants — its hard-coded value is default_value.",
                    detailsOf(parameter),
                )
            }

            source.kind == null -> {
                failures.add(
                    ParameterErrorCodes.SOURCE_AMBIGUOUS,
                    "$at.source",
                    "A source is exactly one of {constants} or {template, datasource}.",
                    detailsOf(parameter) +
                        (
                            "keys" to
                                listOfNotNull(
                                    source.constants?.let { "constants" },
                                    source.template?.let { "template" },
                                    source.datasource?.let { "datasource" },
                                )
                        ),
                )
            }

            source.kind == SelectorSourceKind.TEMPLATE && source.datasource == null -> {
                failures.add(
                    ParameterErrorCodes.SOURCE_AMBIGUOUS,
                    "$at.source.datasource",
                    "A template source needs its datasource.",
                    detailsOf(parameter),
                )
            }
        }
    }

    /** §3.5: `INPUT` only; the shared validator judges the declaration. True when the default may be judged by it. */
    private fun constraintRules(
        at: String,
        parameter: ParameterDefinition,
        failures: ParameterSetFailures,
    ): Boolean {
        parameter.constraints ?: return true
        if (parameter.kind == ParameterKind.SELECT) {
            failures.add(
                ParameterErrorCodes.CONSTRAINTS_ON_SELECT,
                "$at.constraints",
                "Constraints belong to an INPUT; a SELECT's values are its options.",
                detailsOf(parameter),
            )
            return false
        }
        val problems = inputValidator.checkDeclaration(parameter.declaration.copy(default = null))
        problems.forEach { problem ->
            failures.add(
                when (problem.rule) {
                    DeclarationRule.CONSTRAINT_NOT_APPLICABLE -> ParameterErrorCodes.CONSTRAINT_NOT_APPLICABLE
                    DeclarationRule.CONSTRAINT_INVALID -> ParameterErrorCodes.CONSTRAINT_INVALID
                    DeclarationRule.PATTERN_INVALID -> ParameterErrorCodes.PATTERN_INVALID
                },
                "$at.constraints.${problem.constraint}",
                "Parameter '${parameter.name.safeEcho()}': ${problem.message}.",
                detailsOf(parameter) + mapOf("constraint" to problem.constraint, "reason" to problem.reason),
            )
        }
        return problems.isEmpty()
    }

    /** §3.3 — the constants' invariants; answers the ACCEPTED option values (canonical), for the default check. */
    private fun constantsRules(
        at: String,
        parameter: ParameterDefinition,
        failures: ParameterSetFailures,
    ): List<Any>? {
        val options = parameter.source?.constants ?: return null
        if (parameter.kind == ParameterKind.INPUT) return null
        val path = "$at.source.constants"
        if (options.isEmpty()) {
            failures.add(
                ParameterErrorCodes.TOO_MANY_OPTIONS,
                path,
                "A constants list holds at least one option.",
                detailsOf(parameter) + ("reason" to "empty"),
            )
            return emptyList()
        }
        val single =
            parameter.declaration.copy(
                cardinality = ParameterCardinality.SINGLE,
                required = false,
                default = null,
                constraints = null,
            )
        val accepted = mutableListOf<Any>()
        options.forEachIndexed { index, option -> optionRules(parameter, single, option, "$path[$index]", accepted, failures) }
        val defaults = options.count { it.isDefault }
        if (defaults > 1) {
            failures.add(
                ParameterErrorCodes.MULTIPLE_DEFAULTS,
                path,
                "$defaults options are marked is_default; at most one may be (P7).",
                detailsOf(parameter) + ("count" to defaults),
            )
        }
        return accepted
    }

    /** One option's invariants (§3.3): typed by the shared validator, bounded, labelled; an accepted value joins [accepted]. */
    @Suppress("LongParameterList") // the option, its declaration, its path and the two accumulators
    private fun optionRules(
        parameter: ParameterDefinition,
        single: co.datapipelines.typesystem.ParameterDeclaration,
        option: ConstantOption,
        optionPath: String,
        accepted: MutableList<Any>,
        failures: ParameterSetFailures,
    ) {
        val wireChars = if (option.value.isTextual) option.value.asText().length else option.value.toString().length
        if (wireChars > config.maxOptionValueChars) {
            failures.add(
                ParameterErrorCodes.OPTION_INVALID,
                "$optionPath.value",
                "An option value is at most ${config.maxOptionValueChars} characters.",
                mapOf("reason" to "value_too_long"),
            )
        } else {
            when (val outcome = valueValidator.validate(single, option.value)) {
                is ParameterValueOutcome.Accepted -> {
                    duplicateOrKeep(outcome.value, accepted, "$optionPath.value", failures)
                }

                is ParameterValueOutcome.Refused -> {
                    failures.add(
                        ParameterErrorCodes.OPTION_INVALID,
                        "$optionPath.value",
                        "An option value is not a ${parameter.type.wire}: ${outcome.refusal.message}.",
                        mapOf("reason" to (outcome.refusal.reason ?: "type"), "type" to parameter.type.wire),
                    )
                }

                ParameterValueOutcome.Unsupplied -> {
                    failures.add(
                        ParameterErrorCodes.OPTION_INVALID,
                        "$optionPath.value",
                        "An option value is never null.",
                        mapOf("reason" to "null_value"),
                    )
                }
            }
        }
        optionLabelRules(option.displayValue, "$optionPath.display_value", failures)
    }

    private fun optionLabelRules(
        label: String,
        path: String,
        failures: ParameterSetFailures,
    ) {
        if (label.isBlank()) {
            failures.add(
                ParameterErrorCodes.OPTION_INVALID,
                path,
                "An option needs a non-empty display_value.",
                mapOf("reason" to "empty_label"),
            )
        } else if (label.length > config.maxOptionLabelChars) {
            failures.add(
                ParameterErrorCodes.OPTION_INVALID,
                path,
                "A display_value is at most ${config.maxOptionLabelChars} characters.",
                mapOf("reason" to "label_too_long"),
            )
        }
    }

    private fun duplicateOrKeep(
        value: Any,
        accepted: MutableList<Any>,
        path: String,
        failures: ParameterSetFailures,
    ) {
        if (accepted.any { ExpressionEvaluator.canonicalEquals(it, value) }) {
            failures.add(ParameterErrorCodes.OPTION_DUPLICATE, path, "Two options share this value — option values are unique.", emptyMap())
        } else {
            accepted += value
        }
    }

    /**
     * §3.2 `default_value`: the full shared-validator check (type, precision/scale, the INPUT's own
     * constraints — `min: 0` with `-1` is `default_invalid`), and for a `constants` select, every
     * default member among the options. A template select's default is checked at evaluate (its
     * options depend on its parents).
     */
    private fun defaultRules(
        at: String,
        parameter: ParameterDefinition,
        declarationSound: Boolean,
        options: List<Any>?,
        failures: ParameterSetFailures,
    ) {
        val default = parameter.defaultValue?.takeUnless { it.isNull } ?: return
        val path = "$at.default_value"
        if (!declarationSound) {
            if (!coercible(parameter, default)) {
                failures.add(
                    ParameterErrorCodes.DEFAULT_TYPE_MISMATCH,
                    path,
                    "default_value is not a ${describeDeclared(parameter)} wire value.",
                    detailsOf(parameter),
                )
            }
            return
        }
        val validator = if (parameter.kind == ParameterKind.INPUT) inputValidator else valueValidator
        when (val outcome = validator.validate(parameter.declaration.copy(required = false, default = null), default)) {
            is ParameterValueOutcome.Refused -> {
                if (outcome.refusal.rule == ParameterValueRule.CONSTRAINT_VIOLATION) {
                    failures.add(
                        ParameterErrorCodes.DEFAULT_INVALID,
                        path,
                        "default_value breaks the parameter's own rules: ${outcome.refusal.message}.",
                        detailsOf(parameter) + ("reason" to outcome.refusal.reason),
                    )
                } else {
                    failures.add(
                        ParameterErrorCodes.DEFAULT_TYPE_MISMATCH,
                        path,
                        "default_value is not a ${describeDeclared(parameter)} wire value: ${outcome.refusal.message}.",
                        detailsOf(parameter),
                    )
                }
            }

            is ParameterValueOutcome.Accepted -> {
                if (options == null || options.isEmpty()) return
                val members = if (parameter.cardinality == ParameterCardinality.MULTI) outcome.value as List<*> else listOf(outcome.value)
                val missing = members.filter { member -> options.none { ExpressionEvaluator.canonicalEquals(it, member) } }
                if (missing.isNotEmpty()) {
                    failures.add(
                        ParameterErrorCodes.DEFAULT_NOT_AN_OPTION,
                        path,
                        "default_value names ${missing.size} value(s) that are not among the options.",
                        detailsOf(parameter) + ("missing" to missing.size),
                    )
                }
            }

            ParameterValueOutcome.Unsupplied -> {
                // `[]` for a MULTI is "nothing chosen" (P25): no default.
            }
        }
    }

    /** The wire shape alone, when the constraints themselves were refused (their own codes are reported). */
    private fun coercible(
        parameter: ParameterDefinition,
        default: JsonNode,
    ): Boolean =
        if (parameter.cardinality == ParameterCardinality.MULTI) {
            default.isArray && default.all { ParameterCoercion.coerce(parameter.type, it) is ParameterCoercion.Outcome.Coerced }
        } else {
            ParameterCoercion.coerce(parameter.type, default) is ParameterCoercion.Outcome.Coerced
        }

    private fun parse(
        node: JsonNode,
        path: String,
        failures: ParameterSetFailures,
    ): Expr? =
        when (val parse = expressionParser.parse(node)) {
            is ExpressionParse.Parsed -> {
                parse.expr
            }

            is ExpressionParse.Refused -> {
                parse.problems.forEach { failures.add(it.code, joinPath(path, it.path), it.message, it.details) }
                null
            }
        }
}
