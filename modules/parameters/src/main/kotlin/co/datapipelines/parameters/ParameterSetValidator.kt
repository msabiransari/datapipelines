package co.datapipelines.parameters

import co.datapipelines.dag.Dag
import co.datapipelines.pipeline.ContextKeys
import co.datapipelines.pipeline.DatasourceRegistry
import co.datapipelines.pipeline.OrgContext
import co.datapipelines.pipeline.ParameterNameGrammar
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineNameGrammar
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateDryRenderer
import co.datapipelines.pipeline.TemplateLookup
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.pipeline.TemplateVersionStatuses
import co.datapipelines.pipeline.ValidationResult
import co.datapipelines.typesystem.DeclarationRule
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.typesystem.ParameterCardinality
import co.datapipelines.typesystem.ParameterCoercion
import co.datapipelines.typesystem.ParameterValueLimits
import co.datapipelines.typesystem.ParameterValueOutcome
import co.datapipelines.typesystem.ParameterValueRule
import co.datapipelines.typesystem.ParameterValueValidator
import com.fasterxml.jackson.databind.JsonNode
import java.util.UUID

/** What [ParameterSetValidator.validate] answered. */
sealed interface ParameterSetValidation {
    /** Valid: the document in its canonical form — what is stored and hashed (expressions reprinted). */
    data class Valid(
        val document: ParameterSetDocument,
    ) : ParameterSetValidation

    /** Refused, with every failure the steps found. */
    data class Invalid(
        val result: ValidationResult,
    ) : ParameterSetValidation
}

/**
 * The save-time validator (record §4) — every write of a parameter set passes it, and nothing it
 * refuses is stored. Exhaustive: every step runs and every failure is collected, except that the dry
 * run (steps 5–6) runs only on a document that passed steps 1–4 — it touches a customer database,
 * and a document already refused does not earn a query.
 *
 * 1. **Structure** (§3.1, §3.2's field table, §3.3, §3.5, §3.7) — one code per rule; the
 *    expressions are PARSED here, so every §11 cap (parameters and options were capped by the
 *    reader; expression depth and operators, option value/label characters, label and description
 *    lengths, the pattern's 256) is enforced BEFORE the graph is built.
 * 2. **The graph** (§3.2 `depends_on`): `dependency_self`, `dependency_unknown`, then the house
 *    `Dag<T>` (`graph`, P16) refuses a cycle — `dependency_cycle` with `details.cycle`, the path
 *    the builder produces.
 * 3. **The expressions' static rules** (§7.2), against the parsed trees and the graph's names.
 * 4. **Template pins** (§6.1, §4 step 4): the pin resolves (`template_not_found` /
 *    `template_version_not_found`, a DISCARDED version included), is `type = 'sql'`, its dialect is
 *    the datasource's, the datasource is visible, no parent is interpolated (`${}` — the existing
 *    `template.validation.parameter_interpolated`), and every `:bind` resolves by namespace (P30).
 * 5–6. **The dry run** ([SelectorDryRun]) through the [SelectorProbe] port — lane C's
 *    [SelectorRunner]: the real render, binds, read-only gate and metadata execution.
 *
 * All four collaborators are `pipeline-contract` PORTS — the same ones a pipeline save validates
 * through — so this module compiles against no template engine and no datasource pool.
 */
@Suppress("LongParameterList") // the four ports of record §4 and the tier source are the constructor
class ParameterSetValidator(
    private val config: ParametersConfig,
    private val templates: TemplateDryRenderer,
    private val templateStatuses: TemplateVersionStatuses,
    private val datasources: DatasourceRegistry,
    /** Steps 5–6's dry run (record §4) — REQUIRED since lane C: [SelectorRunner] in production. */
    private val probe: SelectorProbe,
    /** The deployment's org tier — its keys are bindable without a dependency (P30). */
    private val org: OrgContext = OrgContext.DEFAULTS,
) {
    /** Judges an `INPUT`'s values: the engine's `max_length` default applies (P34 — the engine's, not a pipeline's). */
    private val inputValidator = ParameterValueValidator(config.valueLimits)

    private val definitions =
        DefinitionRules(
            config,
            inputValidator = inputValidator,
            valueValidator = ParameterValueValidator(ParameterValueLimits(maxRegexReads = config.maxRegexSteps)),
            expressionParser = ExpressionParser(config),
        )

    private val pins = PinRules(templates, templateStatuses, datasources, org.keys + (ContextKeys.PLATFORM - ContextKeys.EXECUTION_ID))

    /**
     * Record §4 in order. With [dryRun] false, steps 5–6 are left to the caller — the write path skips
     * them for a body whose hash is unchanged (record §4's last sentence) and calls [dryRun] otherwise.
     */
    fun validate(
        workspaceId: UUID,
        document: ParameterSetDocument,
        dryRun: Boolean = true,
    ): ParameterSetValidation {
        val failures = ParameterSetFailures()
        val body = document.body
        setRules(document, failures)
        val parsed = body.parameters.mapIndexed { index, parameter -> definitions.check(index, parameter, failures) }
        val graph = graph(body, failures)
        val byName = firstByName(body)
        parsed.forEachIndexed { index, expressions -> expressionRules(index, body.parameters[index], expressions, byName, failures) }
        body.parameters.forEachIndexed { index, parameter -> pins.check(workspaceId, index, parameter, byName, failures) }
        val canonical = body.copy(parameters = body.parameters.mapIndexed { index, parameter -> canonical(parameter, parsed[index]) })
        if (dryRun && failures.isEmpty &&
            graph != null
        ) {
            SelectorDryRun(probe, inputValidator, org).run(workspaceId, canonical, graph, failures)
        }
        return if (failures.isEmpty) {
            ParameterSetValidation.Valid(ParameterSetDocument(document.name, canonical))
        } else {
            ParameterSetValidation.Invalid(failures.toResult())
        }
    }

    /** Steps 5–6 alone, on a body that passed steps 1–4 ([validate] with `dryRun = false`). */
    fun dryRun(
        workspaceId: UUID,
        body: ParameterSetBody,
    ): ValidationResult {
        val failures = ParameterSetFailures()
        SelectorDryRun(probe, inputValidator, org).run(workspaceId, body, ParameterSetGraph.of(body), failures)
        return failures.toResult()
    }

    /**
     * Record §8.2/§8.3: steps 4–6 again on a STORED body, against the pins and datasources as they are
     * NOW — release re-runs them against the pinned templates' bodies (a draft template edited after
     * the set was saved is caught here), and import against the target's. The body passed steps 1–3
     * when it was written.
     */
    fun revalidateSources(
        workspaceId: UUID,
        body: ParameterSetBody,
    ): ValidationResult {
        val failures = ParameterSetFailures()
        val byName = firstByName(body)
        body.parameters.forEachIndexed { index, parameter -> pins.check(workspaceId, index, parameter, byName, failures) }
        if (failures.isEmpty) SelectorDryRun(probe, inputValidator, org).run(workspaceId, body, ParameterSetGraph.of(body), failures)
        return failures.toResult()
    }

    // ---- step 1: the set ----------------------------------------------------------------------------

    private fun setRules(
        document: ParameterSetDocument,
        failures: ParameterSetFailures,
    ) {
        if (!PipelineNameGrammar.matches(document.name)) {
            failures.add(
                ParameterErrorCodes.NAME_INVALID,
                "name",
                "Set name '${document.name.safeEcho()}' is invalid. ${PipelineNameGrammar.DESCRIPTION}",
                mapOf("name" to document.name.safeEcho(), "reason" to PipelineNameGrammar.refusalReason(document.name)),
            )
        }
        definitions.label(document.body.displayName, "display_name", failures)
        definitions.description(document.body.description, "description", failures)
        val seen = mutableSetOf<String>()
        document.body.parameters.forEachIndexed { index, parameter ->
            if (!seen.add(parameter.name)) {
                failures.add(
                    ParameterErrorCodes.DUPLICATE_PARAMETER,
                    "parameters[$index].name",
                    "Two parameters are named '${parameter.name.safeEcho()}'.",
                    mapOf("parameter" to parameter.name.safeEcho()),
                )
            }
        }
    }

    // ---- step 2: the graph ----------------------------------------------------------------------------

    /** `dependency_self`, `dependency_unknown`, then the cycle; answers the graph when it is acyclic. */
    private fun graph(
        body: ParameterSetBody,
        failures: ParameterSetFailures,
    ): Dag<ParameterDefinition>? {
        val names = body.parameters.map { it.name }.toSet()
        var dangling = false
        body.parameters.forEachIndexed { index, parameter ->
            parameter.dependsOn.forEachIndexed { d, dependency ->
                when {
                    dependency == parameter.name -> {
                        dangling = true
                        failures.add(
                            ParameterErrorCodes.DEPENDENCY_SELF,
                            "parameters[$index].depends_on[$d]",
                            "A parameter cannot depend on itself.",
                            mapOf("parameter" to dependency.safeEcho()),
                        )
                    }

                    dependency !in names -> {
                        dangling = true
                        failures.add(
                            ParameterErrorCodes.DEPENDENCY_UNKNOWN,
                            "parameters[$index].depends_on[$d]",
                            "'${dependency.safeEcho()}' names no parameter of this set.",
                            mapOf("dependency" to dependency.safeEcho()),
                        )
                    }
                }
            }
        }
        val built =
            try {
                ParameterSetGraph.of(body, onlyKnown = true)
            } catch (e: IllegalArgumentException) {
                val cycle =
                    e.message
                        .orEmpty()
                        .substringAfter(CYCLE_PREFIX, "")
                        .split(" -> ")
                        .filter { it.isNotEmpty() }
                if (cycle.isEmpty()) throw e
                failures.add(
                    ParameterErrorCodes.DEPENDENCY_CYCLE,
                    "parameters",
                    "The parameters' dependencies form a cycle: ${cycle.joinToString(" -> ") { it.safeEcho() }}.",
                    mapOf("cycle" to cycle.map { it.safeEcho() }),
                )
                null
            }
        return built.takeUnless { dangling }
    }

    // ---- step 3: the expressions' static rules --------------------------------------------------------

    private fun expressionRules(
        index: Int,
        parameter: ParameterDefinition,
        parsed: ParsedExpressions,
        byName: Map<String, ParameterDefinition>,
        failures: ParameterSetFailures,
    ) {
        listOf("hidden_expression" to parsed.hidden, "disabled_expression" to parsed.disabled).forEach { (key, expr) ->
            expr ?: return@forEach
            ExpressionChecker.check(expr, parameter, byName).forEach {
                failures.add(it.code, joinPath("parameters[$index].$key", it.path), it.message, it.details)
            }
        }
    }

    // ---- the canonical form ---------------------------------------------------------------------------

    private fun canonical(
        parameter: ParameterDefinition,
        parsed: ParsedExpressions,
    ): ParameterDefinition =
        parameter.copy(
            defaultValue = parameter.defaultValue?.takeUnless { it.isNull },
            hiddenExpression = parsed.hidden?.let(ExpressionPrinter::print) ?: parameter.hiddenExpression,
            disabledExpression = parsed.disabled?.let(ExpressionPrinter::print) ?: parameter.disabledExpression,
        )

    private companion object {
        /** The DagBuilder's refusal text (`Cycle detected: a -> b -> a`) — `details.cycle` is its path. */
        const val CYCLE_PREFIX = "Cycle detected: "

        /** Each name's FIRST definition — a duplicate is `duplicate_parameter`, and the first one wins everywhere else. */
        fun firstByName(body: ParameterSetBody): Map<String, ParameterDefinition> =
            LinkedHashMap<String, ParameterDefinition>().also { map -> body.parameters.forEach { map.putIfAbsent(it.name, it) } }
    }
}

/** The set's dependency graph over the house `Dag<T>` (P16) — the validator's step 2, and the runtime's order (lane C). */
object ParameterSetGraph {
    /**
     * Builds the graph of [body]. With [onlyKnown] the edges naming no parameter (or the parameter
     * itself) are left out — the validator reports those with their own codes and still wants the
     * cycle verdict on the rest. Throws `IllegalArgumentException("Cycle detected: a -> b -> a")` on a
     * cycle, the builder's own message.
     */
    fun of(
        body: ParameterSetBody,
        onlyKnown: Boolean = false,
    ): Dag<ParameterDefinition> {
        val first = LinkedHashMap<String, ParameterDefinition>()
        body.parameters.forEach { first.putIfAbsent(it.name, it) }
        return Dag.build {
            first.values.forEach { parameter -> addNode(parameter.name, parameter) }
            first.values.forEach { parameter ->
                parameter.dependsOn
                    .distinct()
                    .filter { !onlyKnown || (it in first && it != parameter.name) }
                    .forEach { addDependency(parameter.name, it) }
            }
        }
    }
}
