package co.datapipelines.parameters

import co.datapipelines.dag.Dag
import co.datapipelines.pipeline.ContextKeys
import co.datapipelines.pipeline.OrgContext
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.typesystem.ParameterCardinality
import co.datapipelines.typesystem.ParameterCoercion
import co.datapipelines.typesystem.ParameterValueOutcome
import co.datapipelines.typesystem.ParameterValueRule
import co.datapipelines.typesystem.ParameterValueValidator
import co.datapipelines.typesystem.ParameterWireEncoder
import co.datapipelines.typesystem.TypeDescriptor
import co.datapipelines.typesystem.TypeWidening
import java.math.BigDecimal
import java.math.BigInteger
import java.util.UUID

/**
 * Record §4 steps 5 and 6 — the dry run of every template-backed source, through the
 * [SelectorProbe] port, in the graph's topological order so each parameter renders against its
 * parents' defaults ("top-down, defaults where present, type-appropriate samples otherwise"):
 *
 * - **5. Render** against `{parents' values} ∪ {<name>_count per MULTI parent} ∪ org ∪ platform` —
 *   a MULTI parent's list never reaches the template, its size does (P29); a failure is
 *   `template_render_failed`.
 * - **6. Run it** with `maxRows = 2`, the parents bound as binds: an unreachable datasource is
 *   `datasource_unreachable`, a failing statement `selector_query_failed`. A `SELECT` must return
 *   exactly `value`, `display_value`, `is_default` (`selector_columns_invalid`), `value`'s type must
 *   pass §6.4 from the METADATA (`selector_value_type_mismatch`), and its rendered SQL must carry an
 *   `ORDER BY` (`selector_order_by_missing`). An `INPUT` returns exactly `value`, at most one row
 *   (`input_source_multiple_rows`, owner ruling 2026-09-27), and that row must pass the input's own
 *   rules (`default_invalid` — "a sourced row at save's dry run").
 *
 * A parent's default for its children: `default_value`, else the marked option, else the first
 * option — for a template parent, the probe's `is_default` row, else its first row — else a type
 * sample ([TypeSamples], the pipelines' one table). Execute-time values (`execution_id`) are absent.
 */
internal class SelectorDryRun(
    private val probe: SelectorProbe?,
    private val inputValidator: ParameterValueValidator,
    private val org: OrgContext,
) {
    fun run(
        workspaceId: UUID,
        body: ParameterSetBody,
        graph: Dag<ParameterDefinition>,
        failures: ParameterSetFailures,
    ) {
        val indexOf = LinkedHashMap<String, Int>().also { map -> body.parameters.forEachIndexed { i, p -> map.putIfAbsent(p.name, i) } }
        val resolved = HashMap<String, Any?>()
        graph.topologicalOrder().forEach { name ->
            val parameter = graph.node(name)
            resolved[name] =
                if (parameter.source?.kind == SelectorSourceKind.TEMPLATE) {
                    dryRun(workspaceId, parameter, "parameters[${indexOf.getValue(name)}].source", resolved, graph, failures)
                } else {
                    staticDefault(parameter)
                }
        }
    }

    @Suppress("ReturnCount", "LongParameterList") // one early answer per outcome of the probe; the walk's state is passed in
    private fun dryRun(
        workspaceId: UUID,
        parameter: ParameterDefinition,
        at: String,
        resolved: Map<String, Any?>,
        graph: Dag<ParameterDefinition>,
        failures: ParameterSetFailures,
    ): Any? {
        val source = checkNotNull(parameter.source)
        val ref = checkNotNull(source.template)
        val datasource = checkNotNull(source.datasource)
        val probe =
            this.probe ?: run {
                failures.add(
                    ParameterErrorCodes.SELECTOR_PROBE_UNAVAILABLE,
                    "$at.template",
                    "A template-backed source is proven by a dry run this build cannot perform yet (#194 lane C); " +
                        "use constants, or save once the selector runtime ships.",
                    mapOf("parameter" to parameter.name.safeEcho()),
                )
                return staticDefault(parameter)
            }
        val (context, binds) = contextFor(parameter, resolved, graph)
        val sql =
            when (val render = probe.render(workspaceId, ref, context)) {
                is SelectorRender.Rendered -> {
                    render.sql
                }

                is SelectorRender.Failed -> {
                    failures.add(
                        ParameterErrorCodes.TEMPLATE_RENDER_FAILED,
                        "$at.template",
                        "The selector template did not render against its parents' defaults: ${render.detail.safeEcho(MAX_DETAIL)}",
                        mapOf(
                            "parameter" to parameter.name.safeEcho(),
                            "template_id" to ref.id.safeEcho(),
                            "template_version" to ref.version,
                        ),
                    )
                    return staticDefault(parameter)
                }
            }
        return probed(parameter, at, datasource, sql, probe.probe(workspaceId, datasource, sql, binds), failures)
    }

    /** Step 6's verdict on what the probe answered. */
    private fun probed(
        parameter: ParameterDefinition,
        at: String,
        datasource: String,
        sql: String,
        outcome: SelectorProbeOutcome,
        failures: ParameterSetFailures,
    ): Any? =
        when (outcome) {
            is SelectorProbeOutcome.Unreachable -> {
                failures.add(
                    ParameterErrorCodes.DATASOURCE_UNREACHABLE,
                    "$at.datasource",
                    "Datasource '${datasource.safeEcho()}' could not be reached to prove the selector: ${outcome.detail.safeEcho(
                        MAX_DETAIL,
                    )}",
                    mapOf("parameter" to parameter.name.safeEcho(), "datasource" to datasource.safeEcho()),
                )
                staticDefault(parameter)
            }

            is SelectorProbeOutcome.StatementFailed -> {
                failures.add(
                    ParameterErrorCodes.SELECTOR_QUERY_FAILED,
                    "$at.template",
                    "The selector's statement failed on '${datasource.safeEcho()}': ${outcome.detail.safeEcho(MAX_DETAIL)}",
                    mapOf(
                        "parameter" to parameter.name.safeEcho(),
                        "datasource" to datasource.safeEcho(),
                        "datasource_code" to outcome.datasourceCode,
                    ),
                )
                staticDefault(parameter)
            }

            is SelectorProbeOutcome.Probed -> {
                if (parameter.kind ==
                    ParameterKind.SELECT
                ) {
                    selectShape(parameter, at, sql, outcome, failures)
                } else {
                    inputShape(parameter, at, outcome, failures)
                }
            }
        }

    /** The render context (parents, their counts, the tiers) and the binds (parents' values — MULTI lists included — and the tiers). */
    private fun contextFor(
        parameter: ParameterDefinition,
        resolved: Map<String, Any?>,
        graph: Dag<ParameterDefinition>,
    ): Pair<Map<String, Any?>, Map<String, Any?>> {
        val context = LinkedHashMap<String, Any?>(org.values)
        context[ContextKeys.CURRENT_DATE] = TypeSamples.of(LogicalType.DATE)
        context[ContextKeys.CURRENT_TIMESTAMP] = TypeSamples.of(LogicalType.TIMESTAMP)
        val binds = LinkedHashMap<String, Any?>(context)
        parameter.dependsOn.distinct().forEach { dependency ->
            val value = resolved[dependency]
            if (graph.node(dependency).cardinality == ParameterCardinality.MULTI) {
                val list = (value as? List<*>) ?: listOfNotNull(value)
                context["${dependency}_count"] = list.size
                binds["${dependency}_count"] = list.size
                binds[dependency] = list
            } else {
                context[dependency] = value
                binds[dependency] = value
            }
        }
        return context to binds
    }

    @Suppress("ReturnCount")
    private fun selectShape(
        parameter: ParameterDefinition,
        at: String,
        sql: String,
        probed: SelectorProbeOutcome.Probed,
        failures: ParameterSetFailures,
    ): Any? {
        val columns = columnsOrRefuse(parameter, at, probed.columns, SELECT_COLUMNS, failures) ?: return staticDefault(parameter)
        val value = columns.getValue(VALUE)
        val typeOk = valueType(parameter, at, value, failures)
        columns.getValue(DISPLAY_VALUE).takeIf { it.type != LogicalType.STRING }?.let {
            columnType(parameter, at, DISPLAY_VALUE, LogicalType.STRING, it, failures)
        }
        columns.getValue(IS_DEFAULT).takeIf { it.type != LogicalType.BOOLEAN }?.let {
            columnType(parameter, at, IS_DEFAULT, LogicalType.BOOLEAN, it, failures)
        }
        if (!ORDER_BY.containsMatchIn(SqlClauseText.blanked(sql))) {
            failures.add(
                ParameterErrorCodes.SELECTOR_ORDER_BY_MISSING,
                "$at.template",
                "A SELECT's options need an ORDER BY — the first option is the first row the database returns (P7).",
                mapOf("parameter" to parameter.name.safeEcho()),
            )
        }
        if (!typeOk || probed.rows.isEmpty()) return staticDefault(parameter)
        val valueIndex = probed.columns.indexOf(value)
        val defaultIndex = probed.columns.indexOf(columns.getValue(IS_DEFAULT))
        val row = probed.rows.firstOrNull { it.getOrNull(defaultIndex) == true } ?: probed.rows.first()
        val chosen = toDeclared(row.getOrNull(valueIndex), value.type, parameter.type) ?: return staticDefault(parameter)
        return if (parameter.cardinality == ParameterCardinality.MULTI) listOf(chosen) else chosen
    }

    @Suppress("ReturnCount")
    private fun inputShape(
        parameter: ParameterDefinition,
        at: String,
        probed: SelectorProbeOutcome.Probed,
        failures: ParameterSetFailures,
    ): Any? {
        val columns = columnsOrRefuse(parameter, at, probed.columns, INPUT_COLUMNS, failures) ?: return staticDefault(parameter)
        val value = columns.getValue(VALUE)
        if (!valueType(parameter, at, value, failures)) return staticDefault(parameter)
        if (probed.rows.size > 1) {
            failures.add(
                ParameterErrorCodes.INPUT_SOURCE_MULTIPLE_ROWS,
                "$at.template",
                "A database-fed INPUT's template returns at most one row; it returned ${probed.rows.size} against its parents' defaults.",
                mapOf("parameter" to parameter.name.safeEcho(), "rows" to probed.rows.size),
            )
            return staticDefault(parameter)
        }
        val row = probed.rows.singleOrNull() ?: return staticDefault(parameter)
        val typed = toDeclared(row.firstOrNull(), value.type, parameter.type) ?: return staticDefault(parameter)
        val judged =
            inputValidator.validate(
                parameter.declaration.copy(required = false, default = null),
                ParameterWireEncoder.encode(parameter.type, typed),
            )
        if (judged is ParameterValueOutcome.Refused) {
            val constraint = judged.refusal.rule == ParameterValueRule.CONSTRAINT_VIOLATION
            failures.add(
                if (constraint) ParameterErrorCodes.DEFAULT_INVALID else ParameterErrorCodes.SELECTOR_VALUE_TYPE_MISMATCH,
                "$at.template",
                "The row the source returned at the dry run breaks the input's own rules: ${judged.refusal.message}.",
                mapOf("parameter" to parameter.name.safeEcho(), "reason" to judged.refusal.reason, "source" to "row"),
            )
            return staticDefault(parameter)
        }
        return typed
    }

    /** The probed columns by lower-cased name when they are EXACTLY [expected]; else the refusal and null. */
    private fun columnsOrRefuse(
        parameter: ParameterDefinition,
        at: String,
        columns: List<ColumnSchema>,
        expected: Set<String>,
        failures: ParameterSetFailures,
    ): Map<String, ColumnSchema>? {
        val names = columns.map { it.name.lowercase() }
        val missing = expected - names.toSet()
        val extra = names.filter { it !in expected } + names.groupBy { it }.filter { it.value.size > 1 }.keys
        if (missing.isEmpty() && extra.isEmpty()) return columns.associateBy { it.name.lowercase() }
        val unexpected = extra.distinct().sorted().map { it.safeEcho() }
        failures.add(
            ParameterErrorCodes.SELECTOR_COLUMNS_INVALID,
            "$at.template",
            "A ${parameter.kind.wire} source returns exactly ${expected.sorted()}; missing ${missing.sorted()}, unexpected $unexpected.",
            mapOf("parameter" to parameter.name.safeEcho(), "missing" to missing.sorted(), "extra" to unexpected),
        )
        return null
    }

    /** Record §6.4 from the metadata; true when `value` may flow into the declared type. */
    private fun valueType(
        parameter: ParameterDefinition,
        at: String,
        value: ColumnSchema,
        failures: ParameterSetFailures,
    ): Boolean {
        val from = TypeDescriptor(value.type, value.precision, value.scale)
        val to = TypeDescriptor(parameter.type, parameter.precision, parameter.scale)
        if (TypeWidening.isLossless(from, to)) return true
        failures.add(
            ParameterErrorCodes.SELECTOR_VALUE_TYPE_MISMATCH,
            "$at.template",
            "The selector's value column is ${describe(
                from,
            )}; the parameter declares ${describe(to)} — only a lossless widening is accepted (§6.4).",
            buildMap {
                put("parameter", parameter.name.safeEcho())
                put("source_type", describe(from))
                put("declared_type", describe(to))
                if (value.type == LogicalType.NULL) put("hint", "CAST the column")
            },
        )
        return false
    }

    private fun columnType(
        parameter: ParameterDefinition,
        at: String,
        column: String,
        expected: LogicalType,
        actual: ColumnSchema,
        failures: ParameterSetFailures,
    ) = failures.add(
        ParameterErrorCodes.SELECTOR_COLUMNS_INVALID,
        "$at.template",
        "The selector's $column column must be ${expected.wire}; it is ${actual.type.wire}.",
        mapOf("parameter" to parameter.name.safeEcho(), "column" to column, "reason" to "${column}_type"),
    )

    /** The parameter's own default for its children — never a query (record §4 step 5's "defaults where present"). */
    private fun staticDefault(parameter: ParameterDefinition): Any? {
        val single = parameter.defaultValue?.let { coerceSingleOrList(parameter, it) }
        if (single != null) return single
        val options = parameter.source?.constants.orEmpty()
        val option = (options.firstOrNull { it.isDefault } ?: options.firstOrNull())?.value
        val fromOption = option?.let { (ParameterCoercion.coerce(parameter.type, it) as? ParameterCoercion.Outcome.Coerced)?.value }
        val value = fromOption ?: TypeSamples.of(parameter.type)
        return if (parameter.cardinality == ParameterCardinality.MULTI) listOfNotNull(value) else value
    }

    private fun coerceSingleOrList(
        parameter: ParameterDefinition,
        default: com.fasterxml.jackson.databind.JsonNode,
    ): Any? =
        if (parameter.cardinality == ParameterCardinality.MULTI) {
            default
                .mapNotNull {
                    (ParameterCoercion.coerce(parameter.type, it) as? ParameterCoercion.Outcome.Coerced)?.value
                }.takeIf { it.isNotEmpty() }
        } else {
            (ParameterCoercion.coerce(parameter.type, default) as? ParameterCoercion.Outcome.Coerced)?.value
        }

    private companion object {
        const val VALUE = "value"
        const val DISPLAY_VALUE = "display_value"
        const val IS_DEFAULT = "is_default"
        const val MAX_DETAIL = 200
        val SELECT_COLUMNS = setOf(VALUE, DISPLAY_VALUE, IS_DEFAULT)
        val INPUT_COLUMNS = setOf(VALUE)

        /** P7's check: a clause is PRESENT (not that the order is total), read over [SqlClauseText.blanked]. */
        val ORDER_BY = Regex("\\border\\s+by\\b", RegexOption.IGNORE_CASE)

        fun describe(descriptor: TypeDescriptor): String =
            when {
                descriptor.precision != null -> "${descriptor.type.wire}(${descriptor.precision},${descriptor.scale})"
                descriptor.scale != null -> "${descriptor.type.wire}(scale ${descriptor.scale})"
                else -> descriptor.type.wire
            }

        /**
         * A canonical value of the COLUMN's type as the DECLARED type's canonical value — the §6.4
         * widenings only (`INTEGER` → the bigger integers and decimals, `BIGINTEGER` → `BIGDECIMAL`, a
         * decimal into a decimal); anything else is already the same type. Null when it cannot convert.
         */
        fun toDeclared(
            value: Any?,
            from: LogicalType,
            to: LogicalType,
        ): Any? =
            when {
                value == null -> null
                from == to -> value
                to == LogicalType.BIGINTEGER && value is Int -> BigInteger.valueOf(value.toLong())
                (to == LogicalType.DECIMAL || to == LogicalType.BIGDECIMAL) && value is Int -> BigDecimal.valueOf(value.toLong())
                to == LogicalType.BIGDECIMAL && value is BigInteger -> BigDecimal(value)
                (to == LogicalType.DECIMAL || to == LogicalType.BIGDECIMAL) && value is BigDecimal -> value
                else -> null
            }
    }
}
