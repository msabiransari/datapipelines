package co.datapipelines.calculators

import co.datapipelines.typesystem.LogicalType

/**
 * One entry of the calculator catalog — a **pure, total function** from typed inputs to one
 * typed value ([calculators design §0.3/§0.4](../../../../../../../docs/superpowers/specs/2026-09-04-calculators-design.md),
 * user-facing catalog in [calculators.md](../../../../../../../docs/calculators.md)).
 *
 * ## What "pure" is enforced to mean
 *
 * A kind reads its [inputs] and nothing else: no clock, no locale, no database, no configuration.
 * "Today" is not a kind's to know — it arrives as `$current_date`, which the executor put in the
 * Context at execution start; the organisation's fiscal year arrives as `$org_fiscal_start_date`.
 * That is why the draft's per-kind `config` block is gone (§0.4): a kind is a function, not a
 * form, and everything that used to be config is an input the author can override per pipeline.
 *
 * The property is enforced by the module's build row (`:modules:calculators` may depend only on
 * `typesystem`) and by `CalculatorPurityTest`, not by this comment.
 *
 * ## Evaluation contract
 *
 * [evaluate] receives every declared input by name, already coerced to the Kotlin type its
 * [CalculatorInput.type] names ([CalculatorValues] documents the mapping). An absent optional
 * input arrives as `null` and the kind applies its documented default. Any refusal — an
 * unparseable format string, an unknown unit, a division by zero — is a
 * [CalculatorEvaluationException] naming the input, which the executor turns into
 * `pipeline.node.calculator_failed`. A kind never throws anything else, and never returns a
 * value whose type contradicts [output].
 */
interface CalculatorKind {
    /** The registry key an author writes as `"kind"`. Stable forever; the catalog is additive. */
    val kind: String

    /** Human-readable name for the editor's card and the MCP catalog. */
    val displayName: String

    /** One sentence: what the kind computes, in the terms an author thinks in. */
    val description: String

    /** The declared inputs, in the order the catalog documents them. */
    val inputs: List<CalculatorInput>

    /**
     * The canonical type of the value written to the node's `context_key` — or **null** for the
     * three kinds whose output type is whatever their input's was (`coalesce`, `if_null`,
     * `map`). Null reads as `ANY` in the catalog and in `calculators_list`.
     */
    val output: LogicalType?

    /** One worked example — the row `calculators.md` prints and `calculators_get` returns. */
    val example: CalculatorExample

    /**
     * Evaluates the kind. [values] holds every declared input by name; optional inputs the author
     * omitted are present and null.
     *
     * @throws CalculatorEvaluationException the inputs are individually well-typed but jointly
     *   unusable — an unknown `unit`, a `format` that does not compile, a zero denominator.
     */
    fun evaluate(values: Map<String, Any?>): Any?
}

/**
 * One declared input of a kind.
 *
 * `$name` in a node's `inputs` object is a **reference** to a Context key; anything else is a
 * literal typed against [type] (§0.3). So `"fiscal_start": "$org_fiscal_start_date"` reads the
 * deployment's setting and `"fiscal_start": "09-15"` pins this pipeline's own — the same input,
 * two authoring shapes, one type.
 */
data class CalculatorInput(
    val name: String,
    /**
     * The canonical type of each value this input takes, or **null** for an input that accepts
     * any canonical type (`coalesce`'s values, `if_null`'s default, `map`'s pairs). A null type
     * is not a hole in the type system: it is the honest declaration for a kind that does not
     * look at the value, and the validator still refuses a literal whose JSON shape has no
     * canonical reading at all.
     */
    val type: LogicalType?,
    /** What it means, in the terms an author thinks in. */
    val description: String,
    /** False when the author may omit it; the kind then applies [defaultDescription]'s default. */
    val required: Boolean = true,
    /**
     * [Arity.LIST] when the wire value is a JSON **array** of [type] rather than one value.
     *
     * The design's §0.3 term for this is `variadic`, restricted to the last input. That
     * restriction is kept for the one open-ended case it was written for — `coalesce`, whose
     * argument count IS the authoring choice, and where a second list input would be
     * unparseable. Kinds with *fixed-role* lists (`add_business_days`' weekend days and
     * holidays, `map`'s from/to pairs) declare more than one, because each is a named role and
     * nothing about them is ambiguous. `CalculatorRegistryTest` pins both halves of that rule.
     */
    val arity: Arity = Arity.SINGLE,
    /** How the default reads in the catalog — null when [required]. */
    val defaultDescription: String? = null,
) {
    /** The design's `variadic` flag: this input takes a JSON array. */
    val isList: Boolean get() = arity == Arity.LIST

    /** How the type reads in the catalog and in `calculators_list`. */
    val typeName: String get() = type?.wire ?: ANY_TYPE

    companion object {
        /** How a null [type] (or a null [CalculatorKind.output]) is spelled to a reader. */
        const val ANY_TYPE = "ANY"
    }

    enum class Arity {
        /** One value of [CalculatorInput.type]. */
        SINGLE,

        /** A JSON array of [CalculatorInput.type]. */
        LIST,
    }
}

/** The worked example a catalog row and `calculators_get` both print. */
data class CalculatorExample(
    /** Input values as an author would write them, in `inputs`-object order. */
    val inputs: Map<String, String>,
    /** The value the kind produces for [inputs], rendered as the catalog prints it. */
    val output: String,
)

/**
 * A kind refused the inputs it was given.
 *
 * Deliberately not a `DatapipelinesException`: that type lives in `typesystem` and carries an
 * error CODE, and a code is a contract-layer decision. This module knows what went wrong; the
 * executor knows it is `pipeline.node.calculator_failed` (pipeline-contract §13.4) and attaches
 * the code, the node and the kind. Keeping the split means the catalog stays a library anybody
 * could call, including a future editor preview that has no execution to fail.
 */
class CalculatorEvaluationException(
    /** The input whose value made the evaluation impossible; null when it is the combination. */
    val input: String?,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
