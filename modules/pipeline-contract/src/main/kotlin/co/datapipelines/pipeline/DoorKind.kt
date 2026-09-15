package co.datapipelines.pipeline

import co.datapipelines.calculators.CalculatorKind
import co.datapipelines.calculators.CalculatorRegistry
import co.datapipelines.typesystem.LogicalType

/**
 * How a pipeline's time window is entered — the door a caller knocks on to run it (139 §C).
 *
 * The owner's principle, measured 2026-09-14: the skill's rule 13 — *parameters wear the
 * question's vocabulary; technical inputs are derived* — lost to habit one day after it
 * landed, because an instruction to a model is a hope. This classifier is the mechanical
 * half: it reads ONLY the pipeline body (its declared parameters and its nodes) and answers
 * whether the door is a **raw date pair** — the question's period hand-fixed as two DATE
 * literals by the author — or a **period** input the caller expresses in the question's own
 * vocabulary, or that no date input exists at all.
 *
 * It is a pure function: no repository, no registry I/O, no clock. The calculator-kind
 * lookup is injectable (the [Pipeline.calculatorOutputs] seam) so a test can pin a fixture
 * kind without the deployment's registry.
 */
enum class DoorKind {
    /**
     * Two or more DATE parameters and nothing that derives a window: no INTEGER parameter
     * named like a period (`year`, `quarter`, `month`, `*_year`) and no CALCULATOR node whose
     * kind declares a DATE output. The question's period has been baked in as two raw dates —
     * rule 13's miss.
     */
    RAW_DATE_PAIR,

    /**
     * Anything else that still has a date input: a period parameter, an anchor date with a
     * window calculator, or a single anchor DATE parameter.
     */
    PERIOD,

    /** No DATE-typed parameter at all — the pipeline takes no time door. */
    NONE,
}

object Door {
    /**
     * The INTEGER parameter names rule 13 accepts as a period door: the plain nouns, and any
     * compound ending in `_year` (`base_year`, `comp_year`, `fiscal_year`).
     */
    private val PERIOD_PARAM_NAMES = setOf("year", "quarter", "month")

    /** True when [name] is a period parameter's name per [PERIOD_PARAM_NAMES]. */
    fun isPeriodParamName(name: String): Boolean = name in PERIOD_PARAM_NAMES || name.endsWith("_year")

    /**
     * True when the CALCULATOR node's kind declares a DATE output — the catalog's window
     * writers (`period_start`, `period_end`, `prior_period`, `date_trunc`, and the
     * multi-output `period_bounds` / `trailing_periods`). An INTEGER-output date helper
     * (`quarter_of_year`, `iso_week`) derives a label, not a window.
     */
    fun derivesWindow(kind: CalculatorKind?): Boolean =
        kind != null && (kind.output == LogicalType.DATE || kind.outputs.any { it.type == LogicalType.DATE })

    /**
     * Classifies the door of a pipeline body from its declared [parameters] and [nodes].
     * A node whose `kind` the registry does not know contributes nothing — §12.10 already
     * refuses it, and a second opinion here would only fork the verdict.
     */
    fun classify(
        parameters: Map<String, Parameter>,
        nodes: List<Node>,
        kinds: (String) -> CalculatorKind? = CalculatorRegistry::find,
    ): DoorKind {
        val dateParams = parameters.filterValues { it.type == LogicalType.DATE }
        if (dateParams.isEmpty()) return DoorKind.NONE
        val hasPeriodParam = parameters.any { (name, param) -> param.type == LogicalType.INTEGER && isPeriodParamName(name) }
        val derivesWindow = nodes.any { node -> node.type == NodeType.CALCULATOR && derivesWindow(node.kind?.let(kinds)) }
        return if (dateParams.size >= 2 && !hasPeriodParam && !derivesWindow) DoorKind.RAW_DATE_PAIR else DoorKind.PERIOD
    }
}
